package com.ok.serialport.data

import com.ok.serialport.utils.ByteUtils

/**
 * 请求体
 * @see OnResponseListener 回调必须添加对应的[ResponseRule]
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class Request(internal val data: ByteArray) : ResponseProcess() {

    /**
     * 发送时间
     */
    internal var sendTime: Long = 0

    /**
     * 超时时间，默认5s
     */
    internal var timeout: Long = 5000

    /**
     * 超时重试次数，默认不重试
     */
    private var timeoutRetry: Int = 0

    /**
     * 请求数据
     *
     * @return
     */
    fun data(): ByteArray {
        return data
    }

    /**
     * 设置响应超时时间
     *
     * @param timeout
     * @return
     */
    fun setTimeout(timeout: Long): Request {
        this.timeout = timeout
        return this
    }

    /**
     * 设置响应超时重试次数
     *
     * @param count
     * @return
     */
    fun setTimeoutRetry(count: Int): Request {
        this.timeoutRetry = count
        return this
    }

    /**
     * 超时重试次数
     *
     * @return 是否消耗完毕
     */
    internal fun deductTimeoutRetryCount(): Boolean {
        timeoutRetry -= 1
        if (timeoutRetry <= 0) {
            timeoutRetry = 0
            return true
        }
        return false
    }

    /**
     * 数据转Hex
     * @return String
     */
    fun toHex(): String {
        return ByteUtils.byteArrToHexStr(data)
    }
}