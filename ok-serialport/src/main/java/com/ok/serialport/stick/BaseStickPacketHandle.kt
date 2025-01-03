package com.ok.serialport.stick

import java.io.InputStream

/**
 * 最简单的办法就是不要处理粘包，直接读取并返回与InputStream.available（）读取一样多的值
 * @author Leyi
 * @date 2024/10/24 15:45
 */
class BaseStickPacketHandle : AbsStickPacketHandle {

    override fun execute(inputStream: InputStream): ByteArray? {
        val available = inputStream.available()
        if (available > 0) {
            val buffer = ByteArray(available)
            val size = inputStream.read(buffer)
            if (size > 0) {
                return buffer
            }
        }
        return null
    }
}