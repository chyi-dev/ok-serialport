package com.ok.serialport.exception

import org.junit.Assert.*
import org.junit.Test

/**
 * ACK/NAK 异常类单元测试
 *
 * @author Leyi
 * @date 2025/1/10 16:35
 */
class AckNakExceptionTest {

    // ==================== AckTimeoutException 测试 ====================

    @Test
    fun `test AckTimeoutException with default message`() {
        val exception = AckTimeoutException()

        assertEquals("ACK响应超时", exception.message)
        assertTrue(exception is Exception)
    }

    @Test
    fun `test AckTimeoutException with custom message`() {
        val customMessage = "自定义ACK超时消息"
        val exception = AckTimeoutException(customMessage)

        assertEquals(customMessage, exception.message)
    }

    @Test
    fun `test AckTimeoutException is throwable`() {
        val exception = AckTimeoutException("测试")

        assertTrue(exception is Throwable)
        assertNull(exception.cause)
    }

    // ==================== DataTimeoutException 测试 ====================

    @Test
    fun `test DataTimeoutException with default message`() {
        val exception = DataTimeoutException()

        assertEquals("数据响应超时", exception.message)
        assertTrue(exception is Exception)
    }

    @Test
    fun `test DataTimeoutException with custom message`() {
        val customMessage = "自定义数据超时消息"
        val exception = DataTimeoutException(customMessage)

        assertEquals(customMessage, exception.message)
    }

    @Test
    fun `test DataTimeoutException is throwable`() {
        val exception = DataTimeoutException("测试")

        assertTrue(exception is Throwable)
        assertNull(exception.cause)
    }

    // ==================== 异常区分测试 ====================

    @Test
    fun `test AckTimeoutException and DataTimeoutException are different types`() {
        val ackException = AckTimeoutException()
        val dataException = DataTimeoutException()

        // 两者都是Exception的子类
        assertTrue(ackException is Exception)
        assertTrue(dataException is Exception)

        // 但类型不同（类型检查）
        assertNotEquals(ackException.javaClass, dataException.javaClass)
        assertFalse(ackException.javaClass.isAssignableFrom(dataException.javaClass))
        assertFalse(dataException.javaClass.isAssignableFrom(ackException.javaClass))

        // 消息不同
        assertNotEquals(ackException.message, dataException.message)
    }

    // ==================== 异常抛出和捕获测试 ====================

    @Test
    fun `test can catch AckTimeoutException specifically`() {
        var caught = false
        var message = ""

        try {
            throw AckTimeoutException("测试ACK超时")
        } catch (e: AckTimeoutException) {
            caught = true
            message = e.message ?: ""
        } catch (e: Exception) {
            fail("应该被AckTimeoutException捕获")
        }

        assertTrue(caught)
        assertEquals("测试ACK超时", message)
    }

    @Test
    fun `test can catch DataTimeoutException specifically`() {
        var caught = false
        var message = ""

        try {
            throw DataTimeoutException("测试数据超时")
        } catch (e: DataTimeoutException) {
            caught = true
            message = e.message ?: ""
        } catch (e: Exception) {
            fail("应该被DataTimeoutException捕获")
        }

        assertTrue(caught)
        assertEquals("测试数据超时", message)
    }

    @Test
    fun `test can catch both as Exception`() {
        val exceptions = listOf(
            AckTimeoutException(),
            DataTimeoutException()
        )

        exceptions.forEach { exception ->
            var caught = false
            try {
                throw exception
            } catch (e: Exception) {
                caught = true
            }
            assertTrue("应该能捕获 ${exception.javaClass.simpleName}", caught)
        }
    }

    // ==================== 继承关系测试 ====================

    @Test
    fun `test exception inheritance hierarchy`() {
        val ackException = AckTimeoutException()
        val dataException = DataTimeoutException()

        // 都是Exception的子类
        assertTrue(ackException is Exception)
        assertTrue(dataException is Exception)

        // 都是Throwable的子类
        assertTrue(ackException is Throwable)
        assertTrue(dataException is Throwable)

        // 不是RuntimeException（需要显式声明throws或try-catch）
        assertFalse(ackException.javaClass == RuntimeException::class.java)
        assertFalse(dataException.javaClass == RuntimeException::class.java)
    }

    // ==================== 异常相等性测试 ====================

    @Test
    fun `test exceptions with same message are not equal`() {
        val exception1 = AckTimeoutException("相同消息")
        val exception2 = AckTimeoutException("相同消息")

        // 即使消息相同，也是不同的对象
        assertNotSame(exception1, exception2)
        assertNotEquals(exception1, exception2)
    }

    @Test
    fun `test exception message consistency`() {
        val message = "测试消息"
        val exception = AckTimeoutException(message)

        // 多次获取消息应该一致
        assertEquals(message, exception.message)
        assertEquals(message, exception.message)
        assertEquals(message, exception.toString().substringAfter(": ").trim())
    }
}