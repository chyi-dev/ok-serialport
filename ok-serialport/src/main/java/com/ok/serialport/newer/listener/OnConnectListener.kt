package com.ok.serialport.newer.listener

/**
 * 串口连接失败
 * @author Leyi
 * @date 2025/1/10 15:56
 */
interface OnConnectListener {
    fun onConnect(devicePath: String)

    /**
     * 出错以后会立即调用一次，重连失败以后还会调用一次
     */
    fun onDisconnect(devicePath: String, errorMag: Throwable?)
}