package com.ok.serialport.data

import com.ok.serialport.OkSerialPort
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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
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
    private val readyRequests = ConcurrentLinkedDeque<Request>()
    private val runningRequests = ConcurrentLinkedQueue<ResponseProcess>()
    private val timeoutRequests = mutableListOf<ResponseProcess>()
    private val isBlocking = AtomicBoolean(false)
    private var blockingRequest: Request? = null

    fun start(coroutineScope: CoroutineScope) {
        this.coroutineScope = coroutineScope
        startRead()
        startSend()
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
                            withContext(Dispatchers.Main) {
                                okSerialPort.onDataListener?.onRequest(request.data)
                            }
                            addRunningRequest(request)
                        } catch (e: IOException) {
                            // IO异常，可能是串口断开或设备移除
                            blockRelease(request)
                            okSerialPort.logger.log("串口发送IO异常：${e.message}")
                            withContext(Dispatchers.Main) {
                                request.onResponseListener?.onFailure(request, e)
                            }
                        } catch (e: Exception) {
                            // 其他异常
                            blockRelease(request)
                            okSerialPort.logger.log("串口发送异常：${e.message}")
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
                blockingRequest = null
            }
        }
    }

    private suspend fun addRunningRequest(request: Request) {
        if (request.responseRules.isNotEmpty() || okSerialPort.responseRules.isNotEmpty()) {
            request.sendTime = System.currentTimeMillis()
            if (!runningRequests.contains(request)) {
                runningRequests.add(request)
            }
        } else {
            blockRelease(request)
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
                            // 处理接收到的数据
                            withContext(Dispatchers.Main) {
                                okSerialPort.onDataListener?.onResponse(receive)
                            }
                            val matchRequest: ResponseProcess? = matchRequest(receive)
                            response(matchRequest, receive)
                        } else if (receive == null) {
                            // 流关闭（read返回-1），这是永久错误
                            okSerialPort.logger.log("串口输入流已关闭")
                            break
                        }
                        
                        // 检查超时请求（即使没有新数据也要检查）
                        matchTimeoutRequest()
                        removeTimeoutRequest()
                        
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
        val iterator = runningRequests.iterator()
        var matchProcess: ResponseProcess? = null
        while (iterator.hasNext()) {
            val process = iterator.next()
            if (isTimeout(process)) continue
            val request = if (process is Request) {
                process
            } else {
                null
            }
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
        return matchProcess
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
        try {
            val response = buildResponse(request, receive)
            removeProcess(matchProcess)
            withContext(Dispatchers.Main) {
                matchProcess.onResponseListener?.onResponse(response)
            }
        } catch (e: Exception) {
            removeProcess(matchProcess)
            withContext(Dispatchers.Main) {
                matchProcess.onResponseListener?.onFailure(request, e)
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
        if (blockingRequest != null && process == blockingRequest) {
            isBlocking.set(false)
            blockingRequest = null
        }
    }

    private suspend fun removeTimeoutRequest() {
        runningRequests.removeAll(timeoutRequests.toSet())
        timeoutRequests.forEach {
            val request = if (it is Request) {
                it
            } else {
                null
            }
            blockRelease(request)
            withContext(Dispatchers.Main) {
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

    private fun isTimeout(process: ResponseProcess?): Boolean {
        if (process is Request) {
            val millis = System.currentTimeMillis()
            if (process.timeout > 0 && process.sendTime > 0 && millis - process.sendTime > process.timeout) {
                if (!process.deductTimeoutRetryCount()) {
                    process.sendTime = 0
                    blockRelease(process)
                    readyRequests.addLast(process)
                } else {
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
        // 取消读取和发送协程
        readJob?.cancel(cause = CancellationException("Read job canceled"))
        sendJob?.cancel(cause = CancellationException("Send job canceled"))
        
        // 释放阻塞状态
        isBlocking.set(false)
        blockingRequest = null
        
        // 关闭底层串口连接
        super.disconnect()
        readyRequests.forEach {
            it.onResponseListener?.onFailure(it, ConnectException("serial port disconnect"))
        }
        runningRequests.forEach {
            if (it is Request) {
                it.onResponseListener?.onFailure(it, ConnectException("serial port disconnect"))
            } else {
                it.onResponseListener?.onFailure(null, ConnectException("serial port disconnect"))
            }
        }
        
        // 清理队列
        readyRequests.clear()
        runningRequests.clear()
        timeoutRequests.clear()
    }
}