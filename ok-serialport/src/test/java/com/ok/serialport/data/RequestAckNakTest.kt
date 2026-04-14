package com.ok.serialport.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Request ACK/NAK 单元测试
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class RequestAckNakTest {

    private lateinit var request: Request

    @Before
    fun setup() {
        request = Request(byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01))
    }

    // ==================== ackNakConfig 配置测试 ====================

    @Test
    fun `test ackNakConfig with builder sets config correctly`() {
        val config = AckNakConfig.Builder()
            .ackRule { data -> data[0] == 0x06.toByte() }
            .nakRule { data -> data[0] == 0x15.toByte() }
            .ackRetryCount(3)
            .build()

        request.ackNakConfig(config)

        assertNotNull(request.ackNakConfig)
        assertEquals(3, request.ackRetryCount)
        assertEquals(config, request.ackNakConfig)
    }

    @Test
    fun `test ackNakConfig with convenience method`() {
        val result = request.ackNakConfig(
            ackRule = { data -> data[0] == 0x06.toByte() },
            nakRule = { data -> data[0] == 0x15.toByte() },
            waitData = true,
            ackTimeout = 1000L,
            ackRetryCount = 2,
            dataTimeout = 2000L
        )

        assertSame(request, result)  // 验证链式调用返回自身
        assertNotNull(request.ackNakConfig)
        assertEquals(2, request.ackRetryCount)
        assertEquals(2000L, request.getDataTimeout())
    }

    // ==================== ACK重试次数管理测试 ====================

    @Test
    fun `test deductAckRetryCount decrements count`() {
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .ackRetryCount(3)
                .build()
        )

        assertEquals(3, request.ackRetryCount)

        // 第一次扣减，还有剩余
        val result1 = request.deductAckRetryCount()
        assertFalse(result1)
        assertEquals(2, request.ackRetryCount)

        // 第二次扣减，还有剩余
        val result2 = request.deductAckRetryCount()
        assertFalse(result2)
        assertEquals(1, request.ackRetryCount)

        // 第三次扣减，还有剩余
        val result3 = request.deductAckRetryCount()
        assertFalse(result3)
        assertEquals(0, request.ackRetryCount)

        // 第四次扣减，已耗尽
        val result4 = request.deductAckRetryCount()
        assertTrue(result4)
        assertEquals(0, request.ackRetryCount)
    }

    @Test
    fun `test deductAckRetryCount with zero initial count`() {
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .ackRetryCount(0)
                .build()
        )

        assertEquals(0, request.ackRetryCount)
        val result = request.deductAckRetryCount()
        assertTrue(result)  // 立即耗尽
    }

    @Test
    fun `test deductAckRetryCount never goes negative`() {
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .ackRetryCount(1)
                .build()
        )

        // 扣减到0
        request.deductAckRetryCount()
        assertEquals(0, request.ackRetryCount)

        // 继续扣减，应该保持为0
        repeat(5) {
            request.deductAckRetryCount()
        }
        assertEquals(0, request.ackRetryCount)
    }

    // ==================== 超时时间获取测试 ====================

    @Test
    fun `test getAckTimeout returns config value when set`() {
        request.timeout(5000L)  // 默认超时5秒
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .ackTimeout(1000L)  // ACK超时1秒
                .build()
        )

        assertEquals(1000L, request.getAckTimeout())
    }

    @Test
    fun `test getAckTimeout falls back to request timeout when not set`() {
        request.timeout(5000L)
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                // 不设置ackTimeout
                .build()
        )

        assertEquals(5000L, request.getAckTimeout())  // 使用request的timeout
    }

    @Test
    fun `test getDataTimeout returns config value when set`() {
        request.timeout(5000L)
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .dataTimeout(2000L)
                .build()
        )

        assertEquals(2000L, request.getDataTimeout())
    }

    @Test
    fun `test getDataTimeout falls back to request timeout when not set`() {
        request.timeout(5000L)
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                // 不设置dataTimeout
                .build()
        )

        assertEquals(5000L, request.getDataTimeout())  // 使用request的timeout
    }

    // ==================== 链式调用测试 ====================

    @Test
    fun `test chaining with ackNakConfig`() {
        val result = request
            .tag("test-request")
            .timeout(3000L)
            .ackNakConfig(
                AckNakConfig.Builder()
                    .ackRule { data -> data[0] == 0x06.toByte() }
                    .build()
            )
            .blocking()

        assertSame(request, result)
        assertEquals("test-request", request.tag)
        assertEquals(3000L, request.timeout)
        assertTrue(request.isBlock)
        assertNotNull(request.ackNakConfig)
    }

    // ==================== 无配置情况测试 ====================

    @Test
    fun `test getAckTimeout without config returns request timeout`() {
        request.timeout(5000L)
        // 不配置ackNakConfig

        assertEquals(5000L, request.getAckTimeout())
    }

    @Test
    fun `test getDataTimeout without config returns request timeout`() {
        request.timeout(5000L)
        // 不配置ackNakConfig

        assertEquals(5000L, request.getDataTimeout())
    }

    // ==================== 边界值测试 ====================

    @Test
    fun `test getAckTimeout with negative ackTimeout uses request timeout`() {
        request.timeout(5000L)
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .ackTimeout(-1L)  // 负数表示使用默认
                .build()
        )

        assertEquals(5000L, request.getAckTimeout())
    }

    @Test
    fun `test getDataTimeout with negative dataTimeout uses request timeout`() {
        request.timeout(5000L)
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .dataTimeout(-1L)  // 负数表示使用默认
                .build()
        )

        assertEquals(5000L, request.getDataTimeout())
    }

    @Test
    fun `test ackNakConfig copies dataTimeout from config`() {
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .dataTimeout(3000L)
                .build()
        )

        assertEquals(3000L, request.getDataTimeout())
    }
}