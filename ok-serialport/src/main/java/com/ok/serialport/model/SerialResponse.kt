package com.ok.serialport.model

import com.ok.serialport.enums.ResponseState

/**
 *
 * @author Leyi
 * @date 2024/10/31 11:48
 */
data class SerialResponse(
    val data: ByteArray,                 // 接收到的数据
    val state: ResponseState = ResponseState.SUCCESS     // 响应状态
){
    @OptIn(ExperimentalStdlibApi::class)
    override fun toString(): String {
        return "SerialResponse(data=${data.toHexString().uppercase()}, state=$state)"
    }
}
