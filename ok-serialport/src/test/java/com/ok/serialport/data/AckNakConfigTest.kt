package com.ok.serialport.data

import org.junit.Assert.*
import org.junit.Test

/**
 * AckNakConfig 单元测试
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class AckNakConfigTest {

    // ==================== Builder测试 ====================

    @Test
    fun `test builder creates config correctly`() {
        val config = AckNakConfig.Builder()
            .ackRule { data -> data.isNotEmpty() && data[0] == 0x06.toByte() }
            .nakRule { data -> data.isNotEmpty() && data[0] == 0x15.toByte() }
            .waitData(true)
            .ackTimeout(1000L)
            .ackRetryCount(3)
            .dataTimeout(2000L)
            .build()

        assertNotNull(config)
        assertTrue(config.hasAckRule())
        assertTrue(config.hasNakRule())
        assertTrue(config.waitData)
        assertEquals(1000L, config.ackTimeout)
        assertEquals(3, config.ackRetryCount)
        assertEquals(2000L, config.dataTimeout)
    }

    @Test
    fun `test dsl build creates config correctly`() {
        val config = AckNakConfig.build {
            ackRule { data -> data[0] == 0x06.toByte() }
            nakRule { data -> data[0] == 0x15.toByte() }
            waitData(false)
            ackTimeout(500L)
            ackRetryCount(2)
            dataTimeout(1500L)
        }

        assertNotNull(config)
        assertFalse(config.waitData)
        assertEquals(500L, config.ackTimeout)
        assertEquals(2, config.ackRetryCount)
    }

    // ==================== ACK匹配测试 ====================

    @Test
    fun `test isAck returns true when data matches ack rule`() {
        val config = AckNakConfig.Builder()
            .ackRule { data -> data.isNotEmpty() && data[0] == 0x06.toByte() }
            .build()

        val ackData = byteArrayOf(0x06, 0x00, 0x01)
        assertTrue(config.isAck(ackData))
    }

    @Test
    fun `test isAck returns false when data does not match ack rule`() {
        val config = AckNakConfig.Builder()
            .ackRule { data -> data.isNotEmpty() && data[0] == 0x06.toByte() }
            .build()

        val otherData = byteArrayOf(0x15, 0x00, 0x01)
        assertFalse(config.isAck(otherData))
    }

    @Test
    fun `test isAck returns false when ack rule is null`() {
        val config = AckNakConfig.Builder()
            .nakRule { data -> data[0] == 0x15.toByte() }
            .build()

        val data = byteArrayOf(0x06)
        assertFalse(config.isAck(data))
        assertFalse(config.hasAckRule())
    }

    // ==================== NAK匹配测试 ====================

    @Test
    fun `test isNak returns true when data matches nak rule`() {
        val config = AckNakConfig.Builder()
            .nakRule { data -> data.isNotEmpty() && data[0] == 0x15.toByte() }
            .build()

        val nakData = byteArrayOf(0x15, 0x00, 0x01)
        assertTrue(config.isNak(nakData))
    }

    @Test
    fun `test isNak returns false when data does not match nak rule`() {
        val config = AckNakConfig.Builder()
            .nakRule { data -> data.isNotEmpty() && data[0] == 0x15.toByte() }
            .build()

        val otherData = byteArrayOf(0x06, 0x00, 0x01)
        assertFalse(config.isNak(otherData))
    }

    @Test
    fun `test isNak returns false when nak rule is null`() {
        val config = AckNakConfig.Builder()
            .ackRule { data -> data[0] == 0x06.toByte() }
            .build()

        val data = byteArrayOf(0x15)
        assertFalse(config.isNak(data))
        assertFalse(config.hasNakRule())
    }

    // ==================== 复杂匹配规则测试 ====================

    @Test
    fun `test complex ack rule with multiple conditions`() {
        val config = AckNakConfig.Builder()
            .ackRule { data ->
                data.size >= 3 &&
                        data[0] == 0xAA.toByte() &&
                        data[1] == 0x55.toByte() &&
                        data[2] == 0x06.toByte()
            }
            .build()

        // 匹配：AA 55 06
        val validAck = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x06.toByte(), 0x00)
        assertTrue(config.isAck(validAck))

        // 不匹配：缺少前缀
        val invalidAck1 = byteArrayOf(0x06.toByte(), 0x00, 0x00)
        assertFalse(config.isAck(invalidAck1))

        // 不匹配：数据太短
        val invalidAck2 = byteArrayOf(0xAA.toByte(), 0x55.toByte())
        assertFalse(config.isAck(invalidAck2))
    }

    @Test
    fun `test ack and nak can be distinguished`() {
        val config = AckNakConfig.Builder()
            .ackRule { data -> data.isNotEmpty() && data[0] == 0x06.toByte() }
            .nakRule { data -> data.isNotEmpty() && data[0] == 0x15.toByte() }
            .build()

        val ackData = byteArrayOf(0x06)
        val nakData = byteArrayOf(0x15)
        val otherData = byteArrayOf(0x00)

        // ACK数据应该匹配ACK规则，不匹配NAK规则
        assertTrue(config.isAck(ackData))
        assertFalse(config.isNak(ackData))

        // NAK数据应该匹配NAK规则，不匹配ACK规则
        assertFalse(config.isAck(nakData))
        assertTrue(config.isNak(nakData))

        // 其他数据既不匹配ACK也不匹配NAK
        assertFalse(config.isAck(otherData))
        assertFalse(config.isNak(otherData))
    }

    // ==================== 默认值测试 ====================

    @Test
    fun `test default values`() {
        val config = AckNakConfig.Builder().build()

        assertNull(config.ackRule)
        assertNull(config.nakRule)
        assertTrue(config.waitData)
        assertEquals(-1L, config.ackTimeout)
        assertEquals(0, config.ackRetryCount)
        assertEquals(-1L, config.dataTimeout)
    }

    // ==================== 边界条件测试 ====================

    @Test
    fun `test empty data does not match`() {
        val config = AckNakConfig.Builder()
            .ackRule { data -> data.isNotEmpty() && data[0] == 0x06.toByte() }
            .build()

        val emptyData = byteArrayOf()
        assertFalse(config.isAck(emptyData))
    }

    @Test
    fun `test single byte data matching`() {
        val config = AckNakConfig.Builder()
            .ackRule { data -> data.size == 1 && data[0] == 0x06.toByte() }
            .build()

        val singleAck = byteArrayOf(0x06.toByte())
        assertTrue(config.isAck(singleAck))

        val multipleBytes = byteArrayOf(0x06.toByte(), 0x00)
        assertFalse(config.isAck(multipleBytes))
    }
}