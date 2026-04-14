package com.ok.serialport.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ResponseProcess 分阶段处理单元测试
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class ResponseProcessPhaseTest {

    private lateinit var process: ResponseProcess

    @Before
    fun setup() {
        process = ResponseProcess()
    }

    // ==================== 初始状态测试 ====================

    @Test
    fun `test initial phase is WAITING_ACK`() {
        assertEquals(RequestPhase.WAITING_ACK, process.currentPhase)
        assertFalse(process.ackReceived)
        assertFalse(process.waitingForData)
        assertEquals(0, process.ackReceiveTime)
    }

    // ==================== 进入数据阶段测试 ====================

    @Test
    fun `test enterDataPhase sets correct state`() {
        process.enterDataPhase(dataCount = 3, dataRetry = 2)

        assertEquals(RequestPhase.WAITING_DATA, process.currentPhase)
        assertTrue(process.ackReceived)
        assertTrue(process.waitingForData)
        assertTrue(process.ackReceiveTime > 0)
        assertEquals(3, process.dataResponseCount)
        assertEquals(2, process.dataRetryCount)
    }

    @Test
    fun `test enterDataPhase with default values`() {
        process.enterDataPhase()

        assertEquals(RequestPhase.WAITING_DATA, process.currentPhase)
        assertEquals(1, process.dataResponseCount)  // 默认1次
        assertEquals(0, process.dataRetryCount)     // 默认0次
    }

    // ==================== 标记完成测试 ====================

    @Test
    fun `test markCompleted sets correct state`() {
        process.enterDataPhase()
        process.markCompleted()

        assertEquals(RequestPhase.COMPLETED, process.currentPhase)
        assertFalse(process.waitingForData)
    }

    // ==================== 重置到ACK阶段测试 ====================

    @Test
    fun `test resetToAckPhase clears data phase state`() {
        // 先进入数据阶段
        process.enterDataPhase(dataCount = 3, dataRetry = 2)

        // 然后重置
        process.resetToAckPhase()

        assertEquals(RequestPhase.WAITING_ACK, process.currentPhase)
        assertFalse(process.ackReceived)
        assertFalse(process.waitingForData)
        assertEquals(0, process.ackReceiveTime)
        // dataResponseCount和dataRetryCount保持原值（不影响下次使用）
    }

    // ==================== 数据响应次数扣减测试 ====================

    @Test
    fun `test deductDataCount decrements correctly`() {
        process.enterDataPhase(dataCount = 3)

        // 第一次扣减
        assertFalse(process.deductDataCount())
        assertEquals(2, process.dataResponseCount)

        // 第二次扣减
        assertFalse(process.deductDataCount())
        assertEquals(1, process.dataResponseCount)

        // 第三次扣减，耗尽
        assertTrue(process.deductDataCount())
        assertEquals(0, process.dataResponseCount)
    }

    @Test
    fun `test deductDataCount with MAX_COUNT never depletes`() {
        process.enterDataPhase(dataCount = ResponseProcess.MAX_COUNT)

        // 扣减多次也不应该耗尽
        repeat(100) {
            assertFalse(process.deductDataCount())
        }
        assertTrue(process.dataResponseCount > 0)
    }

    @Test
    fun `test deductDataCount never goes negative`() {
        process.enterDataPhase(dataCount = 1)

        // 扣减到0
        process.deductDataCount()
        assertEquals(0, process.dataResponseCount)

        // 继续扣减，应该保持为0并返回true（已耗尽）
        repeat(5) {
            assertTrue(process.deductDataCount())
        }
        assertEquals(0, process.dataResponseCount)
    }

    // ==================== 数据重试次数扣减测试 ====================

    @Test
    fun `test deductDataRetryCount decrements correctly`() {
        process.enterDataPhase(dataRetry = 3)

        // 第一次扣减
        assertFalse(process.deductDataRetryCount())
        assertEquals(2, process.dataRetryCount)

        // 第二次扣减
        assertFalse(process.deductDataRetryCount())
        assertEquals(1, process.dataRetryCount)

        // 第三次扣减
        assertFalse(process.deductDataRetryCount())
        assertEquals(0, process.dataRetryCount)

        // 第四次扣减，耗尽
        assertTrue(process.deductDataRetryCount())
        assertEquals(0, process.dataRetryCount)
    }

    @Test
    fun `test deductDataRetryCount with zero returns depleted immediately`() {
        process.enterDataPhase(dataRetry = 0)

        assertTrue(process.deductDataRetryCount())
    }

    @Test
    fun `test deductDataRetryCount never goes negative`() {
        process.enterDataPhase(dataRetry = 1)

        // 扣减到0
        process.deductDataRetryCount()
        assertEquals(0, process.dataRetryCount)

        // 继续扣减，应该保持为0并返回true
        repeat(5) {
            assertTrue(process.deductDataRetryCount())
        }
        assertEquals(0, process.dataRetryCount)
    }

    // ==================== 数据超时检查测试 ====================

    @Test
    fun `test isDataTimeout returns true when timeout exceeded`() {
        process.enterDataPhase()
        // 等待一小段时间
        Thread.sleep(50)

        assertTrue(process.isDataTimeout(30L))  // 30ms超时
    }

    @Test
    fun `test isDataTimeout returns false when not timeout`() {
        process.enterDataPhase()

        assertFalse(process.isDataTimeout(5000L))  // 5秒超时
    }

    @Test
    fun `test isDataTimeout returns false when ackReceiveTime is zero`() {
        // 未进入数据阶段
        assertFalse(process.isDataTimeout(100L))
    }

    @Test
    fun `test isDataTimeout returns false when timeout is zero or negative`() {
        process.enterDataPhase()

        assertFalse(process.isDataTimeout(0))
        assertFalse(process.isDataTimeout(-1))
    }

    // ==================== 完整流程状态转换测试 ====================

    @Test
    fun `test complete flow state transitions`() {
        // 初始：WAITING_ACK
        assertEquals(RequestPhase.WAITING_ACK, process.currentPhase)

        // 收到ACK，进入数据阶段
        process.enterDataPhase(dataCount = 2, dataRetry = 1)
        assertEquals(RequestPhase.WAITING_DATA, process.currentPhase)
        assertTrue(process.ackReceived)

        // 第一个数据响应
        process.deductDataCount()
        assertEquals(RequestPhase.WAITING_DATA, process.currentPhase)  // 还在等待数据

        // 第二个数据响应，耗尽
        process.deductDataCount()
        // 假设此时调用markCompleted
        process.markCompleted()
        assertEquals(RequestPhase.COMPLETED, process.currentPhase)
        assertFalse(process.waitingForData)
    }

    @Test
    fun `test ack retry flow state transitions`() {
        // 初始：WAITING_ACK
        assertEquals(RequestPhase.WAITING_ACK, process.currentPhase)

        // 收到NAK，需要重试，重置到ACK阶段
        process.enterDataPhase()  // 模拟之前收到过ACK
        process.resetToAckPhase()

        assertEquals(RequestPhase.WAITING_ACK, process.currentPhase)
        assertFalse(process.ackReceived)
        assertFalse(process.waitingForData)
    }

    // ==================== deductCount兼容测试 ====================

    @Test
    fun `test deductCount works independently`() {
        // deductCount是原有的方法，应该独立工作
        process.count = 3

        assertFalse(process.deductCount())
        assertEquals(2, process.count)

        assertFalse(process.deductCount())
        assertEquals(1, process.count)

        assertTrue(process.deductCount())
        assertEquals(0, process.count)
    }

    @Test
    fun `test deductCount with MAX_COUNT never depletes`() {
        process.count = ResponseProcess.MAX_COUNT

        // 扣减多次也不应该耗尽
        repeat(100) {
            assertFalse(process.deductCount())
        }
        assertTrue(process.count > 0)
    }
}