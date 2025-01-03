package com.ok.serialport.listener

interface OnConnectListener {

    fun onConnect(devicePath: String)

    /**
     * 出错以后会立即调用一次，重连失败以后还会调用一次
     */
    fun onDisconnect(devicePath: String, errorMag: String?)
}