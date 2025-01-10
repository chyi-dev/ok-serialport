package com.ok.serialport.process

/**
 * 响应匹配器
 * @author Leyi
 * @date 2025/1/10 11:21
 */
interface ResponseMatcher {

    fun isMatch(bytes: ByteArray): Boolean

}