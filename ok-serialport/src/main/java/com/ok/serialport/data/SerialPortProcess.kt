package com.ok.serialport.data

import android.os.Handler
import android.os.Looper
import com.ok.serialport.OkSerialPort
import com.ok.serialport.exception.ResponseTimeoutException
import com.ok.serialport.interceptor.RealInterceptorChain
import com.ok.serialport.jni.SerialPort
import com.ok.serialport.listener.OnPerformanceListener
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * 串口数据处理
 * @author Leyi
 * @date 2025/1/10 16:17
 */
class SerialPortProcess(private val okSerialPort: OkSerialPort) : SerialPort(
    okSerialPort.devicePath,
    okSerialPort.baudRate,
    okSerialPort.flags,
    okSerialPort.dataBit,
    okSerialPort.stopBit,
    okSerialPort.parity,
    okSerialPort.logger
) {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var coroutineScope: CoroutineScope
    private var sendJob: Job? = null
    private var readJob: Job? = null
    private var readThread: Thread? = null
    private val shouldStopReading = AtomicBoolean(false)
    private val readyRequests = ConcurrentLinkedDeque<Request>()
    private val runningRequests = ConcurrentLinkedQueue<ResponseProcess>()
    private val timeoutRequests = mutableListOf<ResponseProcess>()
    private val isBlocking = AtomicBoolean(false)
    private var blockingRequest: Request? = null
    
    // 超时检查任务
    private var timeoutCheckJob: Job? = null
    private val lastTimeoutCheckTime = java.util.concurrent.atomic.AtomicLong(0)
    private val timeoutCheckInterval = 100L // 每100ms检查一次超时
    
    // 性能监控
    private var performanceListener: OnPerformanceListener? = null
    private var performanceUpdateJob: Job? = null
    private val performanceMetrics = PerformanceMetrics()
    private val totalBytesSent = AtomicLong(0)
    private val totalBytesReceived = AtomicLong(0)
    private val totalRequestsSent = AtomicLong(0)
    private val totalResponsesReceived = AtomicLong(0)
    private val dataLossCount = AtomicLong(0)
    private val timeoutRequestCount = AtomicLong(0)
    
    // 读取延迟统计
    private val readLatencies = mutableListOf<Long>()
    private val maxReadLatency = AtomicLong(0)
    private val minReadLatency = AtomicLong(Long.MAX_VALUE)
    private val readLatencyLock = Any()
    
    // 数据丢失检测：记录最后接收时间
    private val lastReceiveTime = AtomicLong(0)
    private val lastReceiveSize = AtomicLong(0)

    fun start(coroutineScope: CoroutineScope) {
        this.coroutineScope = coroutineScope
        startRead()
        startSend()
        startTimeoutCheck()
        startPerformanceMonitoring()
    }
    
    /**
     * 设置性能监听器
     */
    fun setPerformanceListener(listener: OnPerformanceListener?) {
        this.performanceListener = listener
    }
    
    /**
     * 启动性能监控任务
     * 定期更新性能指标并通知监听器
     */
    private fun startPerformanceMonitoring() {
        if (performanceUpdateJob?.isActive == true) return
        performanceUpdateJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    delay(1000) // 每秒更新一次性能指标
                    updatePerformanceMetrics()
                }
            } catch (ignore: CancellationException) {
            }
        }
    }
    
    /**
     * 更新性能指标并通知监听器
     */
    private fun updatePerformanceMetrics() {
        val listener = performanceListener ?: return
        
        // 计算平均读取延迟
        val avgLatency = synchronized(readLatencyLock) {
            if (readLatencies.isEmpty()) {
                0.0
            } else {
                readLatencies.average().also {
                    // 保留最近1000次延迟记录
                    if (readLatencies.size > 1000) {
                        readLatencies.removeAt(0)
                    }
                }
            }
        }
        
        val metrics = PerformanceMetrics(
            totalBytesSent = totalBytesSent.get(),
            totalBytesReceived = totalBytesReceived.get(),
            totalRequestsSent = totalRequestsSent.get(),
            totalResponsesReceived = totalResponsesReceived.get(),
            averageReadLatency = avgLatency,
            maxReadLatency = maxReadLatency.get().takeIf { it > 0 } ?: 0,
            minReadLatency = minReadLatency.get().takeIf { it != Long.MAX_VALUE } ?: 0,
            dataLossCount = dataLossCount.get(),
            timeoutRequestCount = timeoutRequestCount.get(),
            currentQueueSize = readyRequests.size,
            currentRunningRequests = runningRequests.size,
            startTime = performanceMetrics.startTime,
            lastUpdateTime = System.currentTimeMillis()
        )
        
        handler.post {
            listener.onPerformanceUpdate(metrics)
        }
    }
    
    /**
     * 启动超时检查任务
     * 使用独立的协程定期检查超时，避免每次数据读取都遍历所有请求
     */
    private fun startTimeoutCheck() {
        if (timeoutCheckJob?.isActive == true) return
        timeoutCheckJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    delay(timeoutCheckInterval)
                    val now = System.currentTimeMillis()
                    // 只在有运行中的请求时检查超时
                    if (runningRequests.isNotEmpty()) {
                        matchTimeoutRequest()
                        removeTimeoutRequest()
                        lastTimeoutCheckTime.set(now)
                    }
                }
            } catch (ignore: CancellationException) {
            }
        }
    }

    fun addRequest(request: Request) {
        if (readyRequests.size > okSerialPort.maxRequestSize) {
            throw RejectedExecutionException("串口队列超出处理容量。处理容量最大为：100")
        }
        request.sendTime = 0
        readyRequests.add(request)
    }

    fun cancelRequest(request: Request): Boolean {
        return readyRequests.remove(request)
    }

    fun addResponseProcess(responseProcess: ResponseProcess) {
        runningRequests.add(responseProcess)
    }

    fun removeResponseProcess(process: ResponseProcess) {
        runningRequests.remove(process)
    }

    private fun startSend() {
        if (sendJob?.isActive == true) return
        sendJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    delay(okSerialPort.sendInterval)
                    if (isBlocking.get()) {
                        continue
                    }
                    val request = readyRequests.pollLast()
                    request?.let {
                        try {
                            if (request.isBlock) {
                                isBlocking.set(true)
                                blockingRequest = request
                            }
                            write(request.data)
                            // 更新性能指标：发送数据
                            totalBytesSent.addAndGet(request.data.size.toLong())
                            totalRequestsSent.incrementAndGet()
                            handler.post { okSerialPort.onDataListener?.onRequest(request.data) }
                            addRunningRequest(request)
                        } catch (e: IOException) {
                            blockRelease(request)
                            handler.post {
                                request.onResponseListener?.onFailure(request, e)
                            }
                        }
                    }
                }
            } catch (ignore: CancellationException) {
            }
        }
    }

    private fun addRunningRequest(request: Request) {
        if (request.responseRules.isNotEmpty() || okSerialPort.responseRules.isNotEmpty()) {
            request.sendTime = System.currentTimeMillis()
            if (!runningRequests.contains(request)) {
                runningRequests.add(request)
            }
        } else {
            blockRelease(request)
            handler.post {
                request.onResponseListener?.onFailure(request, NullPointerException("响应规则为空"))
            }
        }
    }

    private fun startRead() {
        if (okSerialPort.useBlockingRead) {
            // 使用阻塞式读取 + 专用线程（高性能模式）
            startBlockingRead()
        } else {
            // 使用协程轮询（兼容模式）
            startPollingRead()
        }
    }

    /**
     * 阻塞式读取（高性能模式）
     * 使用专用线程 + 阻塞read()，数据到达即读取，延迟接近0ms
     */
    private fun startBlockingRead() {
        if (readThread?.isAlive == true) return
        shouldStopReading.set(false)
        readThread = Thread({
            // 设置线程优先级
            Process.setThreadPriority(okSerialPort.readThreadPriority)
            okSerialPort.logger.log("读取线程启动，优先级：${okSerialPort.readThreadPriority}")
            
            val inputStream = readStream()
            if (inputStream == null) {
                okSerialPort.logger.log("读取流为空，无法启动阻塞读取")
                return@Thread
            }

            // 使用固定缓冲区，避免频繁分配内存
            val buffer = ByteArray(4096)
            
            try {
                while (okSerialPort.isConnect() && !shouldStopReading.get()) {
                    try {
                        // 阻塞式读取，数据到达即返回，无延迟
                        val readStartTime = System.currentTimeMillis()
                        val bytesRead = inputStream.read(buffer)
                        val readEndTime = System.currentTimeMillis()
                        val readLatency = readEndTime - readStartTime
                        
                        if (bytesRead > 0) {
                            // 复制实际读取的数据
                            val receive = ByteArray(bytesRead)
                            System.arraycopy(buffer, 0, receive, 0, bytesRead)
                            
                            // 更新性能指标：接收数据
                            totalBytesReceived.addAndGet(bytesRead.toLong())
                            totalResponsesReceived.incrementAndGet()
                            
                            // 更新读取延迟统计
                            synchronized(readLatencyLock) {
                                readLatencies.add(readLatency)
                                if (readLatency > maxReadLatency.get()) {
                                    maxReadLatency.set(readLatency)
                                }
                                if (readLatency < minReadLatency.get()) {
                                    minReadLatency.set(readLatency)
                                }
                            }
                            
                            // 数据丢失检测：检查接收间隔和大小异常
                            detectDataLoss(bytesRead, readEndTime)
                            
                            // 处理接收到的数据
                            processReceivedData(receive)
                        } else if (bytesRead == -1) {
                            // 流已关闭
                            okSerialPort.logger.log("读取流已关闭")
                            break
                        }
                    } catch (e: IOException) {
                        if (!shouldStopReading.get()) {
                            okSerialPort.logger.log("读取异常：${e.message}")
                            // 区分临时错误和永久错误
                            val isPermanentError = e.message?.let { msg ->
                                msg.contains("Bad file descriptor", ignoreCase = true) ||
                                msg.contains("No such file", ignoreCase = true) ||
                                msg.contains("Permission denied", ignoreCase = true)
                            } ?: false
                            
                            if (isPermanentError) {
                                // 永久错误，标记连接断开
                                okSerialPort.logger.log("检测到串口连接永久错误")
                                handler.post {
                                    // 通知连接断开，外部会触发重连机制
                                    if (okSerialPort.isConnect()) {
                                        okSerialPort.markDisconnected()
                                        okSerialPort.onConnectListener?.onDisconnect(
                                            okSerialPort.devicePath,
                                            IOException("串口读取失败：${e.message}")
                                        )
                                    }
                                }
                                break
                            }
                            // 临时错误，继续尝试
                            try {
                                Thread.sleep(10) // 短暂休眠避免CPU占用过高
                            } catch (ie: InterruptedException) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (!shouldStopReading.get()) {
                            okSerialPort.logger.log("读取处理异常：${e.message}")
                            e.printStackTrace()
                            // 对于未知异常，短暂休眠后继续
                            try {
                                Thread.sleep(10)
                            } catch (ie: InterruptedException) {
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                okSerialPort.logger.log("读取线程异常：${e.message}")
                e.printStackTrace()
            } finally {
                okSerialPort.logger.log("读取线程退出")
            }
        }, "SerialPort-ReadThread-${okSerialPort.devicePath}")
        readThread?.isDaemon = false
        readThread?.start()
    }

    /**
     * 协程轮询读取（兼容模式）
     * 保留原有实现，用于向后兼容
     */
    private fun startPollingRead() {
        if (readJob?.isActive == true) return
        readJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    delay(okSerialPort.readInterval)
                    val readStartTime = System.currentTimeMillis()
                    val receive = try {
                        readStream()?.let {
                            return@let okSerialPort.stickPacketHandle.execute(it)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        okSerialPort.logger.log("粘包处理异常：${e.message}")
                        null
                    }
                    val readEndTime = System.currentTimeMillis()
                    val readLatency = readEndTime - readStartTime
                    
                    if (receive != null && receive.isNotEmpty()) {
                        // 更新性能指标：接收数据
                        totalBytesReceived.addAndGet(receive.size.toLong())
                        totalResponsesReceived.incrementAndGet()
                        
                        // 更新读取延迟统计
                        synchronized(readLatencyLock) {
                            readLatencies.add(readLatency)
                            if (readLatency > maxReadLatency.get()) {
                                maxReadLatency.set(readLatency)
                            }
                            if (readLatency < minReadLatency.get()) {
                                minReadLatency.set(readLatency)
                            }
                        }
                        
                        // 数据丢失检测
                        detectDataLoss(receive.size, readEndTime)
                        
                        processReceivedData(receive)
                    }
                    // 超时检查由独立任务处理
                }
            } catch (ignore: CancellationException) {
            }
        }
    }

    /**
     * 数据丢失检测
     * 检测接收数据的时间间隔和大小异常，可能表示数据丢失
     */
    private fun detectDataLoss(bytesRead: Int, receiveTime: Long) {
        val lastTime = lastReceiveTime.get()
        val lastSize = lastReceiveSize.get()
        
        if (lastTime > 0) {
            val timeInterval = receiveTime - lastTime
            // 如果时间间隔异常大（超过1秒），可能表示数据丢失
            // 或者如果接收数据大小突然变小很多，也可能表示数据丢失
            if (timeInterval > 1000 && lastSize > 0 && bytesRead < lastSize / 2) {
                dataLossCount.incrementAndGet()
                okSerialPort.logger.log("检测到可能的数据丢失：时间间隔=${timeInterval}ms, 上次大小=$lastSize, 本次大小=$bytesRead")
            }
        }
        
        lastReceiveTime.set(receiveTime)
        lastReceiveSize.set(bytesRead.toLong())
    }
    
    /**
     * 处理接收到的数据
     */
    private fun processReceivedData(receive: ByteArray) {
        handler.post { okSerialPort.onDataListener?.onResponse(receive) }
        val matchRequest: ResponseProcess? = matchRequest(receive)
        response(matchRequest, receive)
        // 超时检查由独立任务处理，这里不再每次检查
        // 但如果距离上次检查时间较长，进行一次快速检查
        val now = System.currentTimeMillis()
        if (now - lastTimeoutCheckTime.get() > timeoutCheckInterval * 2) {
            matchTimeoutRequest()
            removeTimeoutRequest()
        }
    }

    /**
     * 匹配请求
     * 优化：优先匹配有自定义规则的请求，然后匹配全局规则
     */
    private fun matchRequest(receive: ByteArray): ResponseProcess? {
        // 先尝试匹配有自定义响应规则的请求（通常更精确）
        val iterator = runningRequests.iterator()
        while (iterator.hasNext()) {
            val process = iterator.next()
            if (isTimeout(process)) continue
            
            val request = if (process is Request) process else null
            
            // 优先匹配有自定义规则的请求
            if (process.isResponseRule()) {
                if (process.match(request, receive)) {
                    timeoutRequests.remove(process)
                    return process
                }
            }
        }
        
        // 如果没有匹配到自定义规则，尝试匹配全局规则
        val iterator2 = runningRequests.iterator()
        while (iterator2.hasNext()) {
            val process = iterator2.next()
            if (isTimeout(process)) continue
            
            val request = if (process is Request) process else null
            
            // 只匹配没有自定义规则的请求（使用全局规则）
            if (!process.isResponseRule() && match(request, receive)) {
                timeoutRequests.remove(process)
                return process
            }
        }
        
        return null
    }

    private fun matchTimeoutRequest() {
        val iterator = runningRequests.iterator()
        while (iterator.hasNext()) {
            val process = iterator.next()
            isTimeout(process)
        }
    }

    private fun response(
        matchProcess: ResponseProcess?, receive: ByteArray
    ) {
        if (matchProcess == null) {
            return
        }
        val request = if (matchProcess is Request) {
            matchProcess
        } else {
            null
        }
        try {
            val response = buildResponse(request, receive)
            removeProcess(matchProcess)
            handler.post { matchProcess.onResponseListener?.onResponse(response) }
        } catch (e: Exception) {
            removeProcess(matchProcess)
            handler.post { matchProcess.onResponseListener?.onFailure(request, e) }
        }
    }

    private fun buildResponse(request: Request?, receive: ByteArray): Response {
        val response = Response(receive)
        response.request = request
        val chain = RealInterceptorChain(okSerialPort.responseInterceptors, 0, response)
        return chain.proceed(response)
    }

    private fun removeProcess(matchProcess: ResponseProcess) {
        blockRelease(matchProcess)
        if (matchProcess.deductCount()) {
            runningRequests.remove(matchProcess)
        }
    }

    private fun blockRelease(process: ResponseProcess?) {
        if (process == null) {
            return
        }
        if (process !is Request) {
            return
        }
        if (blockingRequest != null && process == blockingRequest) {
            isBlocking.set(false)
            blockingRequest = null
        }
    }

    private fun removeTimeoutRequest() {
        runningRequests.removeAll(timeoutRequests.toSet())
        timeoutRequests.forEach {
            val request = if (it is Request) {
                it
            } else {
                null
            }
            // 更新超时计数
            timeoutRequestCount.incrementAndGet()
            blockRelease(request)
            handler.post {
                it.onResponseListener?.onFailure(request, ResponseTimeoutException("响应超时"))
            }
        }
        timeoutRequests.clear()
    }

    private fun match(request: Request?, receive: ByteArray): Boolean {
        try {
            okSerialPort.responseRules.forEach {
                val isMatch = it.match(request, receive)
                if (!isMatch) {
                    return false
                }
            }
        } catch (e: Exception) {
            return false
        }
        return true
    }

    /**
     * 检查请求是否超时
     * 优化：支持更灵活的超时策略
     */
    private fun isTimeout(process: ResponseProcess?): Boolean {
        if (process is Request) {
            val millis = System.currentTimeMillis()
            if (process.timeout > 0 && process.sendTime > 0) {
                val elapsed = millis - process.sendTime
                if (elapsed > process.timeout) {
                    // 超时处理：根据重试次数决定是否重试
                    if (!process.deductTimeoutRetryCount()) {
                        // 还有重试次数，重新加入队列
                        process.sendTime = 0 // 重置发送时间
                        blockRelease(process)
                        readyRequests.addLast(process)
                        okSerialPort.logger.log("请求超时，重新加入队列：剩余重试次数=${process.timeoutRetry}")
                    } else {
                        // 重试次数用完，标记为超时
                        timeoutRequests.add(process)
                        okSerialPort.logger.log("请求超时，重试次数已用完：超时时间=${process.timeout}ms, 实际耗时=${elapsed}ms")
                    }
                    return true
                }
            }
        }
        return false
    }

    /**
     * 取消
     */
    override fun disconnect() {
        // 停止读取线程
        shouldStopReading.set(true)
        readThread?.interrupt()
        try {
            readThread?.join(1000) // 等待最多1秒
            if (readThread?.isAlive == true) {
                okSerialPort.logger.log("读取线程未在1秒内退出，强制继续")
            }
        } catch (e: InterruptedException) {
            okSerialPort.logger.log("等待读取线程退出时被中断")
        } finally {
            readThread = null
        }

        // 取消协程任务
        try {
            readJob?.cancel(cause = CancellationException("Read job canceled"))
            sendJob?.cancel(cause = CancellationException("Send job canceled"))
            timeoutCheckJob?.cancel(cause = CancellationException("Timeout check job canceled"))
            performanceUpdateJob?.cancel(cause = CancellationException("Performance update job canceled"))
            // 等待协程取消完成
            kotlinx.coroutines.runBlocking {
                readJob?.join()
                sendJob?.join()
                timeoutCheckJob?.join()
                performanceUpdateJob?.join()
            }
        } catch (e: Exception) {
            okSerialPort.logger.log("取消协程任务时异常：${e.message}")
        } finally {
            readJob = null
            sendJob = null
            timeoutCheckJob = null
            performanceUpdateJob = null
        }
        
        // 清理请求队列并通知失败
        val pendingRequests = mutableListOf<Request>()
        readyRequests.drainTo(pendingRequests)
        pendingRequests.forEach {
            it.onResponseListener?.onFailure(it, ConnectException("serial port disconnect"))
        }
        
        val runningProcesses = mutableListOf<ResponseProcess>()
        runningRequests.drainTo(runningProcesses)
        runningProcesses.forEach {
            val request = if (it is Request) it else null
            it.onResponseListener?.onFailure(request, ConnectException("serial port disconnect"))
        }
        
        // 清理超时请求
        timeoutRequests.clear()
        
        // 关闭底层串口资源
        super.disconnect()
    }
    
    /**
     * 辅助方法：将队列元素转移到列表
     */
    private fun <T> java.util.Queue<T>.drainTo(list: MutableList<T>) {
        while (true) {
            val item = poll() ?: break
            list.add(item)
        }
    }
}