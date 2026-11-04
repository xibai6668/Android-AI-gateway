package dev.aigw.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineWatchdogTest {

    private class Harness(
        probes: MutableList<Boolean> = mutableListOf(),
        restarts: MutableList<Boolean> = mutableListOf(),
        maxFailedTicks: Int = 3,
    ) {
        var probeCalls = 0
        var restartCalls = 0
        var gaveUpCalls = 0
        val probeResults = probes
        val restartResults = restarts

        val watchdog = EngineWatchdog(
            probe = {
                probeCalls += 1
                if (probeResults.isNotEmpty()) probeResults.removeAt(0) else false
            },
            restart = {
                restartCalls += 1
                if (restartResults.isNotEmpty()) restartResults.removeAt(0) else false
            },
            onGaveUp = { gaveUpCalls += 1 },
            maxFailedTicks = maxFailedTicks,
        )
    }

    @Test
    fun `healthy engine is never restarted`() {
        val harness = Harness(probes = mutableListOf(true, true, true, true))

        repeat(4) { assertTrue(harness.watchdog.tick()) }

        assertEquals(4, harness.probeCalls)
        assertEquals(0, harness.restartCalls)
        assertEquals(0, harness.gaveUpCalls)
        assertEquals(0, harness.watchdog.consecutiveFailures)
    }

    @Test
    fun `failed probe restarts engine and recovery resets the counter`() {
        val harness = Harness(
            probes = mutableListOf(false, false, true),
            restarts = mutableListOf(true, true),
        )

        assertTrue(harness.watchdog.tick())          // 探活失败 → 重启成功（还没确认）
        assertEquals(1, harness.watchdog.consecutiveFailures)
        assertTrue(harness.watchdog.tick())          // 探活失败 → 重启成功
        assertEquals(2, harness.watchdog.consecutiveFailures)

        assertTrue(harness.watchdog.tick())          // 恢复
        assertEquals(0, harness.watchdog.consecutiveFailures)
        assertEquals(2, harness.restartCalls)
        assertEquals(0, harness.gaveUpCalls)

        // 恢复之后重新计数：再失败 3 次才放弃
        harness.probeResults.addAll(mutableListOf(false, false, false))
        harness.restartResults.addAll(mutableListOf(false, false, false))
        harness.watchdog.tick()
        harness.watchdog.tick()
        assertEquals(0, harness.gaveUpCalls)
        harness.watchdog.tick()
        assertEquals(1, harness.gaveUpCalls)
    }

    @Test
    fun `unrecoverable engine gives up exactly once and stops probing`() {
        val harness = Harness()   // 所有探活失败、所有重启失败

        repeat(3) { assertFalse(harness.watchdog.tick()) }

        assertEquals(3, harness.probeCalls)
        assertEquals(3, harness.restartCalls)
        assertEquals(1, harness.gaveUpCalls)
        assertTrue(harness.watchdog.gaveUp)

        // 放弃之后不再探活、不再重启、不再回调
        repeat(3) { assertFalse(harness.watchdog.tick()) }
        assertEquals(3, harness.probeCalls)
        assertEquals(3, harness.restartCalls)
        assertEquals(1, harness.gaveUpCalls)
    }

    @Test
    fun `throwing probe and restart count as failures`() {
        var probeCalls = 0
        var gaveUpCalls = 0
        val watchdog = EngineWatchdog(
            probe = {
                probeCalls += 1
                error("probe boom")
            },
            restart = { error("restart boom") },
            onGaveUp = { gaveUpCalls += 1 },
            maxFailedTicks = 2,
        )

        assertFalse(watchdog.tick())
        assertFalse(watchdog.tick())
        assertEquals(2, probeCalls)
        assertEquals(1, gaveUpCalls)
    }

    @Test
    fun `cancel stops the watchdog without giving up`() {
        val harness = Harness(probes = mutableListOf(false))

        harness.watchdog.cancel()
        assertFalse(harness.watchdog.tick())

        assertEquals(0, harness.probeCalls)
        assertEquals(0, harness.restartCalls)
        assertEquals(0, harness.gaveUpCalls)
    }
}
