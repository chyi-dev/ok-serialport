package com.ok.serialport.model

import java.io.File
import java.io.Serializable

/**
 *
 * @author Leyi
 * @date 2024/10/24 16:35
 */
data class Device(
    val name: String,
    val root: String,
    val file: File
) : Serializable
