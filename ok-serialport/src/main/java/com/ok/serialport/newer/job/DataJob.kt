package com.ok.serialport.newer.job

import com.ok.serialport.newer.OkSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 *
 * @author Leyi
 * @date 2025/1/10 16:17
 */
class DataJob(private val okSerialPort: OkSerialPort) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var sendJob: Job? = null
    private var readJob: Job? = null

    fun start(
        sendAction: (() -> Unit),
        readAction: (() -> Unit)
    ) {
        startRead(sendAction)
        startSend(readAction)
    }

    private fun startSend(sendAction: (() -> Unit)) {
        if (sendJob?.isActive == true) return
        sendJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    delay(okSerialPort.sendInterval)
                    sendAction.invoke()
                }
            } catch (ignore: CancellationException) {
            }
        }
    }

    private fun startRead(readAction: (() -> Unit)) {
        if (readJob?.isActive == true) return
        readJob = coroutineScope.launch {
            try {
                while (okSerialPort.isConnect() && isActive) {
                    readAction.invoke()
                }
            } catch (ignore: CancellationException) {
            }
        }
    }

    fun cancel() {
        readJob?.cancel(cause = CancellationException("Read job canceled"))
        sendJob?.cancel(cause = CancellationException("Send job canceled"))
    }
}