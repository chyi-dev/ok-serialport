package com.ok.serialport.data

/**
 * ACK/NAK 匹配规则接口
 * 用于定义如何判断接收到的数据是ACK还是NAK
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
interface AckNakRule {

    /**
     * 判断是否为ACK响应
     *
     * @param data 接收到的数据
     * @return true=是ACK，false=不是ACK
     */
    fun isAck(data: ByteArray): Boolean

    /**
     * 判断是否为NAK响应
     *
     * @param data 接收到的数据
     * @return true=是NAK，false=不是NAK
     */
    fun isNak(data: ByteArray): Boolean
}

/**
 * 简单的单字节ACK/NAK规则实现
 * 适用于简单的协议，如ACK=0x06，NAK=0x15
 *
 * @param ackByte ACK的字节值
 * @param nakByte NAK的字节值
 */
class SimpleByteAckNakRule(
    private val ackByte: Byte,
    private val nakByte: Byte
) : AckNakRule {

    override fun isAck(data: ByteArray): Boolean {
        return data.isNotEmpty() && data[0] == ackByte
    }

    override fun isNak(data: ByteArray): Boolean {
        return data.isNotEmpty() && data[0] == nakByte
    }
}

/**
 * 基于自定义匹配函数的ACK/NAK规则
 *
 * @param ackMatcher ACK匹配函数
 * @param nakMatcher NAK匹配函数
 */
class FunctionAckNakRule(
    private val ackMatcher: (ByteArray) -> Boolean,
    private val nakMatcher: (ByteArray) -> Boolean
) : AckNakRule {

    override fun isAck(data: ByteArray): Boolean {
        return ackMatcher(data)
    }

    override fun isNak(data: ByteArray): Boolean {
        return nakMatcher(data)
    }
}

/**
 * 复合ACK/NAK规则
 * 同时满足多个条件才认为是ACK或NAK
 *
 * @param rules 规则列表
 */
class CompositeAckNakRule(private val rules: List<AckNakRule>) : AckNakRule {

    constructor(vararg rules: AckNakRule) : this(rules.toList())

    /**
     * 所有规则都匹配ACK才算ACK
     */
    override fun isAck(data: ByteArray): Boolean {
        if (rules.isEmpty()) return false
        return rules.all { it.isAck(data) }
    }

    /**
     * 所有规则都匹配NAK才算NAK
     */
    override fun isNak(data: ByteArray): Boolean {
        if (rules.isEmpty()) return false
        return rules.all { it.isNak(data) }
    }
}

/**
 * 任一规则匹配即成立的ACK/NAK规则
 *
 * @param rules 规则列表
 */
class AnyMatchAckNakRule(private val rules: List<AckNakRule>) : AckNakRule {

    constructor(vararg rules: AckNakRule) : this(rules.toList())

    /**
     * 任一规则匹配ACK就算ACK
     */
    override fun isAck(data: ByteArray): Boolean {
        if (rules.isEmpty()) return false
        return rules.any { it.isAck(data) }
    }

    /**
     * 任一规则匹配NAK就算NAK
     */
    override fun isNak(data: ByteArray): Boolean {
        if (rules.isEmpty()) return false
        return rules.any { it.isNak(data) }
    }
}
