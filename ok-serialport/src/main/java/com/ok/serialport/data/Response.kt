package com.ok.serialport.data

import com.ok.serialport.utils.ByteUtils

/**
 * 响应实体
 * @author Leyi
 * @date 2025/1/10 16:35
 */
open class Response(var data: ByteArray) {

    /**
     * 对应请求 [Request]
     */
    open var request: Request? = null

    /**
     * 数据转Hex
     * @return String
     */
    open fun toHex(): String {
        return ByteUtils.byteArrToHexStr(data)
    }
}