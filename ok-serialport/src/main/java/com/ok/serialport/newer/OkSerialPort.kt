package com.ok.serialport.newer

import android.os.Handler
import android.os.Looper
import androidx.annotation.IntRange
import com.ok.serialport.SerialLogger
import com.ok.serialport.enums.ResponseState
import com.ok.serialport.enums.SerialErrorCode
import com.ok.serialport.jni.SerialPort
import com.ok.serialport.model.SerialRequest
import com.ok.serialport.model.SerialResponse
import com.ok.serialport.newer.data.Request
import com.ok.serialport.newer.job.DataJob
import com.ok.serialport.newer.listener.OnConnectListener
import com.ok.serialport.newer.listener.OnDataListener
import com.ok.serialport.process.ResponseProcess
import com.ok.serialport.stick.AbsStickPacketHandle
import com.ok.serialport.stick.BaseStickPacketHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 管理串口连接和请求处理
 * @author Leyi
 * @date 2024/10/31 11:47
 */
class OkSerialPort private constructor(
    // 串口地址
    private val devicePath: String,
    // 波特率
    private val baudRate: Int,
    // 标志位
    private val flags: Int,
    // 数据位
    private val dataBit: Int,
    // 停止位
    private val stopBit: Int,
    // 校验位：0 表示无校验位，1 表示奇校验，2 表示偶校验
    private val parity: Int,
    // 连接最大重试次数 需要大于0 =0 不重试
    private val maxRetry: Int,
    // 连接重试间隔
    private val retryInterval: Long,
    // 发送间隔
    internal val sendInterval: Long,
    // 读取间隔
    private val readInterval: Long,
    // 离线识别间隔
    private val offlineIntervalSecond: Int,
    // 日志
    private val logger: SerialLogger,
    // 串口粘包处理
    private val stickPacketHandle: AbsStickPacketHandle
) : SerialPort() {
    companion object {
        //超过最大限制为无限次重试，重试时间间隔增加
        private const val MAX_RETRY_COUNT = 100
    }

    private val serialPortClient: SerialPortClient by lazy {
        SerialPortClient(devicePath, baudRate, flags, dataBit, stopBit, parity, logger)
    }
    private val dataJob by lazy {
        DataJob(this)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val isConnected = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var readJob: Job? = null
    private var sendJob: Job? = null
    private val sendQueue = ConcurrentLinkedQueue<Request>()
    private val responseProcesses = ConcurrentLinkedQueue<ResponseProcess>()

    // 串口连接监听
    private var onConnectListener: OnConnectListener? = null

    // 串口全局数据监听
    private var onDataListener: OnDataListener? = null

    /**
     * 串口连接
     */
    fun connect() {
        if (isConnect()) return
        try {
            serialPortClient.connect()
        } catch (e: Exception) {
            logger.log("串口(${devicePath}:${baudRate})连接失败：${e.message}")
            onConnectListener?.onDisconnect(devicePath, e)
            return
        }
        setConnected(true)
        logger.log("串口连接成功")
        try {
            dataJob.start(sendAction = {
                sendAction()
            }, readAction = {
                readAction()
            })
            sendQueue.clear()
            responseProcesses.clear()
            onConnectListener?.onConnect(devicePath)
        } catch (e: Exception) {
            logger.log("读写线程启动失败：${e.message}")
            onConnectListener?.onDisconnect(devicePath, e)
        }
    }

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
    private suspend fun sendAction() {
        val request = sendQueue.last()
        try {
            serialPortClient.write(request.data)
        } catch (e: IOException) {
            // TODO: 回调失败
        }
        handler.post { onDataListener?.onRequest(request.data) }
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

    /**
     * 开始读数据
     */
    private fun readAction() {
        if (readJob?.isActive == true) return

        readJob = coroutineScope.launch {
            try {
                while (isConnect() && isActive) {
                    try {
                        delay(readInterval)
                        if (fileInputStream != null) {
                            val buffer = stickPacketHandle.execute(fileInputStream!!)
                            if (buffer != null && buffer.isNotEmpty()) {
                                handler.post { onDataListener?.onResponse(buffer) }

                                var useProcess: ResponseProcess? = null
                                val iterator = responseProcesses.iterator()
                                while (iterator.hasNext()) {
                                    val process = iterator.next()
                                    if (process.isMatch(buffer)) {
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
        val processes = mutableListOf<ResponseProcess>()
        val currentTime = System.currentTimeMillis()
        responseProcesses.forEach {
            if (currentTime - it.sendTime > it.timeout && it.timeout > 0) {
                processes.add(it)
            }
        }
        for (process in processes) {
            if (responseProcesses.contains(process)) {
                responseProcesses.remove(process)
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
    fun addDataProcess(process: ResponseProcess) {
        responseProcesses.add(process)
    }

    /**
     * 移除数据处理
     */
    fun removeDataProcess(process: ResponseProcess) {
        responseProcesses.remove(process)
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
        responseProcesses.clear()
        if (isConnect()) {
            if (fileDescriptor != null) {
                close()
                fileDescriptor = null
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
        private var devicePath: String? = null

        // 波特率
        private var baudRate: Int? = null

        // 标志位
        private var flags: Int = 0

        // 数据位
        private var dataBit: Int = 8

        // 停止位
        private var stopBit: Int = 1

        // 校验位：0 表示无校验位，1 表示奇校验，2 表示偶校验
        @IntRange(from = 0, to = 2)
        private var parity: Int = 0

        // 连接最大重试次数 需要大于0 =0 不重试
        private var maxRetry: Int = 3

        // 连接重试间隔
        private var retryInterval: Long = 200L

        // 发送间隔
        var sendInterval: Long = 100L

        // 读取间隔
        private var readInterval: Long = 50L

        // 离线识别间隔
        private var offlineIntervalSecond: Int = 0

        // 日志
        private var logger: SerialLogger = SerialLogger()

        // 串口粘包处理
        private var stickPacketHandle: AbsStickPacketHandle = BaseStickPacketHandle()

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

        fun build(): OkSerialPort {
            require(devicePath != null) { "串口地址devicePath不能为空" }
            require(baudRate != null && baudRate!! > 0) { "串口波特率baudRate不能为空或者小于0" }
            require(flags >= 0) { "标识位不能小于0" }
            require(dataBit >= 0) { "数据位不能小于0" }
            require(stopBit >= 0) { "停止位不能小于0" }
            require(parity >= 0) { "校验位不能小于0" }
            require(maxRetry >= 0) { "重试次数不能小于0" }
            require(retryInterval >= 0) { "重试时间间隔不能小于0" }
            require(sendInterval >= 0) { "发送数据时间间隔不能小于0" }
            require(readInterval >= 0) { "读取数据时间间隔不能小于0" }

            return OkSerialPort(
                devicePath!!, baudRate!!, flags, dataBit, stopBit, parity, maxRetry, retryInterval,
                sendInterval, readInterval, offlineIntervalSecond, logger, stickPacketHandle
            )
        }
    }
}

