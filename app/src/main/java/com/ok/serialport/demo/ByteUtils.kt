package com.ok.serialport.demo

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

    /**
     * 十六进制String转ByteArray
     * @param str String?
     * @return ByteArray?
     */
    fun strToByte(str: String?): ByteArray? {
        if (str == null) {
            return null
        }
        // 去除空格
        val trimmedStr = str.replace(" ", "")
        if (trimmedStr.isEmpty()) {
            return byteArrayOf()
        }
        // 检查长度是否为偶数
        if (trimmedStr.length % 2 != 0) {
            throw IllegalArgumentException("Input string length must be even after removing spaces")
        }
        // 转换逻辑
        val byteArray = ByteArray(trimmedStr.length / 2)
        for (i in byteArray.indices) {
            val subStr = trimmedStr.substring(2 * i, 2 * i + 2)
            byteArray[i] = subStr.toInt(16).toByte()
        }
        return byteArray
    }
}