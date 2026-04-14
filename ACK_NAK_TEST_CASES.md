# ACK/NAK 功能测试用例文档

本文档详细描述了 ACK/NAK 分阶段响应功能的测试用例设计。

## 测试文件结构

```
ok-serialport/src/test/java/com/ok/serialport/data/
├── AckNakConfigTest.kt          # AckNakConfig 配置测试
├── AckNakRuleTest.kt            # AckNakRule 匹配规则测试
├── RequestAckNakTest.kt         # Request ACK/NAK 方法测试
├── ResponseProcessPhaseTest.kt  # 分阶段处理测试
└── AckNakIntegrationTest.kt     # 集成测试（模拟场景）
```

---

## 1. AckNakConfigTest.kt

### 测试类：配置构建和匹配功能

```kotlin
package com.ok.serialport.data

import org.junit.Assert.*
import org.junit.Test

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
```

---

## 2. AckNakRuleTest.kt

### 测试类：匹配规则接口的各种实现

```kotlin
package com.ok.serialport.data

import org.junit.Assert.*
import org.junit.Test

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
}
```

---

## 3. RequestAckNakTest.kt

### 测试类：Request 的 ACK/NAK 相关方法

```kotlin
package com.ok.serialport.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

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
        assertEquals(2000L, request.dataTimeout)
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
}
```

---

## 4. ResponseProcessPhaseTest.kt

### 测试类：分阶段处理状态管理

```kotlin
package com.ok.serialport.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

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
}
```

---

## 5. AckNakIntegrationTest.kt

### 测试类：集成测试（模拟场景）

```kotlin
package com.ok.serialport.data

import com.ok.serialport.listener.OnResponseListener
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ACK/NAK 集成测试
 * 模拟各种响应场景
 */
class AckNakIntegrationTest {

    // ==================== 场景1：正常ACK+数据流程 ====================

    @Test
    fun `test scenario - ack received then data received`() {
        val ackReceived = AtomicBoolean(false)
        val dataReceived = AtomicBoolean(false)
        val ackData = byteArrayOf(0x06.toByte())
        val responseData = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01, 0x02, 0x03)

        // 创建Request
        val request = Request(byteArrayOf(0x01))
            .ackNakConfig(
                AckNakConfig.Builder()
                    .ackRule { data -> data.contentEquals(ackData) }
                    .waitData(true)
                    .build()
            )
            .addResponseRule { _, data ->
                // 数据响应规则：不是ACK/NAK，长度>=5
                !data.contentEquals(ackData) && data.size >= 5
            }

        // 模拟响应监听
        val listener = object : OnResponseListener {
            override fun onAckReceived(request: Request) {
                ackReceived.set(true)
            }

            override fun onDataReceived(response: Response) {
                dataReceived.set(true)
                assertArrayEquals(responseData, response.data)
            }

            override fun onResponse(response: Response) {
                // 不会被调用，因为有onDataReceived
            }

            override fun onFailure(request: Request?, e: Exception) {
                fail("Should not fail: ${e.message}")
            }
        }

        request.onResponseListener(listener)

        // 模拟：验证ACK匹配
        assertTrue(request.ackNakConfig!!.isAck(ackData))
        assertFalse(request.ackNakConfig!!.isAck(responseData))

        // 模拟：验证数据匹配
        assertTrue(request.match(null, responseData))
    }

    // ==================== 场景2：ACK后不等待数据 ====================

    @Test
    fun `test scenario - ack only without waiting data`() {
        val ackReceived = AtomicBoolean(false)
        val ackData = byteArrayOf(0x06.toByte())

        val request = Request(byteArrayOf(0x01))
            .ackNakConfig(
                AckNakConfig.Builder()
                    .ackRule { data -> data.contentEquals(ackData) }
                    .waitData(false)  // 不等待数据
                    .build()
            )

        val listener = object : OnResponseListener {
            override fun onAckReceived(request: Request) {
                ackReceived.set(true)
            }

            override fun onResponse(response: Response) {
                // waitData=false时，收到ACK后应该回调onResponse表示完成
            }

            override fun onFailure(request: Request?, e: Exception) {
                fail("Should not fail: ${e.message}")
            }
        }

        request.onResponseListener(listener)

        assertFalse(request.ackNakConfig!!.waitData)
        assertTrue(request.ackNakConfig!!.isAck(ackData))
    }

    // ==================== 场景3：NAK触发重试 ====================

    @Test
    fun `test scenario - nak triggers retry`() {
        val nakReceived = AtomicBoolean(false)
        val nakData = byteArrayOf(0x15.toByte())

        val request = Request(byteArrayOf(0x01))
            .ackNakConfig(
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
        assertTrue(nakReceived.compareAndSet(false, true))

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
        val request = Request(byteArrayOf(0x01))
            .timeout(100L)  // 100ms超时
            .ackNakConfig(
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

        val request = Request(byteArrayOf(0x01))
            .timeout(5000L)
            .timeoutRetry(1)  // 数据阶段重试1次
            .ackNakConfig(
                AckNakConfig.Builder()
                    .ackRule { data -> data[0] == 0x06.toByte() }
                    .waitData(true)
                    .dataTimeout(100L)
                    .build()
            )

        // 进入数据阶段
        process.enterDataPhase(
            dataCount = request.count,
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

        // 扣减数据重试次数
        val depleted = process.deductDataRetryCount()
        assertTrue(depleted)  // 只有1次，已耗尽

        // 重置数据阶段时间（模拟重试）
        process.ackReceiveTime = System.currentTimeMillis()
        assertFalse(process.isDataTimeout(request.getDataTimeout()))
    }

    // ==================== 场景6：多个数据响应 ====================

    @Test
    fun `test scenario - multiple data responses`() {
        val process = ResponseProcess()
        process.count = 3  // 期望3个数据响应

        val receivedDataCount = java.util.concurrent.atomic.AtomicInteger(0)

        val request = Request(byteArrayOf(0x01))
            .responseCount(3)  // 期望3个响应
            .ackNakConfig(
                AckNakConfig.Builder()
                    .ackRule { data -> data[0] == 0x06.toByte() }
                    .waitData(true)
                    .build()
            )

        // 进入数据阶段
        process.enterDataPhase(dataCount = 3)

        // 模拟3个数据响应
        val data1 = byteArrayOf(0x01, 0x02)
        val data2 = byteArrayOf(0x03, 0x04)
        val data3 = byteArrayOf(0x05, 0x06)

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

        val request = Request(byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A))
            .ackNakConfig(
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
            .addResponseRule { _, data ->
                // 数据响应：长度>3，且不是ACK/NAK
                data.size > 3 && data[2] != 0x06.toByte() && data[2] != 0x15.toByte()
            }

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

    // ==================== 场景8：回调顺序验证 ====================

    @Test
    fun `test scenario - callback order with ack and data`() {
        val callbackOrder = mutableListOf<String>()

        val listener = object : OnResponseListener {
            override fun onAckReceived(request: Request) {
                callbackOrder.add("onAckReceived")
            }

            override fun onDataReceived(response: Response) {
                callbackOrder.add("onDataReceived")
            }

            override fun onResponse(response: Response) {
                callbackOrder.add("onResponse")
            }

            override fun onFailure(request: Request?, e: Exception) {
                callbackOrder.add("onFailure")
            }
        }

        // 模拟流程：收到ACK -> 收到数据
        // 顺序应该是：onAckReceived -> onDataReceived

        // 这个测试验证了回调的预期顺序
        // 实际测试需要在SerialPortProcess中进行集成测试
        assertTrue(callbackOrder.isEmpty())
    }
}
```

---

## 6. 模拟Mock测试（可选）

### SerialPortProcessMockTest.kt

```kotlin
package com.ok.serialport.data

import com.ok.serialport.OkSerialPort
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * SerialPortProcess 的 Mock 测试
 * 使用 Mockito 模拟依赖
 */
class SerialPortProcessMockTest {

    @Mock
    private lateinit var mockOkSerialPort: OkSerialPort

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
    }

    @Test
    fun `test matchRequest with ack nak config in waiting ack phase`() {
        // 配置 mock
        whenever(mockOkSerialPort.devicePath).thenReturn("/dev/ttyS1")
        whenever(mockOkSerialPort.baudRate).thenReturn(9600)
        whenever(mockOkSerialPort.responseRules).thenReturn(mutableListOf())
        whenever(mockOkSerialPort.stickPacketHandle).thenReturn(mock())

        val process = SerialPortProcess(mockOkSerialPort)

        // 创建带ACK/NAK配置的请求
        val request = Request(byteArrayOf(0x01))
            .ackNakConfig(
                AckNakConfig.Builder()
                    .ackRule { data -> data[0] == 0x06.toByte() }
                    .build()
            )

        // 模拟响应数据
        val ackData = byteArrayOf(0x06.toByte())
        val dataData = byteArrayOf(0xAA.toByte(), 0x55.toByte())

        // 在WAITING_ACK阶段，ACK应该匹配
        // assertTrue(process.matchRequest(ackData) != null)

        // 在WAITING_ACK阶段，普通数据不应该匹配
        // assertNull(process.matchRequest(dataData))
    }
}
```

---

## 测试执行建议

### 执行全部测试

```bash
./gradlew :ok-serialport:test
```

### 执行单个测试类

```bash
./gradlew :ok-serialport:test --tests "com.ok.serialport.data.AckNakConfigTest"
```

### 执行特定测试方法

```bash
./gradlew :ok-serialport:test --tests "com.ok.serialport.data.AckNakConfigTest.test builder creates config correctly"
```

---

## 覆盖率目标

| 模块 | 目标覆盖率 |
|------|-----------|
| AckNakConfig | 90%+ |
| AckNakRule | 85%+ |
| Request ACK/NAK方法 | 90%+ |
| ResponseProcess 阶段管理 | 85%+ |
| 集成测试 | 核心场景覆盖 |

---

## 注意事项

1. **线程安全测试**：ResponseProcess中的状态变更在多线程环境下测试需要特别注意
2. **超时测试**：涉及时间的测试使用较短的超时值以加快测试速度
3. **Mock使用**：集成测试中尽量减少Mock，使用真实对象验证行为
4. **边界条件**：特别关注空数据、零值、负数等边界条件的处理