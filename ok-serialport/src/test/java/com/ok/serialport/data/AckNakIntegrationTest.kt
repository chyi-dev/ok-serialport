package com.ok.serialport.data

import com.ok.serialport.listener.OnResponseListener
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ACK/NAK 集成测试
 * 模拟各种响应场景
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class AckNakIntegrationTest {

    private lateinit var request: Request

    @Before
    fun setup() {
        request = Request(byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01))
    }

    // ==================== 场景1：正常ACK+数据流程 ====================

    @Test
    fun `test scenario - ack received then data received`() {
        val ackData = byteArrayOf(0x06.toByte())
        val responseData = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01, 0x02, 0x03)

        // 创建Request
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data.contentEquals(ackData) }
                .waitData(true)
                .build()
        )

        request.addResponseRule(object : ResponseRule {
            override fun match(request: Request?, receive: ByteArray): Boolean {
                // 数据响应规则：不是ACK/NAK，长度>=5
                return !receive.contentEquals(ackData) && receive.size >= 5
            }
        })

        // 模拟响应监听状态追踪
        val listener = object : OnResponseListener {
            val ackReceived = AtomicBoolean(false)
            val dataReceived = AtomicBoolean(false)

            override fun onAckReceived(request: Request) {
                ackReceived.set(true)
            }

            override fun onDataReceived(response: Response) {
                dataReceived.set(true)
            }

            override fun onResponse(response: Response) {}

            override fun onFailure(request: Request?, e: Exception) {
                fail("Should not fail: ${e.message}")
            }
        }

        request.onResponseListener(listener)

        // 验证ACK匹配
        assertTrue(request.ackNakConfig!!.isAck(ackData))
        assertFalse(request.ackNakConfig!!.isAck(responseData))

        // 验证数据匹配
        assertTrue(request.match(request, responseData))
    }

    // ==================== 场景2：ACK后不等待数据 ====================

    @Test
    fun `test scenario - ack only without waiting data`() {
        val ackData = byteArrayOf(0x06.toByte())

        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data.contentEquals(ackData) }
                .waitData(false)  // 不等待数据
                .build()
        )

        assertFalse(request.ackNakConfig!!.waitData)
        assertTrue(request.ackNakConfig!!.isAck(ackData))
    }

    // ==================== 场景3：NAK触发重试 ====================

    @Test
    fun `test scenario - nak triggers retry`() {
        val nakData = byteArrayOf(0x15.toByte())

        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .nakRule { data -> data[0] == 0x15.toByte() }
                .ackRetryCount(2)
                .build()
        )

        assertEquals(2, request.ackRetryCount)

        // 模拟收到NAK，扣减重试次数
        val depleted1 = request.deductAckRetryCount()
        assertFalse(depleted1)
        assertEquals(1, request.ackRetryCount)

        // 再次收到NAK
        val depleted2 = request.deductAckRetryCount()
        assertFalse(depleted2)
        assertEquals(0, request.ackRetryCount)

        // 第三次收到NAK，重试次数耗尽
        val depleted3 = request.deductAckRetryCount()
        assertTrue(depleted3)
        assertTrue(request.ackNakConfig!!.isNak(nakData))
    }

    // ==================== 场景4：ACK超时触发重试 ====================

    @Test
    fun `test scenario - ack timeout triggers retry`() {
        request.timeout(100L)  // 100ms超时
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .ackTimeout(50L)  // 50ms ACK超时
                .ackRetryCount(2)
                .build()
        )

        // 验证超时时间
        assertEquals(50L, request.getAckTimeout())

        // 模拟：设置发送时间（过去的时间）
        request.sendTime = System.currentTimeMillis() - 100  // 100ms前发送

        // 验证超时检测逻辑
        val currentTime = System.currentTimeMillis()
        val isTimeout = request.getAckTimeout() > 0 &&
                request.sendTime > 0 &&
                currentTime - request.sendTime > request.getAckTimeout()

        assertTrue(isTimeout)

        // 扣减重试次数
        val depleted = request.deductAckRetryCount()
        assertFalse(depleted)
        assertEquals(1, request.ackRetryCount)
    }

    // ==================== 场景5：数据超时触发数据重试 ====================

    @Test
    fun `test scenario - data timeout triggers data retry`() {
        val process = ResponseProcess()
        process.count = 3  // 期望3个数据响应

        request.timeout(5000L)
        request.timeoutRetry(1)  // 数据阶段重试1次
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .waitData(true)
                .dataTimeout(100L)
                .build()
        )

        // 进入数据阶段
        process.enterDataPhase(
            dataCount = process.count,
            dataRetry = request.timeoutRetry
        )

        assertEquals(RequestPhase.WAITING_DATA, process.currentPhase)
        assertEquals(3, process.dataResponseCount)
        assertEquals(1, process.dataRetryCount)
        assertEquals(100L, request.getDataTimeout())

        // 等待超过超时时间
        Thread.sleep(150)

        // 验证数据超时
        assertTrue(process.isDataTimeout(request.getDataTimeout()))

        // 扣减数据重试次数 - 第一次，从1减到0，返回false表示扣减成功
        val depleted1 = process.deductDataRetryCount()
        assertFalse(depleted1)  // 扣减成功，还有0次

        // 第二次扣减，已经耗尽，返回true
        val depleted2 = process.deductDataRetryCount()
        assertTrue(depleted2)  // 已耗尽

        // 重置数据阶段时间（模拟重试）
        process.ackReceiveTime = System.currentTimeMillis()
        assertFalse(process.isDataTimeout(request.getDataTimeout()))
    }

    // ==================== 场景6：多个数据响应 ====================

    @Test
    fun `test scenario - multiple data responses`() {
        val process = ResponseProcess()
        val receivedDataCount = AtomicInteger(0)

        request.responseCount(3)  // 期望3个响应
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data[0] == 0x06.toByte() }
                .waitData(true)
                .build()
        )

        // 进入数据阶段
        process.enterDataPhase(dataCount = 3)

        // 模拟3个数据响应

        // 第一个响应
        assertFalse(process.deductDataCount())
        receivedDataCount.incrementAndGet()

        // 第二个响应
        assertFalse(process.deductDataCount())
        receivedDataCount.incrementAndGet()

        // 第三个响应，耗尽
        assertTrue(process.deductDataCount())
        receivedDataCount.incrementAndGet()

        assertEquals(3, receivedDataCount.get())
        assertEquals(0, process.dataResponseCount)
    }

    // ==================== 场景7：复杂ACK/NAK匹配 ====================

    @Test
    fun `test scenario - complex ack nak matching`() {
        // 模拟Modbus-like协议
        // ACK: [设备地址][功能码][ACK标记][CRC...]
        val ackPattern = byteArrayOf(0x01, 0x03, 0x06)
        val nakPattern = byteArrayOf(0x01, 0x03, 0x15)

        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data ->
                    data.size >= 3 &&
                            data[0] == ackPattern[0] &&
                            data[1] == ackPattern[1] &&
                            data[2] == ackPattern[2]
                }
                .nakRule { data ->
                    data.size >= 3 &&
                            data[0] == nakPattern[0] &&
                            data[1] == nakPattern[1] &&
                            data[2] == nakPattern[2]
                }
                .waitData(true)
                .build()
        )

        request.addResponseRule(object : ResponseRule {
            override fun match(request: Request?, receive: ByteArray): Boolean {
                // 数据响应：长度>3，且不是ACK/NAK
                return receive.size > 3 && receive[2] != 0x06.toByte() && receive[2] != 0x15.toByte()
            }
        })

        // 测试ACK匹配
        val ackResponse = byteArrayOf(0x01, 0x03, 0x06, 0x00)
        assertTrue(request.ackNakConfig!!.isAck(ackResponse))
        assertFalse(request.ackNakConfig!!.isNak(ackResponse))

        // 测试NAK匹配
        val nakResponse = byteArrayOf(0x01, 0x03, 0x15, 0x00)
        assertFalse(request.ackNakConfig!!.isAck(nakResponse))
        assertTrue(request.ackNakConfig!!.isNak(nakResponse))

        // 测试数据匹配
        val dataResponse = byteArrayOf(0x01, 0x03, 0x14, 0x00, 0x01, 0x02, 0x03)
        assertFalse(request.ackNakConfig!!.isAck(dataResponse))
        assertFalse(request.ackNakConfig!!.isNak(dataResponse))
        assertTrue(request.match(request, dataResponse))
    }

    // ==================== 场景8：完整流程模拟 ====================

    @Test
    fun `test scenario - complete ack nak data flow simulation`() {
        val ackData = byteArrayOf(0x06.toByte())
        val responseData = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01)

        // 创建带ACK/NAK的request
        request.ackNakConfig(
            AckNakConfig.Builder()
                .ackRule { data -> data.contentEquals(ackData) }
                .waitData(true)
                .ackTimeout(1000L)
                .dataTimeout(2000L)
                .build()
        )

        request.addResponseRule(object : ResponseRule {
            override fun match(request: Request?, receive: ByteArray): Boolean {
                return receive.contentEquals(responseData)
            }
        })

        // 状态追踪
        val callbacks = mutableListOf<String>()
        val listener = object : OnResponseListener {
            override fun onAckReceived(request: Request) {
                callbacks.add("ACK")
            }

            override fun onDataReceived(response: Response) {
                callbacks.add("DATA")
            }

            override fun onResponse(response: Response) {
                callbacks.add("RESPONSE")
            }

            override fun onFailure(request: Request?, e: Exception) {
                callbacks.add("FAILURE: ${e.message}")
            }
        }

        request.onResponseListener(listener)

        // 模拟：第一阶段收到ACK
        assertTrue(request.ackNakConfig!!.isAck(ackData))
        // 第二阶段匹配数据
        assertTrue(request.match(request, responseData))

        // 验证配置完整性
        assertNotNull(request.ackNakConfig)
        assertTrue(request.ackNakConfig!!.waitData)
        assertEquals(1000L, request.ackNakConfig!!.ackTimeout)
        assertEquals(2000L, request.getDataTimeout())
    }

    // ==================== 场景9：错误处理测试 ====================

    @Test
    fun `test scenario - exception handling without ack nak config`() {
        // 不配置ACK/NAK
        val plainRequest = Request(byteArrayOf(0x01))
        assertNull(plainRequest.ackNakConfig)

        // 超时时间应该使用默认值
        assertEquals(plainRequest.timeout, plainRequest.getAckTimeout())
        assertEquals(plainRequest.timeout, plainRequest.getDataTimeout())
    }

    // ==================== 场景10：并发安全测试 ====================

    @Test
    fun `test scenario - concurrent phase transitions`() {
        val process = ResponseProcess()
        val errors = mutableListOf<Exception>()

        // 多线程测试状态转换
        val threads = (1..10).map {
            Thread {
                try {
                    process.enterDataPhase()
                    Thread.sleep(1)
                    process.deductDataCount()
                    process.resetToAckPhase()
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // 不应该有异常
        assertTrue("并发测试不应抛出异常", errors.isEmpty())
    }
}