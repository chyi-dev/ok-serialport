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
}