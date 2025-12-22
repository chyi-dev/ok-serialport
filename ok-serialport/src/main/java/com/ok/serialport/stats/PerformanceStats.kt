package com.ok.serialport.stats

/**
 * 性能统计数据类
 * @author Leyi
 * @date 2025/1/10
 */
data class PerformanceStats(
    /**
     * 发送请求总数
     */
    val totalSentRequests: Long,
    
    /**
     * 接收响应总数
     */
    val totalReceivedResponses: Long,
    
    /**
     * 成功请求数
     */
    val successCount: Long,
    
    /**
     * 失败请求数
     */
    val failureCount: Long,
    
    /**
     * 超时请求数
     */
    val timeoutCount: Long,
    
    /**
     * 平均响应时间（毫秒）
     */
    val averageResponseTime: Long,
    
    /**
     * 最大响应时间（毫秒）
     */
    val maxResponseTime: Long,
    
    /**
     * 最小响应时间（毫秒）
     */
    val minResponseTime: Long,
    
    /**
     * 总发送字节数
     */
    val totalSentBytes: Long,
    
    /**
     * 总接收字节数
     */
    val totalReceivedBytes: Long,
    
    /**
     * 当前待发送队列大小
     */
    val currentQueueSize: Int,
    
    /**
     * 当前运行中请求数
     */
    val currentRunningRequests: Int,
    
    /**
     * 统计开始时间（毫秒时间戳）
     */
    val startTime: Long,
    
    /**
     * 最后更新时间（毫秒时间戳）
     */
    val lastUpdateTime: Long
)

