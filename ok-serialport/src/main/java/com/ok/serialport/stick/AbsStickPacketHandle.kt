package com.ok.serialport.stick

import java.io.InputStream

/**
 * 串口粘包处理接口
 * @author Leyi
 * @date 2024/10/24 15:41
 */
interface AbsStickPacketHandle {
    fun execute(inputStream: InputStream): ByteArray?
}