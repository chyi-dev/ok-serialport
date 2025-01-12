package com.ok.serialport.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ok.serialport.OkSerialPort
import com.ok.serialport.exception.ResponseTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException

/**
 * 串口数据处理
 * @author Leyi
 * @date 2025/1/10 16:17
 */
class DataProcess(private val okSerialPort: OkSerialPort) {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var coroutineScope: CoroutineScope
    private var sendJob: Job? = null
    private var readJob: Job? = null
    private val readyRequests = ConcurrentLinkedQueue<Request>()
    private val runningRequests = ConcurrentLinkedQueue<ResponseProcess>()
    private val timeoutRequests = mutableListOf<ResponseProcess>()

    fun start(coroutineScope: CoroutineScope) {
        this.coroutineScope = coroutineScope
        startRead()
        startSend()
    }

    fun addRequest(request: Request) {
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
                    val request = readyRequests.poll()
                    try {
                        okSerialPort.serialPortClient.write(request.data)
                        handler.post { okSerialPort.onDataListener?.onRequest(request.data) }
                        addRunningRequest(request)
                    } catch (e: IOException) {
                        request.onResponseListener?.onFailure(request, e)
                    }
                }
            } catch (ignore: CancellationException) {
            }
        }
    }

    private fun addRunningRequest(request: Request) {
        if (request.responseRules.isNotEmpty()) {
            request.sendTime = SystemClock.currentThreadTimeMillis()
            runningRequests.add(request)
        } else {
            request.onResponseListener?.onFailure(request, NullPointerException("响应规则为空"))
        }
    }

    private fun startRead() {
        if (readJob?.isActive == true) return
        readJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    delay(okSerialPort.readInterval)
                    val receive = try {
                        okSerialPort.serialPortClient.readStream()?.let {
                            return@let okSerialPort.stickPacketHandle.execute(it)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        okSerialPort.logger.log("粘包处理异常：${e.message}")
                        null
                    }
                    if (receive != null && receive.isNotEmpty()) {
                        handler.post { okSerialPort.onDataListener?.onResponse(receive) }
                        var matchRequest: ResponseProcess? = matchRequest(receive)
                        response(matchRequest, receive)
                        timeout()
                    }
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
            if (process is Request) {
                val millis = SystemClock.currentThreadTimeMillis()
                if (process.timeout > 0 && millis - process.sendTime > process.timeout) {
                    if (process.deductTimeoutRetryCount()) {
                        addRequest(process)
                    } else {
                        timeoutRequests.add(process)
                    }
                    continue
                }
            }
            try {
                if (process.match(receive)) {
                    matchProcess = process
                    timeoutRequests.remove(process)
                    break
                }
            } catch (e: Exception) {
                okSerialPort.logger.log("匹配条件存在异常：${e.message}")
                continue
            }
        }
        return matchProcess
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
            val response = Response(receive)
            response.request = request
            matchProcess.onResponseListener?.onResponse(response)
            removeProcess(matchProcess)
        } catch (e: Exception) {
            matchProcess.onResponseListener?.onFailure(request, e)
            removeProcess(matchProcess)
        }
    }

    private fun removeProcess(matchProcess: ResponseProcess) {
        if (matchProcess.deductCount()) {
            runningRequests.remove(matchProcess)
        }
    }

    private fun timeout() {
        runningRequests.removeAll(timeoutRequests.toSet())
        timeoutRequests.forEach {
            val request = if (it is Request) {
                it
            } else {
                null
            }
            it.onResponseListener?.onFailure(
                request, ResponseTimeoutException("响应超时")
            )
        }
        timeoutRequests.clear()
    }

    /**
     * 取消
     */
    fun cancel() {
        readJob?.cancel(cause = CancellationException("Read job canceled"))
        sendJob?.cancel(cause = CancellationException("Send job canceled"))
    }
}