package com.ok.serialport.listener

/**
 * 数据全局监听
 */
interface OnDataListener {

    /**
     * 数据请求监听
     * @param data ByteArray
     */
    fun onRequest(data: ByteArray)

    /**
     * 数据响应监听
     * @param data ByteArray
     */
    fun onResponse(data: ByteArray)
}