package com.ok.serialport.listener

import com.ok.serialport.exception.ReconnectFailException

/**
 * 串口连接监听
 * @author Leyi
 * @date 2025/1/10 15:56
 */
interface OnConnectListener {
    /**
     * 连接成功
     *
     * @param devicePath
     */
    fun onConnect(devicePath: String)

    /**
     * 连接失败
     * 正常连接失败调用一次，重连失败会在调用一次并抛出 [ReconnectFailException]
     * @param devicePath
     * @param errorMag
     */
    fun onDisconnect(devicePath: String, errorMag: Throwable?)
}