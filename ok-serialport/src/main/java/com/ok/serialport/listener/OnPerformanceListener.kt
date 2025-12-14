package com.ok.serialport.listener

import com.ok.serialport.data.PerformanceMetrics

/**
 * 性能监控监听器
 * 用于监控串口通信的性能指标
 * @author Leyi
 * @date 2025/1/10
 */
interface OnPerformanceListener {
    /**
     * 性能指标更新回调
     * @param metrics 性能指标数据
     */
    fun onPerformanceUpdate(metrics: PerformanceMetrics)
}

