package com.ok.serialport.interceptor

import com.ok.serialport.data.Request

/**
 * 请求拦截器
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class RequestInterceptor :Interceptor<Request>{
    override fun intercept(chain: Interceptor.Chain<Request>): Request {
        val request = chain.request()
        // 修改请求数据
        // 将修改后的请求传递给下一个拦截器
        return chain.proceed(request)
    }
}