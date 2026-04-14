package com.ok.serialport.data

import com.ok.serialport.listener.OnResponseListener
import com.ok.serialport.utils.ByteUtils

/**
 * 请求体
 * @see OnResponseListener 回调必须添加对应的[ResponseRule]
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class Request(var data: ByteArray) : ResponseProcess() {
    var tag: String = ""

    /**
     * 发送时间
     */
    internal var sendTime: Long = 0

    /**
     * 超时时间，默认5s
     */
    internal var timeout: Long = 5000L

    /**
     * 超时重试次数，默认不重试
     */
    internal var timeoutRetry: Int = 0

    /**
     * 是否阻塞
     */
    internal var isBlock: Boolean = false

    /**
     * ACK/NAK配置
     */
    internal var ackNakConfig: AckNakConfig? = null

    /**
     * ACK重试剩余次数（内部使用）
     */
    internal var ackRetryCount: Int = 0

    /**
     * 数据阶段超时时间（内部使用，ACK/NAK流程中有效）
     */
    internal var dataTimeout: Long = -1L

    /**
     * 请求数据
     *
     * @param data
     * @return
     */
    fun data(data: ByteArray): Request {
        this.data = data
        return this
    }

    /**
     * 阻塞
     *
     * @return
     */
    fun blocking(): Request {
        this.isBlock = true
        return this
    }

    /**
     * 请求标记
     *
     * @param tag
     * @return
     */
    fun tag(tag: String): Request {
        this.tag = tag
        return this
    }

    /**
     * 设置响应超时时间
     *
     * @param timeout
     * @return
     */
    fun timeout(timeout: Long): Request {
        this.timeout = timeout
        return this
    }

    /**
     * 设置响应超时重试次数
     *
     * @param count
     * @return
     */
    fun timeoutRetry(count: Int): Request {
        this.timeoutRetry = count
        return this
    }

    /**
     * 设置ACK/NAK配置
     * 启用分阶段响应处理：发送指令 -> 等待ACK/NAK -> 等待数据（可选）
     *
     * @param config ACK/NAK配置
     * @return
     */
    fun ackNakConfig(config: AckNakConfig): Request {
        this.ackNakConfig = config
        // 初始化ACK重试次数
        this.ackRetryCount = config.ackRetryCount
        // 如果配置了数据超时，保存它
        if (config.dataTimeout > 0) {
            this.dataTimeout = config.dataTimeout
        }
        return this
    }

    /**
     * 便捷的ACK/NAK配置方法
     *
     * @param ackRule ACK匹配规则
     * @param nakRule NAK匹配规则
     * @param waitData 是否等待数据响应，默认true
     * @param ackTimeout ACK超时时间，-1表示使用默认超时
     * @param ackRetryCount ACK重试次数
     * @param dataTimeout 数据超时时间，-1表示使用默认超时
     * @return
     */
    fun ackNakConfig(
        ackRule: (ByteArray) -> Boolean,
        nakRule: (ByteArray) -> Boolean,
        waitData: Boolean = true,
        ackTimeout: Long = -1L,
        ackRetryCount: Int = 0,
        dataTimeout: Long = -1L
    ): Request {
        val config = AckNakConfig.Builder()
            .ackRule(ackRule)
            .nakRule(nakRule)
            .waitData(waitData)
            .ackTimeout(ackTimeout)
            .ackRetryCount(ackRetryCount)
            .dataTimeout(dataTimeout)
            .build()
        return ackNakConfig(config)
    }

    /**
     * 添加 MatchRule
     *
     * @param responseRule
     * @return
     */
    override fun addResponseRule(responseRule: ResponseRule): Request {
        super.addResponseRule(responseRule)
        return this
    }

    /**
     * 设置响应监听 OnResponseListener
     *
     * @param listener
     * @return
     */
    override fun onResponseListener(listener: OnResponseListener): Request {
        super.onResponseListener(listener)
        return this
    }

    /**
     * 设置响应次数
     *
     * @param count
     * @return
     */
    override fun responseCount(count: Int): Request {
        super.responseCount(count)
        return this
    }

    /**
     * 无限响应
     *
     * @return
     */
    override fun infiniteResponse(): Request {
        super.infiniteResponse()
        return this
    }

    /**
     * 超时重试次数
     *
     * @return 是否消耗完毕
     */
    internal fun deductTimeoutRetryCount(): Boolean {
        if (timeoutRetry <= 0) {
            timeoutRetry = 0
            return true
        }
        timeoutRetry -= 1
        return false
    }

    /**
     * ACK重试次数扣减
     *
     * @return 是否消耗完毕（true=已耗尽，false=还有剩余）
     */
    internal fun deductAckRetryCount(): Boolean {
        if (ackRetryCount <= 0) {
            ackRetryCount = 0
            return true
        }
        ackRetryCount -= 1
        return false
    }

    /**
     * 获取ACK超时时间
     * @return ACK超时时间（毫秒）
     */
    internal fun getAckTimeout(): Long {
        return ackNakConfig?.ackTimeout?.takeIf { it > 0 } ?: timeout
    }

    /**
     * 获取数据超时时间
     * @return 数据超时时间（毫秒）
     */
    internal fun getDataTimeout(): Long {
        return dataTimeout.takeIf { it > 0 } ?: timeout
    }

    /**
     * 数据转Hex
     * @return String
     */
    fun toHex(): String {
        return ByteUtils.byteArrToHexStr(data)
    }
}