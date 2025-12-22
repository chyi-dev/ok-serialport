package com.ok.serialport.stats

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能统计收集器
 * 线程安全，使用原子类保证并发安全
 * @author Leyi
 * @date 2025/1/10
 */
class PerformanceStatsCollector {
    companion object {
        // 响应时间样本最大数量，用于计算平均值
        private const val MAX_RESPONSE_TIME_SAMPLES = 1000
    }

    // 计数器
    private val totalSentRequests = AtomicLong(0)
    private val totalReceivedResponses = AtomicLong(0)
    private val successCount = AtomicLong(0)
    private val failureCount = AtomicLong(0)
    private val timeoutCount = AtomicLong(0)
    private val totalSentBytes = AtomicLong(0)
    private val totalReceivedBytes = AtomicLong(0)

    // 响应时间统计
    private val responseTimeSamples = ConcurrentLinkedQueue<Long>()
    private val maxResponseTime = AtomicLong(0)
    private val minResponseTime = AtomicLong(Long.MAX_VALUE)

    // 队列状态（需要外部更新）
    private val currentQueueSize = AtomicInteger(0)
    private val currentRunningRequests = AtomicInteger(0)

    // 时间戳
    private var startTime = System.currentTimeMillis()
    private val lastUpdateTime = AtomicLong(startTime)

    /**
     * 记录发送请求
     */
    fun recordSentRequest(bytes: Int) {
        totalSentRequests.incrementAndGet()
        totalSentBytes.addAndGet(bytes.toLong())
        lastUpdateTime.set(System.currentTimeMillis())
    }

    /**
     * 记录接收响应
     */
    fun recordReceivedResponse(bytes: Int) {
        totalReceivedResponses.incrementAndGet()
        totalReceivedBytes.addAndGet(bytes.toLong())
        lastUpdateTime.set(System.currentTimeMillis())
    }

    /**
     * 记录成功响应
     */
    fun recordSuccess(responseTime: Long) {
        successCount.incrementAndGet()
        recordResponseTime(responseTime)
        lastUpdateTime.set(System.currentTimeMillis())
    }

    /**
     * 记录成功但不记录响应时间（用于非 Request 类型的 ResponseProcess）
     */
    fun recordSuccessWithoutTime() {
        successCount.incrementAndGet()
        lastUpdateTime.set(System.currentTimeMillis())
    }

    /**
     * 记录失败
     */
    fun recordFailure() {
        failureCount.incrementAndGet()
        lastUpdateTime.set(System.currentTimeMillis())
    }

    /**
     * 记录超时
     */
    fun recordTimeout() {
        timeoutCount.incrementAndGet()
        lastUpdateTime.set(System.currentTimeMillis())
    }

    /**
     * 记录响应时间
     */
    private fun recordResponseTime(responseTime: Long) {
        // 添加到样本队列
        responseTimeSamples.offer(responseTime)

        // 限制样本数量，移除最旧的样本
        while (responseTimeSamples.size > MAX_RESPONSE_TIME_SAMPLES) {
            responseTimeSamples.poll()
        }

        // 更新最大响应时间（使用循环 CAS 操作，API 21 兼容）
        var current = maxResponseTime.get()
        while (responseTime > current && !maxResponseTime.compareAndSet(current, responseTime)) {
            current = maxResponseTime.get()
        }

        // 更新最小响应时间（使用循环 CAS 操作，API 21 兼容）
        current = minResponseTime.get()
        while (responseTime < current && !minResponseTime.compareAndSet(current, responseTime)) {
            current = minResponseTime.get()
        }
    }

    /**
     * 更新队列大小
     */
    fun updateQueueSize(size: Int) {
        currentQueueSize.set(size)
        lastUpdateTime.set(System.currentTimeMillis())
    }

    /**
     * 更新运行中请求数
     */
    fun updateRunningRequests(count: Int) {
        currentRunningRequests.set(count)
        lastUpdateTime.set(System.currentTimeMillis())
    }

    /**
     * 获取当前统计快照
     */
    fun getStats(): PerformanceStats {
        // 计算平均响应时间
        val samples = responseTimeSamples.toList()
        val averageResponseTime = if (samples.isNotEmpty()) {
            samples.sum() / samples.size
        } else {
            0L
        }

        val minTime = if (minResponseTime.get() == Long.MAX_VALUE) {
            0L
        } else {
            minResponseTime.get()
        }

        return PerformanceStats(
            totalSentRequests = totalSentRequests.get(),
            totalReceivedResponses = totalReceivedResponses.get(),
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            timeoutCount = timeoutCount.get(),
            averageResponseTime = averageResponseTime,
            maxResponseTime = maxResponseTime.get(),
            minResponseTime = minTime,
            totalSentBytes = totalSentBytes.get(),
            totalReceivedBytes = totalReceivedBytes.get(),
            currentQueueSize = currentQueueSize.get(),
            currentRunningRequests = currentRunningRequests.get(),
            startTime = startTime,
            lastUpdateTime = lastUpdateTime.get()
        )
    }

    /**
     * 重置统计数据
     */
    fun reset() {
        totalSentRequests.set(0)
        totalReceivedResponses.set(0)
        successCount.set(0)
        failureCount.set(0)
        timeoutCount.set(0)
        totalSentBytes.set(0)
        totalReceivedBytes.set(0)
        responseTimeSamples.clear()
        maxResponseTime.set(0)
        minResponseTime.set(Long.MAX_VALUE)
        currentQueueSize.set(0)
        currentRunningRequests.set(0)
        startTime = System.currentTimeMillis()
        lastUpdateTime.set(System.currentTimeMillis())
    }
}
