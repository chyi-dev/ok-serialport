package com.ok.serialport.data

/**
 * 请求响应匹配规则
 * @author Leyi
 * @date 2025/1/10 16:35
 */
interface ResponseRule {

    /**
     * 通过收到的数据进行响应匹配
     *
     * @param receive 数据
     * @return 匹配结果
     */
    fun match(request: Request?, receive: ByteArray): Boolean

}