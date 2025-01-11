package com.ok.serialport.data

import com.ok.serialport.listener.OnResponseListener
import com.ok.serialport.utils.ByteUtils

/**
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class Request(internal val data: ByteArray) : ResponseProcess() {

    internal var sendTime: Long = 0

    internal var timeout: Long = 5000

    private var timeoutRetry: Int = 0

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
     * @param timeout
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

    override fun toString(): String {
        return super.toString()
    }
}