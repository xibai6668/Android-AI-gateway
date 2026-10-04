package dev.aigw.core.failover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitBreakerTest {

    private var virtualTime = 1_000_000L

    private fun newBreaker(
        threshold: Int = 3,
        openDuration: Long = 60_000L,
        halfOpenSuccess: Int = 2,
    ): ProviderCircuitBreaker {
        val cfg = CircuitBreakerConfig(
            failureThreshold = threshold,
            openDurationMs = openDuration,
            halfOpenSuccessCount = halfOpenSuccess,
        )
        return ProviderCircuitBreaker("test-provider", config = { cfg }, nowMillis = { virtualTime })
    }

    @Test
    fun `连续失败达到阈值进入 OPEN 熔断并在静默期拒绝请求`() {
        val cb = newBreaker(threshold = 3, openDuration = 60_000L)
        assertEquals(BreakerState.CLOSED, cb.currentState())

        cb.recordFailure()
        cb.recordFailure()
        assertEquals(BreakerState.CLOSED, cb.currentState(), "未达阈值保持 CLOSED")
        assertTrue(cb.allowRequest())

        // 第 3 次失败触发熔断
        cb.recordFailure()
        assertEquals(BreakerState.OPEN, cb.currentState(), "达到 3 次失败触发 OPEN 熔断")

        // 60秒静默期内拒绝放行
        assertFalse(cb.allowRequest())

        // 59秒后依然拒绝
        virtualTime += 59_000L
        assertFalse(cb.allowRequest())
    }

    @Test
    fun `熔断期满自动转入 HALF_OPEN 放行探针且连续成功后自愈`() {
        val cb = newBreaker(threshold = 2, openDuration = 60_000L, halfOpenSuccess = 2)
        cb.recordFailure()
        cb.recordFailure()
        assertEquals(BreakerState.OPEN, cb.currentState())

        // 60秒后探针放行，原子转入 HALF_OPEN
        virtualTime += 60_000L
        assertTrue(cb.allowRequest())
        assertEquals(BreakerState.HALF_OPEN, cb.currentState())

        // 第 1 次探针成功，仍保持 HALF_OPEN
        cb.recordSuccess()
        assertEquals(BreakerState.HALF_OPEN, cb.currentState())

        // 第 2 次探针成功达到阈值，自愈回 CLOSED
        cb.recordSuccess()
        assertEquals(BreakerState.CLOSED, cb.currentState())
        assertTrue(cb.allowRequest())
    }

    @Test
    fun `半开状态下探针一旦失败立即重新进入 OPEN 熔断`() {
        val cb = newBreaker(threshold = 2, openDuration = 60_000L, halfOpenSuccess = 2)
        cb.recordFailure()
        cb.recordFailure()

        virtualTime += 60_000L
        assertTrue(cb.allowRequest())
        assertEquals(BreakerState.HALF_OPEN, cb.currentState())

        // 探针调用再次失败，立即重新打回 OPEN
        cb.recordFailure()
        assertEquals(BreakerState.OPEN, cb.currentState())
        assertFalse(cb.allowRequest())
    }

    @Test
    fun `闭合状态下偶尔单次失败后成功清零计数`() {
        val cb = newBreaker(threshold = 3)
        cb.recordFailure()
        cb.recordFailure()
        assertEquals(BreakerState.CLOSED, cb.currentState())

        // 成功一次清零连续失败计数
        cb.recordSuccess()
        cb.recordFailure()
        assertEquals(BreakerState.CLOSED, cb.currentState(), "计数已清零，未触发熔断")
    }

    @Test
    fun `指标统计器准确累加各维度指标与错误分类`() {
        val tracker = ProviderMetricsTracker()
        tracker.record(success = true, durationMs = 120L)
        tracker.record(success = true, durationMs = 80L)
        tracker.record(success = false, durationMs = 200L, errorReason = "rate_limit_429")
        tracker.record(success = false, durationMs = 500L, errorReason = "timeout")

        val snap = tracker.snapshot()
        assertEquals(4L, snap["total"])
        assertEquals(2L, snap["success"])
        assertEquals(2L, snap["failed"])
        assertEquals("50.0%", snap["successRate"])
        assertEquals(225L, snap["avgLatencyMs"])

        @Suppress("UNCHECKED_CAST")
        val errors = snap["errors"] as Map<String, Long>
        assertEquals(1L, errors["rate_limit_429"])
        assertEquals(1L, errors["timeout"])
    }
}
