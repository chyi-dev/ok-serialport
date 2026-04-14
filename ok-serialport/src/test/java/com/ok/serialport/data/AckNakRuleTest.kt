package com.ok.serialport.data

import org.junit.Assert.*
import org.junit.Test

/**
 * AckNakRule 单元测试
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class AckNakRuleTest {

    // ==================== SimpleByteAckNakRule 测试 ====================

    @Test
    fun `test SimpleByteAckNakRule matches single byte`() {
        val rule = SimpleByteAckNakRule(
            ackByte = 0x06.toByte(),
            nakByte = 0x15.toByte()
        )

        assertTrue(rule.isAck(byteArrayOf(0x06.toByte())))
        assertTrue(rule.isNak(byteArrayOf(0x15.toByte())))
        assertFalse(rule.isAck(byteArrayOf(0x15.toByte())))
        assertFalse(rule.isNak(byteArrayOf(0x06.toByte())))
    }

    @Test
    fun `test SimpleByteAckNakRule checks first byte only`() {
        val rule = SimpleByteAckNakRule(
            ackByte = 0x06.toByte(),
            nakByte = 0x15.toByte()
        )

        // 只要第一个字节匹配就算匹配
        assertTrue(rule.isAck(byteArrayOf(0x06.toByte(), 0x01, 0x02)))
        assertTrue(rule.isNak(byteArrayOf(0x15.toByte(), 0x01, 0x02)))
    }

    @Test
    fun `test SimpleByteAckNakRule returns false for empty data`() {
        val rule = SimpleByteAckNakRule(
            ackByte = 0x06.toByte(),
            nakByte = 0x15.toByte()
        )

        assertFalse(rule.isAck(byteArrayOf()))
        assertFalse(rule.isNak(byteArrayOf()))
    }

    // ==================== FunctionAckNakRule 测试 ====================

    @Test
    fun `test FunctionAckNakRule with custom logic`() {
        val rule = FunctionAckNakRule(
            ackMatcher = { data ->
                data.size >= 3 &&
                        data[0] == 0xAA.toByte() &&
                        data[1] == 0x55.toByte() &&
                        data[2] == 0x06.toByte()
            },
            nakMatcher = { data ->
                data.size >= 3 &&
                        data[0] == 0xAA.toByte() &&
                        data[1] == 0x55.toByte() &&
                        data[2] == 0x15.toByte()
            }
        )

        val ackData = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x06.toByte())
        val nakData = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x15.toByte())
        val otherData = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x00.toByte())

        assertTrue(rule.isAck(ackData))
        assertFalse(rule.isAck(nakData))
        assertFalse(rule.isAck(otherData))

        assertFalse(rule.isNak(ackData))
        assertTrue(rule.isNak(nakData))
        assertFalse(rule.isNak(otherData))
    }

    // ==================== CompositeAckNakRule 测试 ====================

    @Test
    fun `test CompositeAckNakRule all must match`() {
        val rule1 = SimpleByteAckNakRule(0x06.toByte(), 0x15.toByte())
        val rule2 = FunctionAckNakRule(
            ackMatcher = { data -> data.size == 1 },
            nakMatcher = { data -> data.size == 1 }
        )

        val composite = CompositeAckNakRule(rule1, rule2)

        // 两个规则都要匹配
        assertTrue(composite.isAck(byteArrayOf(0x06.toByte())))  // 匹配rule1和rule2
        assertTrue(composite.isNak(byteArrayOf(0x15.toByte())))  // 匹配rule1和rule2

        // 第一个字节对但不满足size==1
        assertFalse(composite.isAck(byteArrayOf(0x06.toByte(), 0x00)))
        assertFalse(composite.isNak(byteArrayOf(0x15.toByte(), 0x00)))
    }

    @Test
    fun `test CompositeAckNakRule with empty list returns false`() {
        val composite = CompositeAckNakRule(emptyList())

        assertFalse(composite.isAck(byteArrayOf(0x06.toByte())))
        assertFalse(composite.isNak(byteArrayOf(0x15.toByte())))
    }

    // ==================== AnyMatchAckNakRule 测试 ====================

    @Test
    fun `test AnyMatchAckNakRule any can match`() {
        val rule1 = SimpleByteAckNakRule(0x06.toByte(), 0x15.toByte())
        val rule2 = FunctionAckNakRule(
            ackMatcher = { data -> data.size == 1 && data[0] == 0xAA.toByte() },
            nakMatcher = { data -> data.size == 1 && data[0] == 0x55.toByte() }
        )

        val anyMatch = AnyMatchAckNakRule(rule1, rule2)

        // 任一规则匹配即可
        assertTrue(anyMatch.isAck(byteArrayOf(0x06.toByte())))  // 匹配rule1
        assertTrue(anyMatch.isAck(byteArrayOf(0xAA.toByte()))) // 匹配rule2
        assertTrue(anyMatch.isNak(byteArrayOf(0x15.toByte())))  // 匹配rule1
        assertTrue(anyMatch.isNak(byteArrayOf(0x55.toByte())))  // 匹配rule2

        // 都不匹配
        assertFalse(anyMatch.isAck(byteArrayOf(0x00.toByte())))
        assertFalse(anyMatch.isNak(byteArrayOf(0x00.toByte())))
    }

    @Test
    fun `test AnyMatchAckNakRule with empty list returns false`() {
        val anyMatch = AnyMatchAckNakRule(emptyList())

        assertFalse(anyMatch.isAck(byteArrayOf(0x06.toByte())))
        assertFalse(anyMatch.isNak(byteArrayOf(0x15.toByte())))
    }

    @Test
    fun `test CompositeAckNakRule with vararg constructor`() {
        val rule1 = SimpleByteAckNakRule(0x06.toByte(), 0x15.toByte())
        val rule2 = SimpleByteAckNakRule(0xAA.toByte(), 0x55.toByte())

        // 使用vararg构造函数
        val composite = CompositeAckNakRule(rule1, rule2)

        // 必须同时满足两个规则（对于ACK：第一个字节既要是0x06又要是0xAA - 不可能）
        assertFalse(composite.isAck(byteArrayOf(0x06.toByte())))
        assertFalse(composite.isNak(byteArrayOf(0x15.toByte())))
    }

    @Test
    fun `test AnyMatchAckNakRule with vararg constructor`() {
        val rule1 = SimpleByteAckNakRule(0x06.toByte(), 0x15.toByte())
        val rule2 = SimpleByteAckNakRule(0xAA.toByte(), 0x55.toByte())

        // 使用vararg构造函数
        val anyMatch = AnyMatchAckNakRule(rule1, rule2)

        // 任一规则匹配即可
        assertTrue(anyMatch.isAck(byteArrayOf(0x06.toByte())))  // 匹配rule1
        assertTrue(anyMatch.isAck(byteArrayOf(0xAA.toByte())))  // 匹配rule2
        assertTrue(anyMatch.isNak(byteArrayOf(0x15.toByte())))  // 匹配rule1
        assertTrue(anyMatch.isNak(byteArrayOf(0x55.toByte())))  // 匹配rule2
    }
}