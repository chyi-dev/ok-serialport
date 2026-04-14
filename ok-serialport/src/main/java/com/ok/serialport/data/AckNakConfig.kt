package com.ok.serialport.data

/**
 * ACK/NAK 配置类
 * 用于配置分阶段响应中的ACK/NAK确认机制
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class AckNakConfig private constructor(
    /**
     * ACK匹配规则
     */
    val ackRule: ((ByteArray) -> Boolean)?,

    /**
     * NAK匹配规则
     */
    val nakRule: ((ByteArray) -> Boolean)?,

    /**
     * 是否等待具体数据响应
     * true: 收到ACK后继续等待数据响应
     * false: 收到ACK后直接回调成功，不等待数据
     */
    val waitData: Boolean,

    /**
     * ACK/NAK超时时间（毫秒）
     * 默认使用Request的timeout
     */
    val ackTimeout: Long,

    /**
     * ACK失败（NAK或ACK超时）重试次数
     */
    val ackRetryCount: Int,

    /**
     * 数据响应超时时间（毫秒）
     * 仅在waitData=true时有效
     * 默认使用Request的timeout
     */
    val dataTimeout: Long
) {

    /**
     * 检查是否配置了ACK规则
     */
    fun hasAckRule(): Boolean = ackRule != null

    /**
     * 检查是否配置了NAK规则
     */
    fun hasNakRule(): Boolean = nakRule != null

    /**
     * 匹配ACK
     * @param data 接收到的数据
     * @return 是否匹配ACK
     */
    fun isAck(data: ByteArray): Boolean {
        return ackRule?.invoke(data) ?: false
    }

    /**
     * 匹配NAK
     * @param data 接收到的数据
     * @return 是否匹配NAK
     */
    fun isNak(data: ByteArray): Boolean {
        return nakRule?.invoke(data) ?: false
    }

    /**
     * Builder类用于构建AckNakConfig
     */
    class Builder {
        private var ackRule: ((ByteArray) -> Boolean)? = null
        private var nakRule: ((ByteArray) -> Boolean)? = null
        private var waitData: Boolean = true
        private var ackTimeout: Long = -1L
        private var ackRetryCount: Int = 0
        private var dataTimeout: Long = -1L

        /**
         * 设置ACK匹配规则
         * @param rule 匹配函数
         */
        fun ackRule(rule: (ByteArray) -> Boolean) = apply {
            this.ackRule = rule
        }

        /**
         * 设置NAK匹配规则
         * @param rule 匹配函数
         */
        fun nakRule(rule: (ByteArray) -> Boolean) = apply {
            this.nakRule = rule
        }

        /**
         * 设置是否等待具体数据响应
         * @param wait true=等待数据，false=收到ACK即完成
         */
        fun waitData(wait: Boolean) = apply {
            this.waitData = wait
        }

        /**
         * 设置ACK/NAK超时时间
         * @param timeout 超时时间（毫秒），-1表示使用Request的默认超时
         */
        fun ackTimeout(timeout: Long) = apply {
            this.ackTimeout = timeout
        }

        /**
         * 设置ACK失败重试次数
         * @param count 重试次数
         */
        fun ackRetryCount(count: Int) = apply {
            this.ackRetryCount = count
        }

        /**
         * 设置数据响应超时时间
         * @param timeout 超时时间（毫秒），-1表示使用Request的默认超时
         */
        fun dataTimeout(timeout: Long) = apply {
            this.dataTimeout = timeout
        }

        fun build(): AckNakConfig {
            return AckNakConfig(
                ackRule = ackRule,
                nakRule = nakRule,
                waitData = waitData,
                ackTimeout = ackTimeout,
                ackRetryCount = ackRetryCount,
                dataTimeout = dataTimeout
            )
        }
    }

    companion object {
        /**
         * 便捷的DSL风格创建方法
         */
        inline fun build(block: Builder.() -> Unit): AckNakConfig {
            return Builder().apply(block).build()
        }
    }
}
