package com.ok.serialport.jni

import com.ok.serialport.utils.SerialLogger
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 串口交互
 * @author Leyi
 * @date 2024/10/25 9:49
 */
open class SerialPort(
    // 串口地址
    private val devicePath: String,
    // 波特率
    private val baudRate: Int,
    // 标志位
    private val flags: Int,
    // 数据位
    private val dataBit: Int,
    // 停止位
    private val stopBit: Int,
    // 校验位：0 表示无校验位，1 表示奇校验，2 表示偶校验
    private val parity: Int,
    //日志
    private val logger: SerialLogger
) {

    companion object {
        init {
            System.loadLibrary("SerialPort")
        }
    }

    private var fileInputStream: FileInputStream? = null
    private var fileOutputStream: FileOutputStream? = null
    private var fileDescriptor: FileDescriptor? = null

    /**
     * 连接串口设备
     * @return Boolean
     */
    fun connect() {
        if (!checkPermission()) {
            throw SecurityException("当前设备串口读写权限不足")
        }
        try {
            fileDescriptor = open(devicePath, baudRate, flags, dataBit, stopBit, parity)
            fileInputStream = FileInputStream(fileDescriptor)
            fileOutputStream = FileOutputStream(fileDescriptor)
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * 检查串口权限
     * @return Boolean 是否有权限
     */
    private fun checkPermission(): Boolean {
        val deviceFile = File(devicePath)
        if (!deviceFile.canRead() || !deviceFile.canWrite()) {
            val chmod = chmod777(deviceFile)
            if (chmod) {
                return false
            }
        }
        return true
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

    /**
     * 读取数据流
     * @return InputStream?
     */
    fun readStream(): InputStream? {
        return fileInputStream
    }

    /**
     * 写入数据
     * @param data ByteArray
     */
    @Throws(IOException::class)
    fun write(data: ByteArray) {
        fileOutputStream?.let { output ->
            output.write(data)
            output.flush()
        }
    }

    /**
     * 关闭
     */
    open fun disconnect() {
        try {
            if (fileDescriptor != null) {
                close()
                fileDescriptor = null
            }
            fileInputStream?.close()
            fileOutputStream?.close()
            fileInputStream = null
            fileOutputStream = null
            logger.log("${devicePath}-${baudRate}串口关闭成功")
        } catch (e: Exception) {
            logger.log("${devicePath}-${baudRate}串口关闭异常：${e.message}")
            e.printStackTrace()
        }
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