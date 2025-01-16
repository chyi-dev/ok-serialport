package com.ok.serialport.data

import android.os.Handler
import android.os.Looper
import com.ok.serialport.OkSerialPort
import com.ok.serialport.exception.ResponseTimeoutException
import com.ok.serialport.interceptor.RealInterceptorChain
import com.ok.serialport.jni.SerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
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
    private val handler = Handler(Looper.getMainLooper())
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
        if (readJob?.isActive == true) return
        readJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    delay(okSerialPort.readInterval)
                    val receive = try {
                        readStream()?.let {
                            return@let okSerialPort.stickPacketHandle.execute(it)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        okSerialPort.logger.log("粘包处理异常：${e.message}")
                        null
                    }
                    if (receive != null && receive.isNotEmpty()) {
                        handler.post { okSerialPort.onDataListener?.onResponse(receive) }
                        val matchRequest: ResponseProcess? = matchRequest(receive)
                        response(matchRequest, receive)
                    } else {
                        matchTimeoutRequest()
                    }
                    removeTimeoutRequest()
                }
            } catch (ignore: CancellationException) {
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

    private fun isTimeout(process: ResponseProcess?): Boolean {
        if (process is Request) {
            val millis = System.currentTimeMillis()
            if (process.timeout > 0 && process.sendTime > 0 && millis - process.sendTime > process.timeout) {
                if (!process.deductTimeoutRetryCount()) {
                    process.sendTime = 0
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
        readJob?.cancel(cause = CancellationException("Read job canceled"))
        sendJob?.cancel(cause = CancellationException("Send job canceled"))
        super.disconnect()
    }
}