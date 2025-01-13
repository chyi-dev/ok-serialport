package com.ok.serialport.jni

import com.ok.serialport.utils.SerialLogger
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 串口连接，数据io
 * @author Leyi
 * @date 2025/1/10 15:29
 */
open class SerialPortClient(
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
) : SerialPort() {
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
}