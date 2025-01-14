package com.ok.serialport.demo.interceptor

import com.ok.serialport.data.Request
import com.ok.serialport.interceptor.Interceptor

/**
 * 请求拦截器
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class RequestInterceptor : Interceptor<Request> {
    override fun intercept(chain: Interceptor.Chain<Request>): Request {
        val request = chain.data()
        // 修改请求数据
        request.data[4] = 0x2F
        // 将修改后的请求传递给下一个拦截器
        return chain.proceed(request)
    }
}