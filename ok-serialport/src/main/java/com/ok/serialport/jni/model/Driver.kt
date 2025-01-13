package com.ok.serialport.jni.model

import android.util.Log
import java.io.File

/**
 *
 * @author Leyi
 * @date 2024/10/24 16:36
 */
data class Driver(
    val driverName: String,
    val deviceRoot: String
) {
    companion object {
        var TAG: String = Driver::class.java.simpleName
    }

    fun getDevices(): MutableList<File> {
        val devices = mutableListOf<File>()

        val dev = File("/dev")
        if (!dev.exists()) {
            Log.i(TAG, "getDevices: " + dev.absolutePath + " 不存在")
            return devices
        }
        if (!dev.canRead()) {
            Log.i(TAG, "getDevices: " + dev.absolutePath + " 没有读取权限")
            return devices
        }

        val files = dev.listFiles()
        if (files == null) {
            Log.i(TAG, "getDevices: " + dev.absolutePath + " 文件列表为空")
            return devices
        }
        files.forEach {
            if (it.absolutePath.startsWith(deviceRoot)) {
                Log.d(TAG, "Found new device: $it")
                devices.add(it)
            }
        }

        return devices
    }
}
