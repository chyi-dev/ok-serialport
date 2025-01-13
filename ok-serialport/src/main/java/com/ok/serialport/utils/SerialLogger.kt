package com.ok.serialport.utils

import android.util.Log

/**
 * 日志
 * @author Leyi
 * @date 2024/10/31 13:39
 */
open class SerialLogger {
    open fun log(message: String) {
        Log.i("OK-SerialPort", message)
    }
}