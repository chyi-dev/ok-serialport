package com.ok.serialport.demo.interceptor

import com.ok.serialport.data.Response
import com.ok.serialport.interceptor.Interceptor

/**
 * 请求拦截器
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class ResponseInterceptor : Interceptor<Response> {
    override fun intercept(chain: Interceptor.Chain<Response>): Response {
        val response = chain.data()
        // 修改请求数据
        response.data[8] = 0x24
        // 将修改后的请求传递给下一个拦截器
        return chain.proceed(response)
    }
}