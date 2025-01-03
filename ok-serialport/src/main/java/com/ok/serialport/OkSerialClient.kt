package com.ok.serialport

import android.os.Handler
import android.os.Looper
import androidx.annotation.IntRange
import com.ok.serialport.stick.AbsStickPacketHandle
import com.ok.serialport.process.BaseDataProcess
import com.ok.serialport.listener.OnConnectListener
import com.ok.serialport.model.SerialRequest
import com.ok.serialport.jni.SerialPort
import com.ok.serialport.listener.OnDataListener
import com.ok.serialport.enums.ResponseState
import com.ok.serialport.enums.SerialErrorCode
import com.ok.serialport.model.SerialResponse
import com.ok.serialport.stick.BaseStickPacketHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 管理串口连接和请求处理
 * @author Leyi
 * @date 2024/10/31 11:47
 */
class OkSerialClient(
    builder: Builder
) : SerialPort() {
    companion object {
        //超过最大限制为无限次重试，重试时间间隔增加
        private const val MAX_RETRY_COUNT = 100
    }

    // 串口地址
    private val devicePath: String = builder.devicePath!!

    // 波特率
    private val baudRate: Int = builder.baudRate!!

    // 标志位
    private val flags: Int = builder.flags

    // 数据位
    private val dataBit: Int = builder.dataBit

    // 停止位
    private val stopBit: Int = builder.stopBit

    // 校验位：0 表示无校验位，1 表示奇校验，2 表示偶校验
    private val parity: Int = builder.parity

    // 连接最大重试次数 需要大于0 =0 不重试
    private val maxRetry: Int = builder.maxRetry

    // 连接重试间隔
    private val retryInterval: Long = builder.retryInterval

    // 发送间隔
    private val sendInterval: Long = builder.sendInterval

    // 读取间隔
    private val readInterval: Long = builder.readInterval

    // 离线识别间隔
    private val offlineIntervalSecond: Int = builder.offlineIntervalSecond

    // 日志
    private val logger: SerialLogger = builder.logger

    // 串口连接监听
    private var onConnectListener: OnConnectListener? = null

    // 串口粘包处理
    private val stickPacketHandle: AbsStickPacketHandle = builder.stickPacketHandle

    // 串口全局数据监听
    private var onDataListener: OnDataListener? = null

    private val handler = Handler(Looper.getMainLooper())

    private var fileInputStream: FileInputStream? = null
    private var fileOutputStream: FileOutputStream? = null
    private var fileDispatcher: FileDescriptor? = null
    private val isConnected = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var readJob: Job? = null
    private var sendJob: Job? = null
    private lateinit var sendQueue: ConcurrentLinkedQueue<SerialRequest>

    private lateinit var dataProcess: ConcurrentLinkedQueue<BaseDataProcess>


    fun connect() {
        //连接成功直接返回
        if (isConnect()) {
            return
        }
        //校验权限
        if (!checkSerialPortPermission(devicePath)) {
            onConnectListener?.onDisconnect(devicePath, SerialErrorCode.PERMISSION_DENIED.message)
            return
        }
        try {
            fileDispatcher = open(
                devicePath, baudRate, flags, dataBit, stopBit, parity
            )
            fileInputStream = FileInputStream(fileDispatcher)
            fileOutputStream = FileOutputStream(fileDispatcher)

            setConnected(true)
            startRead()
            startSend()
        } catch (e: Exception) {
            fileDispatcher = null
            setConnected(false)
        }
        if (isConnect()) {
            logger.log("串口连接成功")
            sendQueue.clear()
            dataProcess.clear()
            onConnectListener?.onConnect(devicePath)
            return
        }
        reconnect()
    }

    @Synchronized
    private fun setConnected(value: Boolean) {
        isConnected.set(value)
    }

    fun isConnect(): Boolean = isConnected.get()

    /**
     * 发送数据
     * @param request SerialRequest
     */
    fun send(request: SerialRequest) {
        if (isConnect()) {
            request.sendTime = 0
            sendQueue.add(request)
        }
    }

    /**
     * 开启发送线程
     */
    private fun startSend() {
        if (sendJob?.isActive == true) return
        sendQueue = ConcurrentLinkedQueue<SerialRequest>()
        sendJob = coroutineScope.launch {
            try {
                send@ while (isConnect() && isActive) {
                    delay(sendInterval)

                    clearTimeProcess()

                    val request = sendQueue.lastOrNull()
                    if (fileOutputStream == null || request == null) {
                        continue@send
                    }
                    for (count in 0..request.maxRetries) {
                        try {
                            fileOutputStream?.let { output ->
                                output.write(request.data)
                                output.flush()
                            }
                            request.sendTime = System.currentTimeMillis()
                            sendQueue.remove(request)
                            addDataProcess(request)
                            handler.post { onDataListener?.onRequest(request.data) }
                            continue@send
                        } catch (ex: IOException) {
                            delay(request.retryInterval)
                        }
                    }
                    sendQueue.remove(request)
                    handler.post {
                        request.response(
                            SerialResponse(
                                byteArrayOf(),
                                ResponseState.SEND_FAIL
                            )
                        )
                    }
                }
            } catch (ignore: CancellationException) {
            } catch (ex: Exception) {
                ex.printStackTrace()
                setConnected(false)
                reconnect()
            }
        }
    }

    /**
     * 开始读数据
     */
    private fun startRead() {
        if (readJob?.isActive == true) return
        dataProcess = ConcurrentLinkedQueue<BaseDataProcess>()
        readJob = coroutineScope.launch {
            try {
                while (isConnect() && isActive) {
                    try {
                        delay(readInterval)
                        if (fileInputStream != null) {
                            val buffer = stickPacketHandle.execute(fileInputStream!!)
                            if (buffer != null && buffer.isNotEmpty()) {
                                handler.post { onDataListener?.onResponse(buffer) }

                                var useProcess: BaseDataProcess? = null
                                val iterator = dataProcess.iterator()
                                while (iterator.hasNext()) {
                                    val process = iterator.next()
                                    if (process.process(buffer)) {
                                        useProcess = process
                                        handler.post {
                                            useProcess.response(
                                                SerialResponse(
                                                    buffer,
                                                    ResponseState.SUCCESS
                                                )
                                            )
                                        }
                                        break
                                    }
                                }
                                if (useProcess != null && useProcess.timeout != 0L) {
                                    removeDataProcess(useProcess)
                                }
                            }
                        }
                    } catch (ex: IOException) {
                        setConnected(false)
                        reconnect()
                    }
                }
            } catch (ignore: CancellationException) {
            } catch (ex: Exception) {
                ex.printStackTrace()
                setConnected(false)
                reconnect()
            }
        }
    }

    /**
     * 清理超时数据处理任务
     */
    private fun clearTimeProcess() {
        val processes = mutableListOf<BaseDataProcess>()
        val currentTime = System.currentTimeMillis()
        dataProcess.forEach {
            if (currentTime - it.sendTime > it.timeout && it.timeout > 0) {
                processes.add(it)
            }
        }
        for (process in processes) {
            if (dataProcess.contains(process)) {
                dataProcess.remove(process)
                if (process is SerialRequest
                    && process.isTimeoutRetry
                    && process.timeoutRetryCount >= 1
                ) {
                    process.timeoutRetryCount--
                    send(process)
                    continue
                }
                handler.post {
                    process.response(SerialResponse(byteArrayOf(), ResponseState.TIME_OUT))
                }
            }
        }
    }

    /**
     * 添加数据数据处理器
     */
    fun addDataProcess(process: BaseDataProcess) {
        dataProcess.add(process)
    }

    /**
     * 移除数据处理
     */
    fun removeDataProcess(process: BaseDataProcess) {
        dataProcess.remove(process)
    }

    /**
     * 添加连接监听器
     */
    fun addConnectListener(onConnectListener: OnConnectListener) {
        this.onConnectListener = onConnectListener
    }

    /**
     * 移除连接监听器
     */
    fun removeConnectListener() {
        this.onConnectListener = null
    }

    /**
     * 添加数据监听器
     */
    fun addDataListener(onDataListener: OnDataListener) {
        this.onDataListener = onDataListener
    }

    /**
     * 移除数据监听器
     */
    fun removeDataListener() {
        this.onDataListener = null
    }

    /**
     * 检查串口权限
     * @param devicePath String 串口地址
     * @return Boolean 是否有权限
     */
    private fun checkSerialPortPermission(devicePath: String): Boolean {
        val deviceFile = File(devicePath)
        if (!deviceFile.canRead() || !deviceFile.canWrite()) {
            val chmod = chmod777(deviceFile)
            if (chmod) {
                return false
            }
        }
        return true
    }

    /**
     * 重连
     */
    private fun reconnect() {
        if (reconnectJob?.isActive == true) return
        readJob?.cancel(cause = CancellationException("Read job canceled"))
        sendJob?.cancel(cause = CancellationException("Send job canceled"))
        reconnectJob = coroutineScope.launch {
            var count = 0
            while (!isConnect() && isActive && (count < maxRetry || maxRetry > MAX_RETRY_COUNT)) {
                logger.log("连接失败，$retryInterval ms 后重试 (${count + 1} / $maxRetry)")
                var tempInterval = retryInterval
                if (count > 10) {
                    tempInterval *= (count + 10 / 10)
                }
                delay(tempInterval) // 重连间隔
                connect()
                count++
            }
            if (!isConnect()) {
                onConnectListener?.onDisconnect(
                    devicePath,
                    SerialErrorCode.SERIAL_PORT_OPEN_FAILED.message
                )
            }
        }
    }

    /**
     * 断开串口连接
     */
    fun disconnect() {
        reconnectJob?.cancel(cause = CancellationException("Reconnect canceled"))
        readJob?.cancel(cause = CancellationException("Read job canceled"))
        sendJob?.cancel(cause = CancellationException("Send job canceled"))
        reconnectJob = null
        readJob = null
        sendJob = null
        sendQueue.clear()
        dataProcess.clear()
        if (isConnect()) {
            if (fileDispatcher != null) {
                close()
                fileDispatcher = null
            }
            safeCloseStreams()
            setConnected(false)
        }
    }

    @Synchronized
    private fun safeCloseStreams() {
        closeStream(fileInputStream)
        closeStream(fileOutputStream)
        fileInputStream = null
        fileOutputStream = null
    }

    /**
     * 关闭流
     * @param stream Closeable? 被关闭流
     */
    private fun closeStream(stream: Closeable?) {
        try {
            stream?.close()
        } catch (ex: IOException) {
            ex.printStackTrace()
        }
    }


    class Builder() {
        // 串口地址
        internal var devicePath: String? = null

        // 波特率
        internal var baudRate: Int? = null

        // 标志位
        internal var flags: Int = 0

        // 数据位
        internal var dataBit: Int = 8

        // 停止位
        internal var stopBit: Int = 1

        // 校验位：0 表示无校验位，1 表示奇校验，2 表示偶校验
        @IntRange(from = 0, to = 2)
        internal var parity: Int = 0

        // 连接最大重试次数 需要大于0 =0 不重试
        internal var maxRetry: Int = 3

        // 连接重试间隔
        internal var retryInterval: Long = 200L

        // 发送间隔
        internal var sendInterval: Long = 100L

        // 读取间隔
        internal var readInterval: Long = 50L

        // 离线识别间隔
        internal var offlineIntervalSecond: Int = 0

        // 日志
        internal var logger: SerialLogger = SerialLogger()

        // 串口粘包处理
        internal var stickPacketHandle: AbsStickPacketHandle = BaseStickPacketHandle()

        internal constructor(okSerialClient: OkSerialClient) : this() {
            this.devicePath = okSerialClient.devicePath
            this.baudRate = okSerialClient.baudRate
            this.flags = okSerialClient.flags
            this.dataBit = okSerialClient.dataBit
            this.stopBit = okSerialClient.stopBit
            this.parity = okSerialClient.parity
            this.maxRetry = okSerialClient.maxRetry
            this.retryInterval = okSerialClient.retryInterval
            this.sendInterval = okSerialClient.sendInterval
            this.readInterval = okSerialClient.readInterval
            this.offlineIntervalSecond = okSerialClient.offlineIntervalSecond
            this.logger = okSerialClient.logger
            this.stickPacketHandle = okSerialClient.stickPacketHandle
        }

        fun devicePath(devicePath: String) = apply {
            this.devicePath = devicePath
        }

        fun baudRate(baudRate: Int) = apply {
            this.baudRate = baudRate
        }

        fun flags(flags: Int) = apply {
            this.flags = flags
        }

        fun dataBit(dataBit: Int) = apply {
            this.dataBit = dataBit
        }

        fun stopBit(stopBit: Int) = apply {
            this.stopBit = stopBit
        }

        fun parity(parity: Int) = apply {
            this.parity = parity
        }

        fun maxRetry(maxRetry: Int) = apply {
            this.maxRetry = maxRetry
        }

        fun retryInterval(retryInterval: Long) = apply {
            this.retryInterval = retryInterval
        }

        fun sendInterval(sendInterval: Long) = apply {
            this.sendInterval = sendInterval
        }

        fun readInterval(readInterval: Long) = apply {
            this.readInterval = readInterval
        }

        fun offlineIntervalSecond(offlineIntervalSecond: Int) = apply {
            this.offlineIntervalSecond = offlineIntervalSecond
        }

        fun logger(logger: SerialLogger) = apply {
            this.logger = logger
        }

        fun stickPacketHandle(stickPacketHandle: AbsStickPacketHandle) = apply {
            this.stickPacketHandle = stickPacketHandle
        }

        fun build(): OkSerialClient {
            require(devicePath != null) { "串口地址devicePath不能为空" }
            require(baudRate == null || baudRate!! > 0) { "串口波特率baudRate不能为空或者小于0" }
            require(flags >= 0) { "标识位不能小于0" }
            require(dataBit >= 0) { "数据位不能小于0" }
            require(stopBit >= 0) { "停止位不能小于0" }
            require(parity >= 0) { "校验位不能小于0" }
            require(maxRetry >= 0) { "重试次数不能小于0" }
            require(retryInterval >= 0) { "重试时间间隔不能小于0" }
            require(sendInterval >= 0) { "发送数据时间间隔不能小于0" }
            require(readInterval >= 0) { "读取数据时间间隔不能小于0" }
            return OkSerialClient(this)
        }
    }
}

