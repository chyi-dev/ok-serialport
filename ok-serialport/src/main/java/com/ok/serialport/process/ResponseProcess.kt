package com.ok.serialport.process

import com.ok.serialport.model.SerialResponse

/**
 * 数据处理
 * @author Leyi
 * @date 2024/10/28 14:51
 */
abstract class ResponseProcess : ResponseMatcher {
    /**
     * 是否超时重试
     */
    open var isTimeoutRetry: Boolean = false

    /**
     * 超时重试次数
     */
    open var timeoutRetryCount: Long = 2

    /**
     * 超时时间
     */
    open var timeout: Long = 5000

    /**
     * 发送时间
     */
    open var sendTime: Long = 0L

    constructor()

    constructor(timeout: Long) {
        this.timeout = timeout
    }

    constructor(isTimeoutRetry: Boolean, timeoutRetryCount: Long = 0, timeout: Long) {
        this.isTimeoutRetry = isTimeoutRetry
        this.timeoutRetryCount = timeoutRetryCount
        this.timeout = timeout
    }

    abstract fun response(response: SerialResponse)
}