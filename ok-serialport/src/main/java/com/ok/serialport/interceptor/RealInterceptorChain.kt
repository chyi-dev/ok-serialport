package com.ok.serialport.interceptor


class RealInterceptorChain<T>(
    private val interceptors: List<Interceptor<T>>,
    private val index: Int,
    private val data: T
) : Interceptor.Chain<T> {

    override fun data(): T {
        return data
    }

    override fun proceed(data: T): T {
        if (index >= interceptors.size) {
            return data
        }
        // 获取下一个拦截器
        val next = RealInterceptorChain(interceptors, index + 1, data)
        val interceptor = interceptors[index]
        // 调用当前拦截器的 intercept 方法
        return interceptor.intercept(next)
    }
}