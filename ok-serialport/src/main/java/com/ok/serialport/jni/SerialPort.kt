package com.ok.serialport.jni

import java.io.File
import java.io.FileDescriptor

/**
 * 串口交互
 * @author Leyi
 * @date 2024/10/25 9:49
 */
open class SerialPort {

    companion object {
        init {
            System.loadLibrary("SerialPort")
        }
    }

    /**
     * 文件设置最高权限 777 可读 可写 可执行
     * @param file File
     * @return Boolean 权限修改是否成功
     */
    fun chmod777(file: File?): Boolean {
        if (file == null || file.exists()) {
            return false
        }

        try {
            // 获取ROOT权限
            val process = Runtime.getRuntime().exec("/system/bin/su")
            // 修改文件属性为 [可读 可写 可执行]
            val cmd = """
                chmod 777 ${file.absolutePath}
                exit
                """.trimIndent()
            process.outputStream.write(cmd.toByteArray())
            if (process.waitFor() == 0
                && file.canRead()
                && file.canWrite()
                && file.canExecute()
            ) {
                return true
            }
        } catch (ex: Exception) {
            // 没有ROOT权限
            ex.printStackTrace()
        }
        return false
    }

    // 打开串口
    external fun open(
        path: String,
        baudRate: Int,
        flags: Int,
        dataBit: Int,
        stopBit: Int,
        parity: Int
    ): FileDescriptor

    // 关闭串口
    external fun close()

}