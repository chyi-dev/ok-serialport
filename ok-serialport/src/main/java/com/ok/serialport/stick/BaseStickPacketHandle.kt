package com.ok.serialport.stick

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

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
        } catch (e: EOFException) {
            // 流结束异常，表示流已关闭，这是正常情况
            return null
        } catch (e: InterruptedIOException) {
            // 线程中断异常，重新设置中断标志并返回 null
            Thread.currentThread().interrupt()
            return null
        } catch (e: IOException) {
            // IO异常，可能是串口断开或设备移除，由上层处理
            // 这里返回 null，上层会捕获 IOException 并处理
            throw e
        } catch (e: Exception) {
            // 其他未知异常，重新抛出让上层处理
            throw e
        }
    }
}