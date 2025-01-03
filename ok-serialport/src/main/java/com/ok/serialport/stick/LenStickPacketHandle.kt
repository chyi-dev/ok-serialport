package com.ok.serialport.stick

import java.io.IOException
import java.io.InputStream

/**
 * 定长粘包解析
 * @author Leyi
 * @date 2024/11/9 9:35
 */
class LenStickPacketHandle constructor(
    val stickLength: Int = 17
) : AbsStickPacketHandle {

    override fun execute(inputStream: InputStream): ByteArray? {
        var count = 0
        var len = -1
        var temp: Byte
        val result = ByteArray(stickLength)
        try {
            while (count < stickLength && (inputStream.read().also { len = it }) != -1) {
                temp = len.toByte()
                result[count] = temp
                count++
            }
            if (len == -1) {
                return null
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
        return result
    }
}