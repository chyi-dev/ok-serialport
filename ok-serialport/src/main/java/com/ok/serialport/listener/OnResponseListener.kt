package com.ok.serialport.listener

import com.ok.serialport.data.Request
import com.ok.serialport.data.Response
import com.ok.serialport.data.ResponseRule

/**
 * 响应监听
 * 需和[ResponseRule]配合使用
 * @author Leyi
 * @date 2025/1/10 16:46
 */
public interface OnResponseListener {
    /**
     * 成功响应
     * 在普通模式下或ACK/NAK模式下收到数据时回调
     *
     * @param response
     */
    fun onResponse(response: Response)

    /**
     * 失败响应
     *
     * @param request 如未通过Request发起，request = null
     * @param e
     */
    fun onFailure(request: Request?, e: Exception)

    /**
     * 收到ACK确认
     * 仅在配置了ACK/NAK且收到ACK响应时回调
     * 注意：如果配置了waitData=true，收到ACK后还会继续等待数据
     *
     * @param request 对应的请求
     */
    fun onAckReceived(request: Request) {
        // 默认空实现，保持向后兼容
    }

    /**
     * 收到NAK响应
     * 仅在配置了ACK/NAK且收到NAK响应时回调
     * 如果配置了ackRetryCount，收到NAK后会自动重试，此回调会在每次收到NAK时触发
     *
     * @param request 对应的请求
     */
    fun onNakReceived(request: Request) {
        // 默认空实现，保持向后兼容
    }

    /**
     * 收到具体数据响应（ACK/NAK模式下）
     * 仅在配置了ACK/NAK且waitData=true时，收到数据响应后回调
     * 此方法与onResponse的区别：
     * - onResponse：所有成功响应都会回调（包括普通模式和ACK/NAK模式）
     * - onDataReceived：仅在ACK/NAK模式下收到数据时回调，且会在onAckReceived之后
     *
     * @param response 数据响应
     */
    fun onDataReceived(response: Response) {
        // 默认空实现，保持向后兼容
        // 如果不实现，会fallback到onResponse
        onResponse(response)
    }

    /**
     * ACK阶段超时
     * 仅在配置了ACK/NAK且ACK超时、且ACK重试次数已耗尽时回调
     *
     * @param request 对应的请求
     */
    fun onAckTimeout(request: Request) {
        // 默认空实现，保持向后兼容
    }

    /**
     * 数据阶段超时
     * 仅在配置了ACK/NAK且waitData=true、数据超时、且数据重试次数已耗尽时回调
     *
     * @param request 对应的请求
     */
    fun onDataTimeout(request: Request) {
        // 默认空实现，保持向后兼容
    }
}