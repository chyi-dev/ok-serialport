package com.ok.serialport.listener

import com.ok.serialport.data.Request
import com.ok.serialport.data.Response

/**
 * 响应监听
 * @author Leyi
 * @date 2025/1/10 16:46
 */
interface OnResponseListener {
    /**
     * 成功响应
     *
     * @param response
     */
    fun onResponse(response: Response)

    /**
     * 失败响应
     *
     * @param request
     * @param e
     */
    fun onFailure(request: Request?, e: Exception)
}