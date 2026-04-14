package com.ok.serialport.exception

/**
 * 数据阶段超时异常
 * 在配置了ACK/NAK且数据响应超时时抛出
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class DataTimeoutException(message: String = "数据响应超时") : Exception(message)
