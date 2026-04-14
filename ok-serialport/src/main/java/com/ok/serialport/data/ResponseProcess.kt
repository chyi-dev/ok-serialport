package com.ok.serialport.data

import com.ok.serialport.listener.OnResponseListener

/**
 * 请求处理阶段枚举
 */
enum class RequestPhase {
    /**
     * 等待ACK/NAK响应阶段
     */
    WAITING_ACK,

    /**
     * 等待数据响应阶段
     */
    WAITING_DATA,

    /**
     * 已完成
     */
    COMPLETED
}

/**
 * 响应处理
 * @author Leyi
 * @date 2025/1/10 16:35
 */
open class ResponseProcess {
    companion object {
        /**
         * 最大响应次数：无限次响应
         */
        const val MAX_COUNT = 10000
    }

    internal var responseRules = mutableListOf<ResponseRule>()

    internal var onResponseListener: OnResponseListener? = null

    internal var count: Int = 1

    /**
     * 当前请求处理阶段
     */
    internal var currentPhase: RequestPhase = RequestPhase.WAITING_ACK

    /**
     * 是否已收到ACK确认
     */
    internal var ackReceived: Boolean = false

    /**
     * 是否在等待数据阶段
     */
    internal var waitingForData: Boolean = false

    /**
     * ACK收到时间（用于数据阶段超时计算）
     */
    internal var ackReceiveTime: Long = 0

    /**
     * 数据阶段剩余响应次数（独立于count）
     */
    internal var dataResponseCount: Int = 1

    /**
     * 数据阶段剩余重试次数
     */
    internal var dataRetryCount: Int = 0

    /**
     * 添加 MatchRule
     *
     * @param responseRule
     * @return
     */
    open fun addResponseRule(responseRule: ResponseRule): ResponseProcess {
        responseRules.add(responseRule)
        return this
    }

    /**
     * 是否存在响应规则
     *
     * @return
     */
    internal fun isResponseRule(): Boolean {
        return responseRules.isNotEmpty()
    }

    /**
     * 设置响应监听 OnResponseListener
     *
     * @param listener
     * @return
     */
    open fun onResponseListener(listener: OnResponseListener): ResponseProcess {
        this.onResponseListener = listener
        return this
    }

    /**
     * 设置响应次数
     *
     * @param count
     * @return
     */
    open fun responseCount(count: Int): ResponseProcess {
        this.count = count
        return this
    }

    /**
     * 无限响应
     *
     * @return
     */
    open fun infiniteResponse(): ResponseProcess {
        this.count = MAX_COUNT
        return this
    }

    internal fun match(request: Request?, receive: ByteArray): Boolean {
        try {
            responseRules.forEach {
                val isMatch = it.match(request, receive)
                if (!isMatch) {
                    return false
                }
            }
        } catch (e: Exception) {
            return false
        }
        return true
    }

    /**
     * 处理次数
     *
     * @return 是否消耗完毕
     */
    internal fun deductCount(): Boolean {
        if (count >= MAX_COUNT) {
            return false
        }
        count -= 1
        if (count <= 0) {
            count = 0
            return true
        }
        return false
    }

    /**
     * 进入数据等待阶段
     * 在收到ACK确认后调用
     *
     * @param dataCount 数据阶段期望的响应次数
     * @param dataRetry 数据阶段重试次数
     */
    internal fun enterDataPhase(dataCount: Int = 1, dataRetry: Int = 0) {
        currentPhase = RequestPhase.WAITING_DATA
        ackReceived = true
        waitingForData = true
        ackReceiveTime = System.currentTimeMillis()
        dataResponseCount = dataCount
        dataRetryCount = dataRetry
    }

    /**
     * 标记为已完成
     */
    internal fun markCompleted() {
        currentPhase = RequestPhase.COMPLETED
        waitingForData = false
    }

    /**
     * 重置到ACK等待阶段（用于ACK重试）
     */
    internal fun resetToAckPhase() {
        currentPhase = RequestPhase.WAITING_ACK
        ackReceived = false
        waitingForData = false
        ackReceiveTime = 0
    }

    /**
     * 扣减数据阶段响应次数
     *
     * @return 是否消耗完毕
     */
    internal fun deductDataCount(): Boolean {
        if (dataResponseCount >= MAX_COUNT) {
            return false
        }
        dataResponseCount -= 1
        if (dataResponseCount <= 0) {
            dataResponseCount = 0
            return true
        }
        return false
    }

    /**
     * 扣减数据阶段重试次数
     *
     * @return 是否消耗完毕（true=已耗尽）
     */
    internal fun deductDataRetryCount(): Boolean {
        if (dataRetryCount <= 0) {
            dataRetryCount = 0
            return true
        }
        dataRetryCount -= 1
        return false
    }

    /**
     * 检查数据阶段是否超时
     *
     * @param timeout 数据阶段超时时间
     * @return 是否超时
     */
    internal fun isDataTimeout(timeout: Long): Boolean {
        if (ackReceiveTime <= 0 || timeout <= 0) return false
        return System.currentTimeMillis() - ackReceiveTime > timeout
    }
}