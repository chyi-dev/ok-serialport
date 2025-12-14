package com.ok.serialport.data

/**
 * 性能指标数据类
 * @author Leyi
 * @date 2025/1/10
 */
data class PerformanceMetrics(
    /**
     * 总发送字节数
     */
    val totalBytesSent: Long = 0,
    
    /**
     * 总接收字节数
     */
    val totalBytesReceived: Long = 0,
    
    /**
     * 总发送请求数
     */
    val totalRequestsSent: Long = 0,
    
    /**
     * 总接收响应数
     */
    val totalResponsesReceived: Long = 0,
    
    /**
     * 平均读取延迟（毫秒）
     */
    val averageReadLatency: Double = 0.0,
    
    /**
     * 最大读取延迟（毫秒）
     */
    val maxReadLatency: Long = 0,
    
    /**
     * 最小读取延迟（毫秒）
     */
    val minReadLatency: Long = Long.MAX_VALUE,
    
    /**
     * 数据丢失次数
     */
    val dataLossCount: Long = 0,
    
    /**
     * 超时请求数
     */
    val timeoutRequestCount: Long = 0,
    
    /**
     * 当前队列中的请求数
     */
    val currentQueueSize: Int = 0,
    
    /**
     * 当前运行中的请求数
     */
    val currentRunningRequests: Int = 0,
    
    /**
     * 统计开始时间（毫秒时间戳）
     */
    val startTime: Long = System.currentTimeMillis(),
    
    /**
     * 最后更新时间（毫秒时间戳）
     */
    val lastUpdateTime: Long = System.currentTimeMillis()
) {
    /**
     * 计算平均吞吐量（字节/秒）
     */
    fun getAverageThroughput(): Double {
        val duration = (lastUpdateTime - startTime) / 1000.0
        if (duration <= 0) return 0.0
        return (totalBytesSent + totalBytesReceived) / duration
    }
    
    /**
     * 计算发送吞吐量（字节/秒）
     */
    fun getSendThroughput(): Double {
        val duration = (lastUpdateTime - startTime) / 1000.0
        if (duration <= 0) return 0.0
        return totalBytesSent / duration
    }
    
    /**
     * 计算接收吞吐量（字节/秒）
     */
    fun getReceiveThroughput(): Double {
        val duration = (lastUpdateTime - startTime) / 1000.0
        if (duration <= 0) return 0.0
        return totalBytesReceived / duration
    }
    
    /**
     * 计算成功率（%）
     */
    fun getSuccessRate(): Double {
        if (totalRequestsSent == 0L) return 0.0
        val successCount = totalResponsesReceived
        return (successCount.toDouble() / totalRequestsSent) * 100.0
    }
    
    /**
     * 计算数据丢失率（%）
     */
    fun getDataLossRate(): Double {
        if (totalBytesSent == 0L) return 0.0
        return (dataLossCount.toDouble() / totalBytesSent) * 100.0
    }
}

