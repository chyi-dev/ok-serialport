package com.ok.serialport

import com.ok.serialport.data.Request
import com.ok.serialport.data.Response
import com.ok.serialport.data.ResponseProcess
import com.ok.serialport.data.ResponseRule
import com.ok.serialport.data.SerialPortProcess
import com.ok.serialport.exception.ReconnectFailException
import com.ok.serialport.interceptor.Interceptor
import com.ok.serialport.interceptor.RealInterceptorChain
import com.ok.serialport.listener.OnConnectListener
import com.ok.serialport.listener.OnDataListener
import com.ok.serialport.listener.OnPerformanceListener
import com.ok.serialport.stick.AbsStickPacketHandle
import com.ok.serialport.stick.BaseStickPacketHandle
import com.ok.serialport.utils.SerialLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 管理串口连接和请求处理
 * @author Leyi
 * @date 2024/10/31 11:47
 */
class OkSerialPort private constructor(
    // 串口地址
    internal val devicePath: String,
    // 波特率
    internal val baudRate: Int,
    // 标志位
    internal val flags: Int,
    // 数据位
    internal val dataBit: Int,
    // 停止位
    internal val stopBit: Int,
    // 校验位：0 表示无校验位，1 表示奇校验，2 表示偶校验
    internal val parity: Int,
    // 连接最大重试次数 需要大于0 =0 不重试
    private val retryCount: Int,
    // 连接重试间隔
    private val retryInterval: Long,
    // 发送间隔
    internal val sendInterval: Long,
    // 读取间隔
    internal val readInterval: Long,
    // 是否使用阻塞式读取（默认true，使用专用线程+阻塞读取以提升性能）
    internal val useBlockingRead: Boolean,
    // 读取线程优先级（默认THREAD_PRIORITY_URGENT_AUDIO）
    internal val readThreadPriority: Int,
    // 最大请求数
    internal val maxRequestSize: Int,
    // 日志
    internal val logger: SerialLogger,
    // 串口粘包处理
    internal val stickPacketHandle: AbsStickPacketHandle,
    internal val responseRules: MutableList<ResponseRule>,
    internal val responseInterceptors: MutableList<Interceptor<Response>>,
    private val requestInterceptors: MutableList<Interceptor<Request>>
) {
    private val serialPortProcess by lazy {
        SerialPortProcess(this)
    }
    private val isConnected = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    //重连次数
    private var retryTimes = 0
    
    // 是否正在重连
    private val isReconnecting = AtomicBoolean(false)

    // 串口连接监听
    private var onConnectListener: OnConnectListener? = null

    // 串口全局数据监听
    internal var onDataListener: OnDataListener? = null
    
    // 重连Job，用于取消重连
    private var reconnectJob: kotlinx.coroutines.Job? = null

    /**
     * 串口连接
     */
    fun connect() {
        if (isConnect()) return
        try {
            serialPortProcess.connect()
        } catch (e: Exception) {
            logger.log("串口(${devicePath}:${baudRate})连接失败：${e.message}")
            setConnected(false)
            onConnectListener?.onDisconnect(devicePath, e)
            handleConnectionFailure(e)
            return
        }
        setConnected(true)
        logger.log("串口(${devicePath}:${baudRate})连接成功")
        try {
            serialPortProcess.start(coroutineScope)
            onConnectListener?.onConnect(devicePath)
            retryTimes = 0
            isReconnecting.set(false)
        } catch (e: Exception) {
            logger.log("读写线程启动失败：${e.message}")
            setConnected(false)
            onConnectListener?.onDisconnect(devicePath, e)
            handleConnectionFailure(e)
        }
    }
    
    /**
     * 处理连接失败
     */
    private fun handleConnectionFailure(e: Exception) {
        // 判断错误类型
        val isPermanentError = when (e) {
            is SecurityException -> true // 权限错误，永久错误
            is java.io.FileNotFoundException -> true // 文件不存在，永久错误
            else -> false // 其他错误可能是临时的
        }
        
        if (isPermanentError) {
            logger.log("检测到永久错误，停止重连：${e.javaClass.simpleName}")
            retryTimes = retryCount // 标记为已重试完毕
        }
        
        reconnect()
    }

    private fun setConnected(value: Boolean) {
        isConnected.set(value)
    }
    
    /**
     * 内部方法：由SerialPortProcess调用，用于标记连接断开
     */
    internal fun markDisconnected() {
        setConnected(false)
    }

    fun isConnect(): Boolean = isConnected.get()

    /**
     * 发送数据
     * @param request Request
     */
    fun request(request: Request) {
        if (isConnect()) {
            try {
                val chain = RealInterceptorChain(requestInterceptors, 0, request)
                val newRequest = chain.proceed(request)
                request.data(newRequest.data)
                    .tag(newRequest.tag)
                    .timeout(newRequest.timeout)
                    .timeoutRetry(newRequest.timeoutRetry)
                serialPortProcess.addRequest(request)
            } catch (e: Exception) {
                request.onResponseListener?.onFailure(request, e)
                return
            }
        }
    }

    /**
     * 取消请求
     * @param request SerialRequest
     */
    fun cancel(request: Request): Boolean {
        return serialPortProcess.cancelRequest(request)
    }

    /**
     * 添加数据数据处理器
     */
    fun addProcess(process: ResponseProcess) {
        serialPortProcess.addResponseProcess(process)
    }

    /**
     * 移除数据处理
     * 针对无限次重试
     */
    fun removeProcess(process: ResponseProcess) {
        serialPortProcess.removeResponseProcess(process)
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
     * 添加性能监听器
     */
    fun addPerformanceListener(listener: OnPerformanceListener) {
        serialPortProcess.setPerformanceListener(listener)
    }

    /**
     * 移除性能监听器
     */
    fun removePerformanceListener() {
        serialPortProcess.setPerformanceListener(null)
    }

    /**
     * 重连
     */
    private fun reconnect() {
        // 如果已经达到重试次数，不再重连
        if (retryTimes >= retryCount) {
            if (!isConnect()) {
                onConnectListener?.onDisconnect(devicePath, ReconnectFailException("重连失败，已重试${retryCount}次"))
            }
            return
        }
        
        // 如果正在重连，不重复启动
        if (isReconnecting.getAndSet(true)) {
            return
        }
        
        reconnectJob = coroutineScope.launch {
            try {
                delay(retryInterval)
                retryTimes++
                logger.log("开始重连，进度：$retryTimes / $retryCount")
                connect()
            } catch (e: Exception) {
                logger.log("重连过程异常：${e.message}")
                isReconnecting.set(false)
            }
        }
    }

    /**
     * 断开串口连接
     */
    fun disconnect() {
        // 取消重连任务
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting.set(false)
        retryTimes = 0
        
        if (isConnect()) {
            serialPortProcess.disconnect()
            setConnected(false)
            onConnectListener = null
            onDataListener = null
        }
    }

    class Builder {
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
        private var parity: Int = 0

        // 连接最大重试次数 需要大于0 =0 不重试
        private var maxRetry: Int = 3

        // 连接重试间隔
        private var retryInterval: Long = 1000L

        // 发送间隔
        private var sendInterval: Long = 300L

        // 读取间隔
        private var readInterval: Long = 50L

        // 是否使用阻塞式读取（默认true，使用专用线程+阻塞读取以提升性能）
        private var useBlockingRead: Boolean = true

        // 读取线程优先级（默认THREAD_PRIORITY_URGENT_AUDIO = -19）
        private var readThreadPriority: Int = -19

        // 最大请求数
        private var maxRequestSize: Int = 100

        // 日志
        private var logger: SerialLogger = SerialLogger()

        // 串口粘包处理
        private var stickPacketHandle: AbsStickPacketHandle = BaseStickPacketHandle()

        // 响应匹配规则
        private var responseRules = mutableListOf<ResponseRule>()

        // 响应拦截器
        private var responseInterceptors = mutableListOf<Interceptor<Response>>()

        // 请求拦截器
        private var requestInterceptors = mutableListOf<Interceptor<Request>>()

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

        /**
         * 设置是否使用阻塞式读取
         * @param useBlockingRead true=使用专用线程+阻塞读取（推荐，性能更好），false=使用协程轮询（兼容旧版本）
         */
        fun useBlockingRead(useBlockingRead: Boolean) = apply {
            this.useBlockingRead = useBlockingRead
        }

        /**
         * 设置读取线程优先级
         * @param priority 线程优先级，建议使用Process.THREAD_PRIORITY_URGENT_AUDIO(-19)或更高
         */
        fun readThreadPriority(priority: Int) = apply {
            this.readThreadPriority = priority
        }

        fun maxRequestSize(maxRequestSize: Int) = apply {
            this.maxRequestSize = maxRequestSize
        }

        fun logger(logger: SerialLogger) = apply {
            this.logger = logger
        }

        fun stickPacketHandle(stickPacketHandle: AbsStickPacketHandle) = apply {
            this.stickPacketHandle = stickPacketHandle
        }

        fun addResponseRule(rule: ResponseRule) = apply {
            this.responseRules.add(rule)
        }

        fun addRequestInterceptor(interceptor: Interceptor<Request>) = apply {
            this.requestInterceptors.add(interceptor)
        }

        fun addResponseInterceptor(interceptor: Interceptor<Response>) = apply {
            this.responseInterceptors.add(interceptor)
        }

        fun build(): OkSerialPort {
            require(devicePath != null) { "串口地址devicePath不能为空" }
            require(baudRate != null && baudRate!! > 0) { "串口波特率baudRate不能为空或者小于0" }
            require(flags >= 0) { "标识位不能小于0" }
            require(dataBit >= 0) { "数据位不能小于0" }
            require(stopBit >= 0) { "停止位不能小于0" }
            require(parity >= 0) { "校验位不能小于0" }
            require(maxRetry >= 0) { "重试次数不能小于0" }
            require(retryInterval >= 500) { "重试时间间隔不能小于500毫秒" }
            require(sendInterval >= 100) { "发送数据时间间隔不能小于100毫秒" }
            require(readInterval >= 1) { "读取数据时间间隔不能小于1毫秒" }
            require(maxRequestSize in 1..10000) { "队列容量区间为1-10000" }
            // 如果使用阻塞读取，readInterval仅用于超时检查，不影响实际读取延迟

            return OkSerialPort(
                devicePath!!, baudRate!!, flags, dataBit, stopBit, parity, maxRetry, retryInterval,
                sendInterval, readInterval, useBlockingRead, readThreadPriority, maxRequestSize, logger, stickPacketHandle,
                responseRules, responseInterceptors, requestInterceptors
            )
        }
    }
}

