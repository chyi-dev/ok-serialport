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
        val next = RealInterceptorChain(interceptors, index + 1, data)
        val interceptor = interceptors[index]
        return interceptor.intercept(next)
    }
}