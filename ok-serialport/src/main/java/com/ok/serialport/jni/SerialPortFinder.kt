package com.ok.serialport.jni

import android.util.Log
import com.ok.serialport.jni.model.Device
import com.ok.serialport.jni.model.Driver
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.LineNumberReader
import java.util.Vector

/**
 * 串口设备查找
 * @author Leyi
 * @date 2024/10/29 14:48
 */
class SerialPortFinder {

    companion object {
        private
        val TAG: String = SerialPortFinder::class.java.simpleName

        private
        const val DRIVERS_PATH: String = "/proc/tty/drivers"

        private
        const val SERIAL_FIELD: String = "serial"

    }

    init {
        val file = File(DRIVERS_PATH)
        val b = file.canRead()
        Log.i(TAG, "SerialPortFinder: file.canRead() = $b")
    }

    /**
     * 获取 Drivers
     *
     * @return Drivers
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    fun getDrivers(): MutableList<Driver> {
        val drivers = mutableListOf<Driver>()
        val lineNumberReader = LineNumberReader(FileReader(DRIVERS_PATH))
        var readLine: String?
        while ((lineNumberReader.readLine().also { readLine = it }) != null) {
            readLine?.let { str ->
                val driverName = str.substring(0, 0x15).trim { it <= ' ' }
                val fields = str.split(" +".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                if ((fields.size >= 5) && (fields[fields.size - 1] == SERIAL_FIELD)) {
                    Log.i(TAG, "Found new driver " + driverName + " on " + fields[fields.size - 4])
                    drivers.add(Driver(driverName, fields[fields.size - 4]))
                }
            }
        }
        return drivers
    }

    /**
     * 获取串口
     *
     * @return 串口
     */
    fun getDevices(): MutableList<Device> {
        val devices = mutableListOf<Device>()
        try {
            val drivers: MutableList<Driver> = getDrivers()
            for (driver in drivers) {
                val driverName = driver.driverName
                val driverDevices = driver.getDevices()
                for (file in driverDevices) {
                    val devicesName = file.name
                    devices.add(Device(devicesName, driverName, file))
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val list = devices.sortedBy { it.name.lowercase() }.toMutableList()
        return list
    }


    fun getAllDevicesPath(): Array<String> {
        val devices = Vector<String>()
        // Parse each driver
        val itdriv: Iterator<Driver>
        try {
            itdriv = getDrivers().iterator()
            while (itdriv.hasNext()) {
                val driver = itdriv.next()
                val itdev: Iterator<File> = driver.getDevices().iterator()
                while (itdev.hasNext()) {
                    val device = itdev.next().absolutePath
                    devices.add(device)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return devices.toTypedArray<String>()
    }
}