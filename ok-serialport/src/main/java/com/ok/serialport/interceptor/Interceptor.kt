package com.ok.serialport.interceptor

/**
 * 请求拦截器
 * @author Leyi
 * @date 2025/1/10 16:35
 */
interface Interceptor<T> {

    fun intercept(chain: Chain<T>): T

    interface Chain<T> {
        fun data(): T
        fun proceed(data: T): T
    }
}