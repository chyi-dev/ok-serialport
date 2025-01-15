package com.ok.serialport.demo

import com.blankj.utilcode.util.PathUtils
import java.io.File

/**
 *
 * @author Leyi
 * @date 2024/10/19 11:11
 */
object Constant {

    private val CACHE_PATH: String = PathUtils.getExternalStoragePath() + File.separator + "ok-serialport"
    val LOG_PATH: String = CACHE_PATH + File.separator + "Log"
}