package com.ok.serialport.data

import com.ok.serialport.utils.ByteUtils

/**
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class Response(private val data: ByteArray) {

    var request: Request? = null

    /**
     * 数据转Hex
     * @return String
     */
    fun toHex(): String {
        return ByteUtils.byteArrToHexStr(data)
    }
}