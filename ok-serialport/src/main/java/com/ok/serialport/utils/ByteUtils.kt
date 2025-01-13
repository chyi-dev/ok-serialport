package com.ok.serialport.utils

/**
 * byte工具类
 * @author Leyi
 * @date 2024/12/18 16:30
 */
object ByteUtils {

    /**
     * Byte转BooleanArray
     * @param byte Byte
     * @return BooleanArray
     */
    fun byteToBoolArr(byte: Byte): BooleanArray {
        val booleanArray = BooleanArray(8) // 每个字节包含 8 位
        for (i in 0..7) {
            booleanArray[i] = (byte.toInt() shr (7 - i) and 1) == 1
        }
        return booleanArray
    }

    /**
     * ByteArray转Int
     * @param bytes ByteArray
     * @return Int
     */
    fun byteArrToInt(bytes: ByteArray): Int {
        require(bytes.size == 4) { "Byte array must have exactly 4 elements" }
        return (bytes[0].toInt() and 0xFF shl 24) or
                (bytes[1].toInt() and 0xFF shl 16) or
                (bytes[2].toInt() and 0xFF shl 8) or
                (bytes[3].toInt() and 0xFF)
    }

    /**
     * ByteArray转HexString
     * @param byteArray ByteArray
     * @param isUpperCase Boolean 是否大写
     * @return String
     */
    fun byteArrToHexStr(byteArray: ByteArray, isUpperCase: Boolean = true): String {
        val format = if (isUpperCase) "%02X" else "%02x"
        return byteArray.joinToString(" ") { format.format(it) }
    }
}