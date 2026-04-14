package com.ok.serialport.exception

/**
 * ACK阶段超时异常
 * 在配置了ACK/NAK且ACK超时时抛出
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class AckTimeoutException(message: String = "ACK响应超时") : Exception(message)
