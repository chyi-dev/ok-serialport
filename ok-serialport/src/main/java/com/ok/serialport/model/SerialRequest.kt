package com.ok.serialport.model

import com.ok.serialport.process.ResponseProcess

/**
 * 串口请求
 * @author Leyi
 * @date 2024/10/31 11:48
 */
abstract class SerialRequest : ResponseProcess {

    constructor(data: ByteArray) {
        this.data = data
    }

    constructor()

    open lateinit var data: ByteArray

    // 最大重试次数
    open val maxRetries: Int = 0

    // 重试间隔
    open val retryInterval: Long = 100L
}
