package com.ok.serialport.newer.data

import com.ok.serialport.newer.utils.ByteUtils

/**
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class Request(val data: ByteArray) {

    /**
     * 数据转Hex
     * @return String
     */
    fun toHex(): String {
        return ByteUtils.byteArrToHexStr(data)
    }

    override fun toString(): String {
        return super.toString()
    }
}