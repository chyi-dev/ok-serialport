package com.ok.serialport.demo

import com.ok.serialport.OkSerialClient
import com.ok.serialport.listener.OnConnectListener

/**
 *
 * @author Leyi
 * @date 2025/1/9 15:04
 */
class SerialPortManager private constructor() : OnConnectListener {

    private val serialPortClients = arrayOfNulls<OkSerialClient>(3)
    private val serialPortDevices = arrayOfNulls<String>(3)

    companion object {
        val instance: SerialPortManager by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            SerialPortManager()
        }
    }



    override fun onConnect(devicePath: String) {
        TODO("Not yet implemented")
    }

    override fun onDisconnect(devicePath: String, errorMag: String?) {
        TODO("Not yet implemented")
    }

}