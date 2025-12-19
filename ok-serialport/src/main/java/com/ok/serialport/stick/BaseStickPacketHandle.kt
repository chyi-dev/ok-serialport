package com.ok.serialport.stick

import java.io.InputStream

/**
 * 使用阻塞式读取，提高数据抢占率
 * 使用固定大小缓冲区，减少内存分配开销
 * @author Leyi
 * @date 2024/10/24 15:45
 */
class BaseStickPacketHandle : AbsStickPacketHandle {

    companion object {
        // 固定缓冲区大小，减少内存分配
        private const val BUFFER_SIZE = 4096
    }

    override fun execute(inputStream: InputStream): ByteArray? {
        try {
            // 使用固定大小缓冲区
            val buffer = ByteArray(BUFFER_SIZE)
            // 阻塞读取，直到有数据到达或流关闭
            val size = inputStream.read(buffer)
            
            if (size > 0) {
                // 如果读取的数据小于缓冲区大小，返回实际大小的数组
                return if (size < buffer.size) {
                    buffer.copyOf(size)
                } else {
                    buffer
                }
            }
            // size == -1 表示流已关闭
            return null
        } catch (e: Exception) {
            // 异常时返回null，由上层处理
            return null
        }
    }
}