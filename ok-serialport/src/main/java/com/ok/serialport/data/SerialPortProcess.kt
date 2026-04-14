package com.ok.serialport.data

import com.ok.serialport.OkSerialPort
import com.ok.serialport.exception.AckTimeoutException
import com.ok.serialport.exception.DataTimeoutException
import com.ok.serialport.exception.ResponseTimeoutException
import com.ok.serialport.interceptor.RealInterceptorChain
import com.ok.serialport.jni.SerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private lateinit var coroutineScope: CoroutineScope
    private var sendJob: Job? = null
    private var readJob: Job? = null
    private var timeoutCheckJob: Job? = null
    private val readyRequests = ConcurrentLinkedDeque<Request>()
    private val runningRequests = ConcurrentLinkedQueue<ResponseProcess>()
    private val timeoutRequests = CopyOnWriteArrayList<ResponseProcess>()
    private val isBlocking = AtomicBoolean(false)
    private val blockingRequest = AtomicReference<Request?>(null)

    companion object {
        // 超时检查间隔（毫秒）
        private const val TIMEOUT_CHECK_INTERVAL = 100L
    }

    fun start(coroutineScope: CoroutineScope) {
        this.coroutineScope = coroutineScope
        startRead()
        startSend()
        startTimeoutCheck()
    }

    fun addRequest(request: Request) {
        if (readyRequests.size > okSerialPort.maxRequestSize) {
            throw RejectedExecutionException("串口队列超出处理容量。处理容量最大为：${okSerialPort.maxRequestSize}")
        }
        request.sendTime = 0
        readyRequests.add(request)

        // 记录发送请求统计
        okSerialPort.performanceStatsCollector?.recordSentRequest(request.data.size)
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
                                blockingRequest.set(request)
                            }
                            write(request.data)
                            withContext(Dispatchers.Main) {
                                okSerialPort.onDataListener?.onRequest(request.data)
                            }
                            addRunningRequest(request)
                        } catch (e: IOException) {
                            // IO异常，可能是串口断开或设备移除
                            blockRelease(request)
                            okSerialPort.logger.log("串口发送IO异常：${e.message}")
                            // 记录失败统计
                            okSerialPort.performanceStatsCollector?.recordFailure()
                            withContext(Dispatchers.Main) {
                                request.onResponseListener?.onFailure(request, e)
                            }
                        } catch (e: Exception) {
                            // 其他异常
                            blockRelease(request)
                            okSerialPort.logger.log("串口发送异常：${e.message}")
                            // 记录失败统计
                            okSerialPort.performanceStatsCollector?.recordFailure()
                            withContext(Dispatchers.Main) {
                                request.onResponseListener?.onFailure(request, e)
                            }
                        }
                    }
                }
            } catch (ignore: CancellationException) {
                // 协程被取消，正常退出
            } finally {
                // 确保阻塞状态被释放
                isBlocking.set(false)
                blockingRequest.set(null)
            }
        }
    }

    private suspend fun addRunningRequest(request: Request) {
        if (request.responseRules.isNotEmpty() || okSerialPort.responseRules.isNotEmpty()) {
            request.sendTime = System.currentTimeMillis()
            // 直接添加，Request 对象应该是唯一的，不需要 O(n) 的 contains 检查
            runningRequests.add(request)
        } else {
            blockRelease(request)
            // 记录失败统计（响应规则为空）
            okSerialPort.performanceStatsCollector?.recordFailure()
            withContext(Dispatchers.Main) {
                request.onResponseListener?.onFailure(request, NullPointerException("响应规则为空"))
            }
        }
    }

    private fun startRead() {
        if (readJob?.isActive == true) return
        readJob = coroutineScope.launch {
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 10 // 最大连续错误次数

            try {
                val inputStream = readStream()
                if (inputStream == null) {
                    okSerialPort.logger.log("串口输入流为空，无法启动读取")
                    return@launch
                }

                while (okSerialPort.isConnect() && isActive) {
                    try {
                        // 使用阻塞读取，在IO线程中执行，确保不阻塞协程调度器
                        val receive = withContext(Dispatchers.IO) {
                            okSerialPort.stickPacketHandle.execute(inputStream)
                        }

                        // 成功读取，重置错误计数
                        consecutiveErrors = 0

                        if (receive != null && receive.isNotEmpty()) {
                            // 记录接收响应统计
                            okSerialPort.performanceStatsCollector?.recordReceivedResponse(receive.size)

                            // 处理接收到的数据
                            withContext(Dispatchers.Main) {
                                okSerialPort.onDataListener?.onResponse(receive)
                            }
                            val matchRequest: ResponseProcess? = matchRequest(receive)
                            response(matchRequest, receive)
                        }
                    } catch (e: EOFException) {
                        // 流结束异常，永久错误
                        okSerialPort.logger.log("串口输入流结束：${e.message}")
                        break
                    } catch (e: IOException) {
                        // IO异常，可能是串口断开或设备移除
                        consecutiveErrors++
                        okSerialPort.logger.log("串口读取IO异常（${consecutiveErrors}/${maxConsecutiveErrors}）：${e.message}")

                        // 检查是否是永久错误（连续错误过多或连接已断开）
                        if (!okSerialPort.isConnect() || consecutiveErrors >= maxConsecutiveErrors) {
                            okSerialPort.logger.log("串口读取失败，停止读取线程")
                            break
                        }

                        // 临时错误，短暂延迟后重试，避免快速重试导致CPU占用过高
                        delay(100)
                    } catch (e: CancellationException) {
                        // 协程被取消，正常退出
                        throw e
                    } catch (e: Exception) {
                        // 其他异常（如粘包处理异常），可能是临时错误
                        consecutiveErrors++
                        okSerialPort.logger.log("串口读取异常（${consecutiveErrors}/${maxConsecutiveErrors}）：${e.message}")
                        e.printStackTrace()

                        // 检查是否是永久错误
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            okSerialPort.logger.log("串口读取连续异常过多，停止读取线程")
                            break
                        }

                        // 短暂延迟后继续，避免异常循环
                        delay(50)
                    }
                }
            } catch (ignore: CancellationException) {
                // 协程被取消，正常退出
                okSerialPort.logger.log("读取线程被取消")
            } catch (e: Exception) {
                okSerialPort.logger.log("读取线程启动异常：${e.message}")
                e.printStackTrace()
            } finally {
                // 确保资源清理
                okSerialPort.logger.log("读取线程退出")
            }
        }
    }

    private fun matchRequest(receive: ByteArray): ResponseProcess? {
        if (runningRequests.isEmpty()) {
            return null
        }

        val iterator = runningRequests.iterator()
        var matchProcess: ResponseProcess? = null
        while (iterator.hasNext()) {
            val process = iterator.next()
            if (process == null || isTimeout(process)) continue

            val request = if (process is Request) {
                process
            } else {
                null
            }

            try {
                // 检查是否是配置了ACK/NAK的请求
                if (request?.ackNakConfig != null) {
                    // 根据当前阶段选择匹配逻辑
                    when (process.currentPhase) {
                        RequestPhase.WAITING_ACK -> {
                            // ACK阶段：匹配ACK或NAK
                            val config = request.ackNakConfig!!
                            if (config.isAck(receive)) {
                                // 匹配到ACK
                                okSerialPort.logger.log("请求[${request.tag}]匹配到ACK响应")
                                matchProcess = process
                                timeoutRequests.remove(process)
                                break
                            } else if (config.hasNakRule() && config.isNak(receive)) {
                                // 匹配到NAK
                                okSerialPort.logger.log("请求[${request.tag}]匹配到NAK响应")
                                matchProcess = process
                                timeoutRequests.remove(process)
                                break
                            }
                            // 既不是ACK也不是NAK，继续等待
                            continue
                        }
                        RequestPhase.WAITING_DATA -> {
                            // 数据阶段：使用原有响应规则匹配
                            if (process.isResponseRule()) {
                                if (process.match(request, receive)) {
                                    matchProcess = process
                                    timeoutRequests.remove(process)
                                    break
                                }
                            } else if (match(request, receive)) {
                                matchProcess = process
                                timeoutRequests.remove(process)
                                break
                            }
                        }
                        RequestPhase.COMPLETED -> {
                            // 已完成，不应该还在runningRequests中
                            continue
                        }
                    }
                } else {
                    // 普通请求（没有ACK/NAK配置），使用原有逻辑
                    if (process.isResponseRule()) {
                        if (process.match(request, receive)) {
                            matchProcess = process
                            timeoutRequests.remove(process)
                            break
                        }
                    } else if (match(request, receive)) {
                        matchProcess = process
                        timeoutRequests.remove(process)
                        break
                    }
                }
            } catch (e: Exception) {
                // 匹配过程中出现异常，记录日志但继续匹配下一个
                okSerialPort.logger.log("请求匹配异常：${e.message}")
                continue
            }
        }
        return matchProcess
    }

    /**
     * 启动超时检查协程，定时检查请求是否超时
     */
    private fun startTimeoutCheck() {
        if (timeoutCheckJob?.isActive == true) return
        timeoutCheckJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    delay(TIMEOUT_CHECK_INTERVAL)
                    matchTimeoutRequest()
                    removeTimeoutRequest()

                    // 异步更新队列状态统计，不影响主流程
                    okSerialPort.performanceStatsCollector?.let { collector ->
                        collector.updateQueueSize(readyRequests.size)
                        collector.updateRunningRequests(runningRequests.size)
                    }
                }
            } catch (ignore: CancellationException) {
                // 协程被取消，正常退出
            }
        }
    }

    private fun matchTimeoutRequest() {
        val iterator = runningRequests.iterator()
        while (iterator.hasNext()) {
            val process = iterator.next()
            isTimeout(process)
        }
    }

    private suspend fun response(
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

        // 检查是否是配置了ACK/NAK的请求且处于ACK阶段
        if (request?.ackNakConfig != null && matchProcess.currentPhase == RequestPhase.WAITING_ACK) {
            handleAckNakResponse(matchProcess, request, receive)
            return
        }

        // 普通响应处理或ACK/NAK模式的数据阶段处理
        try {
            val response = buildResponse(request, receive)

            // 判断是否是ACK/NAK模式的数据阶段
            val isDataPhase = request?.ackNakConfig != null && matchProcess.currentPhase == RequestPhase.WAITING_DATA

            if (isDataPhase) {
                // 数据阶段：使用数据阶段计数
                if (matchProcess.deductDataCount()) {
                    // 数据响应次数已耗尽，移除请求
                    removeProcess(matchProcess)
                    matchProcess.markCompleted()
                }
                // 否则保持请求在runningRequests中，继续等待更多数据响应
            } else {
                // 普通请求，使用原有计数逻辑
                removeProcess(matchProcess)
            }

            // 计算响应时间并记录成功统计
            if (request != null && request.sendTime > 0) {
                val responseTime = System.currentTimeMillis() - request.sendTime
                okSerialPort.performanceStatsCollector?.recordSuccess(responseTime)
            } else {
                okSerialPort.performanceStatsCollector?.recordSuccessWithoutTime()
            }

            withContext(Dispatchers.Main) {
                if (isDataPhase) {
                    // ACK/NAK模式下，调用onDataReceived（内部会fallback到onResponse）
                    matchProcess.onResponseListener?.onDataReceived(response)
                } else {
                    matchProcess.onResponseListener?.onResponse(response)
                }
            }
        } catch (e: Exception) {
            removeProcess(matchProcess)
            okSerialPort.performanceStatsCollector?.recordFailure()
            withContext(Dispatchers.Main) {
                matchProcess.onResponseListener?.onFailure(request, e)
            }
        }
    }

    /**
     * 处理ACK/NAK响应
     *
     * @param matchProcess 匹配的响应处理对象
     * @param request 请求对象
     * @param receive 接收到的数据
     */
    private suspend fun handleAckNakResponse(
        matchProcess: ResponseProcess,
        request: Request,
        receive: ByteArray
    ) {
        val config = request.ackNakConfig!!

        when {
            config.isAck(receive) -> {
                // 收到ACK确认
                okSerialPort.logger.log("请求[${request.tag}]收到ACK确认")

                withContext(Dispatchers.Main) {
                    matchProcess.onResponseListener?.onAckReceived(request)
                }

                if (config.waitData) {
                    // 需要等待数据，进入数据阶段
                    okSerialPort.logger.log("请求[${request.tag}]进入数据等待阶段")
                    matchProcess.enterDataPhase(
                        dataCount = matchProcess.count,
                        dataRetry = request.timeoutRetry
                    )
                    // 不调用removeProcess，保持请求在runningRequests中继续等待数据
                } else {
                    // 不需要等待数据，直接完成
                    okSerialPort.logger.log("请求[${request.tag}]ACK确认完成，不等待数据")
                    removeProcess(matchProcess)
                    matchProcess.markCompleted()

                    // 计算响应时间
                    if (request.sendTime > 0) {
                        val responseTime = System.currentTimeMillis() - request.sendTime
                        okSerialPort.performanceStatsCollector?.recordSuccess(responseTime)
                    } else {
                        okSerialPort.performanceStatsCollector?.recordSuccessWithoutTime()
                    }
                }
            }
            config.hasNakRule() && config.isNak(receive) -> {
                // 收到NAK响应，需要重试
                okSerialPort.logger.log("请求[${request.tag}]收到NAK响应")

                withContext(Dispatchers.Main) {
                    matchProcess.onResponseListener?.onNakReceived(request)
                }

                if (!request.deductAckRetryCount()) {
                    // 还有ACK重试次数，进行重试
                    okSerialPort.logger.log("请求[${request.tag}]进行ACK重试，剩余重试次数：${request.ackRetryCount}")
                    runningRequests.remove(matchProcess)
                    matchProcess.resetToAckPhase()
                    request.sendTime = 0
                    blockRelease(request)
                    readyRequests.addLast(request)
                } else {
                    // ACK重试次数已耗尽，标记为失败
                    okSerialPort.logger.log("请求[${request.tag}]ACK重试次数耗尽，标记为失败")
                    removeProcess(matchProcess)
                    okSerialPort.performanceStatsCollector?.recordFailure()
                    withContext(Dispatchers.Main) {
                        matchProcess.onResponseListener?.onFailure(request, AckTimeoutException("收到NAK响应且ACK重试次数已耗尽"))
                    }
                }
            }
            else -> {
                // 不应该走到这里，因为matchRequest已经过滤过了
                okSerialPort.logger.log("请求[${request.tag}]ACK/NAK匹配异常，数据不匹配ACK也不匹配NAK")
            }
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
        val currentBlocking = blockingRequest.get()
        if (currentBlocking != null && process == currentBlocking) {
            isBlocking.set(false)
            blockingRequest.set(null)
        }
    }

    private suspend fun removeTimeoutRequest() {
        if (timeoutRequests.isEmpty()) return

        val timeoutList = timeoutRequests.toList() // 创建快照避免并发修改
        runningRequests.removeAll(timeoutList.toSet())

        timeoutList.forEach { process ->
            // 记录超时统计
            okSerialPort.performanceStatsCollector?.recordTimeout()

            val request = if (process is Request) {
                process
            } else {
                null
            }
            blockRelease(request)

            try {
                withContext(Dispatchers.Main) {
                    // 判断超时类型并调用相应回调
                    if (request != null && request.ackNakConfig != null) {
                        // ACK/NAK模式下的超时
                        when (process.currentPhase) {
                            RequestPhase.WAITING_ACK -> {
                                // ACK阶段超时
                                process.onResponseListener?.onAckTimeout(request)
                                process.onResponseListener?.onFailure(request, AckTimeoutException("ACK响应超时，重试次数已耗尽"))
                            }
                            RequestPhase.WAITING_DATA -> {
                                // 数据阶段超时
                                process.onResponseListener?.onDataTimeout(request)
                                process.onResponseListener?.onFailure(request, DataTimeoutException("数据响应超时，重试次数已耗尽"))
                            }
                            else -> {
                                // 其他情况，使用通用超时回调
                                process.onResponseListener?.onFailure(request, ResponseTimeoutException("响应超时"))
                            }
                        }
                    } else {
                        // 普通请求超时
                        process.onResponseListener?.onFailure(request, ResponseTimeoutException("响应超时"))
                    }
                }
            } catch (e: Exception) {
                okSerialPort.logger.log("超时回调异常：${e.message}")
            }
        }
        timeoutRequests.removeAll(timeoutList)
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

    private fun isTimeout(process: ResponseProcess?): Boolean {
        if (process !is Request) return false

        val millis = System.currentTimeMillis()
        val config = process.ackNakConfig

        // 检查是否是配置了ACK/NAK的请求
        if (config != null) {
            // ACK/NAK模式下的超时处理
            return when (process.currentPhase) {
                RequestPhase.WAITING_ACK -> {
                    // ACK阶段超时检查
                    val ackTimeout = process.getAckTimeout()
                    if (ackTimeout > 0 && process.sendTime > 0 && millis - process.sendTime > ackTimeout) {
                        // ACK超时
                        okSerialPort.logger.log("请求[${process.tag}]ACK阶段超时")

                        if (!process.deductAckRetryCount()) {
                            // 还有ACK重试次数，进行重试（重新发送指令）
                            okSerialPort.logger.log("请求[${process.tag}]进行ACK超时重试，剩余重试次数：${process.ackRetryCount}")
                            runningRequests.remove(process)
                            process.resetToAckPhase()
                            process.sendTime = 0
                            blockRelease(process)
                            readyRequests.addLast(process)
                        } else {
                            // ACK重试次数已耗尽
                            okSerialPort.logger.log("请求[${process.tag}]ACK重试次数耗尽，标记为超时失败")
                            timeoutRequests.add(process)
                        }
                        true
                    } else {
                        false
                    }
                }
                RequestPhase.WAITING_DATA -> {
                    // 数据阶段超时检查
                    val dataTimeout = process.getDataTimeout()
                    if (dataTimeout > 0 && process.isDataTimeout(dataTimeout)) {
                        // 数据阶段超时
                        okSerialPort.logger.log("请求[${process.tag}]数据阶段超时")

                        if (!process.deductDataRetryCount()) {
                            // 还有数据重试次数，继续等待数据（不重新发送指令）
                            okSerialPort.logger.log("请求[${process.tag}]进行数据阶段重试，继续等待数据，剩余重试次数：${process.dataRetryCount}")
                            // 重置数据阶段开始时间
                            process.ackReceiveTime = System.currentTimeMillis()
                        } else {
                            // 数据重试次数已耗尽
                            okSerialPort.logger.log("请求[${process.tag}]数据阶段重试次数耗尽，标记为超时失败")
                            timeoutRequests.add(process)
                        }
                        true
                    } else {
                        false
                    }
                }
                RequestPhase.COMPLETED -> {
                    // 已完成，不应该还在runningRequests中
                    false
                }
            }
        } else {
            // 普通请求的超时处理（原有逻辑）
            if (process.timeout > 0 && process.sendTime > 0 && millis - process.sendTime > process.timeout) {
                if (!process.deductTimeoutRetryCount()) {
                    // 还有重试次数，进行重试
                    runningRequests.remove(process)
                    process.sendTime = 0
                    blockRelease(process)
                    readyRequests.addLast(process)
                } else {
                    // 重试次数用完，标记为最终失败
                    timeoutRequests.add(process)
                }
                return true
            }
        }
        return false
    }

    /**
     * 取消
     */
    override fun disconnect() {
        // 取消读取、发送和超时检查协程
        readJob?.cancel(cause = CancellationException("Read job canceled"))
        sendJob?.cancel(cause = CancellationException("Send job canceled"))
        timeoutCheckJob?.cancel(cause = CancellationException("Timeout check job canceled"))

        // 释放阻塞状态
        isBlocking.set(false)
        blockingRequest.set(null)

        // 关闭底层串口连接
        super.disconnect()

        // 清理待发送请求，使用 try-catch 保护每个回调
        readyRequests.forEach {
            try {
                it.onResponseListener?.onFailure(it, ConnectException("serial port disconnect"))
            } catch (e: Exception) {
                okSerialPort.logger.log("清理待发送请求回调异常：${e.message}")
            }
        }

        // 清理运行中的请求，使用 try-catch 保护每个回调
        runningRequests.forEach {
            try {
                if (it is Request) {
                    it.onResponseListener?.onFailure(it, ConnectException("serial port disconnect"))
                } else {
                    it.onResponseListener?.onFailure(
                        null,
                        ConnectException("serial port disconnect")
                    )
                }
            } catch (e: Exception) {
                okSerialPort.logger.log("清理运行中请求回调异常：${e.message}")
            }
        }

        // 清理超时请求，使用 try-catch 保护每个回调
        timeoutRequests.forEach {
            try {
                val request = if (it is Request) it else null
                it.onResponseListener?.onFailure(
                    request,
                    ConnectException("serial port disconnect")
                )
            } catch (e: Exception) {
                okSerialPort.logger.log("清理超时请求回调异常：${e.message}")
            }
        }

        // 清理队列
        readyRequests.clear()
        runningRequests.clear()
        timeoutRequests.clear()
    }
}