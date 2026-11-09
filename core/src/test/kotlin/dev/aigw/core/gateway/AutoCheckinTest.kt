package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.startOfDay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 每日自动签到闸门的回归：**用假时钟验证状态机，不依赖真实日期、不需要 Android、不需要真签到**。
 *
 * 这是「今天已签到时如何确认功能跑通」的答案的一半：
 * - 阴性对照臂（上游回「今日已签到」= ok）→ [今日已签到也算完成当天不会再跑]；
 * - 未签到分支、跨天、失败退避、并发、重启持久化 → 全部在假时钟 + 假执行器下覆盖。
 *
 * 时间基准刻意用 [startOfDay] 反推，避免把「+2h 是否跨天」写死成设备时区假设。
 */
class AutoCheckinTest {

    companion object {
        private val BASE = 1_700_000_000_000L
        private val HOUR = 3_600_000L

        /** 当天上午（相对本地 00:00），保证 +1h 仍在同一天。 */
        private val T1 = startOfDay(BASE) + 10 * HOUR
        private val T1_LATER = startOfDay(BASE) + 11 * HOUR
        private val T1_BACKOFF_EXPIRED = startOfDay(BASE) + 13 * HOUR

        /** 次日凌晨，必然与前一天不同自然日。 */
        private val T2 = startOfDay(BASE) + 25 * HOUR

        private val DAY1 = startOfDay(T1)
        private val DAY2 = startOfDay(T2)
    }

    /** 记录执行次数与每次收到的目标，供断言核对「真的只跑了一轮」。 */
    private class Recorder(
        private val results: List<AutoCheckinResult> = listOf(AutoCheckinResult("u1", true, "签到成功")),
        private val gate: CountDownLatch? = null,
    ) {
        val calls = AtomicInteger(0)
        val lastTargets = mutableListOf<String>()
        val execute: (List<String>) -> List<AutoCheckinResult> = { targets ->
            calls.incrementAndGet()
            synchronized(lastTargets) { lastTargets.addAll(targets) }
            gate?.await(5, TimeUnit.SECONDS)
            results
        }
    }

    private fun build(
        store: KeyValueStore = InMemoryKeyValueStore(),
        clock: () -> Long,
        targets: () -> List<String> = { listOf("u1") },
        execute: (List<String>) -> List<AutoCheckinResult>,
        log: (String) -> Unit = {},
        retryIntervalMs: Long = AutoCheckin.DEFAULT_RETRY_INTERVAL_MS,
    ): AutoCheckin<String> = AutoCheckin(
        store = store,
        nowMillis = clock,
        selectTargets = targets,
        execute = execute,
        log = log,
        retryIntervalMs = retryIntervalMs,
    )

    @Test
    fun `时间基准自证 同日与跨天判定成立`() {
        assertEquals(DAY1, startOfDay(T1_LATER), "+1h 应仍在同一自然日")
        assertTrue(DAY2 != DAY1, "次日必须落在不同的自然日")
        assertEquals(DAY2, startOfDay(T2))
    }

    @Test
    fun `首次巡检执行 同日再巡检跳过`() {
        var now = T1
        val rec = Recorder()
        val auto = build(store = InMemoryKeyValueStore(), clock = { now }, execute = rec.execute)

        val first = auto.tick()
        assertEquals(AutoCheckinOutcome.EXECUTED, first.outcome)
        assertEquals(1, first.targetCount)
        assertEquals(1, first.succeeded)
        assertEquals(1, rec.calls.get())
        assertTrue(auto.doneToday())

        val second = auto.tick()
        assertEquals(AutoCheckinOutcome.SKIPPED_ALREADY_DONE, second.outcome, "同一天绝不能重复打上游")
        assertEquals(1, rec.calls.get(), "跳过时不应执行")
    }

    @Test
    fun `假时钟拨到次日 会再执行一次`() {
        var now = T1
        val rec = Recorder()
        val auto = build(clock = { now }, execute = rec.execute)
        auto.tick()

        now = T2
        assertTrue(auto.shouldRun(), "跨天后应重新可跑")
        assertEquals(AutoCheckinOutcome.EXECUTED, auto.tick().outcome)
        assertEquals(2, rec.calls.get())
        assertEquals(DAY2, auto.lastSuccessDay())
    }

    @Test
    fun `今日已签到也算完成 当天不再跑 阴性对照臂`() {
        // 今天用户已经手动签过了：上游回「今日已签到」(ok=true)。
        // 全新安装 / 清台账后第一次巡检仍会真实走一遍链路，回执可核对；
        // 关键是它不会被判成失败而无限重试，也不会把今天再跑第二遍。
        var now = T1
        val logs = mutableListOf<String>()
        val rec = Recorder(results = listOf(AutoCheckinResult("账号A", true, "今日已签到")))
        val auto = build(clock = { now }, execute = rec.execute, log = { logs.add(it) })

        val report = auto.tick()
        assertEquals(AutoCheckinOutcome.EXECUTED, report.outcome)
        assertEquals(1, report.succeeded)
        assertEquals(0, report.failed)
        assertTrue(report.messages.single().contains("今日已签到"), "回执必须透传上游原话")
        assertTrue(logs.any { it.contains("本轮选中 1 个账号") }, "日志里要有选目标的证据")
        assertTrue(logs.any { it.contains("账号A：今日已签到") }, "日志里要有逐条回执")

        now = T1_LATER
        assertEquals(AutoCheckinOutcome.SKIPPED_ALREADY_DONE, auto.tick().outcome)
        assertEquals(1, rec.calls.get())
    }

    @Test
    fun `force 绕过日期闸门但不绕过并发`() {
        var now = T1
        val rec = Recorder()
        val auto = build(clock = { now }, execute = rec.execute)
        auto.tick()
        assertEquals(AutoCheckinOutcome.EXECUTED, auto.tick(force = true).outcome, "试跑应能再次走完整链路")
        assertEquals(2, rec.calls.get())
    }

    @Test
    fun `部分失败不记完成 退避窗口内跳过 过期后重试成功`() {
        var now = T1
        // 同一个执行器：前两轮返回部分失败，之后返回全成功（模拟上游恢复）。
        // 同一个 AutoCheckin 实例 + 同一个 store，才能验证「失败只记 attempt 不记 success」。
        var healthy = false
        val calls = AtomicInteger(0)
        val store = InMemoryKeyValueStore()
        val auto = build(
            store = store,
            clock = { now },
            targets = { listOf("u1", "u2") },
            execute = {
                calls.incrementAndGet()
                if (healthy) {
                    listOf(
                        AutoCheckinResult("u1", true, "今日已签到"),
                        AutoCheckinResult("u2", true, "签到成功"),
                    )
                } else {
                    listOf(
                        AutoCheckinResult("u1", true, "签到成功"),
                        AutoCheckinResult("u2", false, "网络超时"),
                    )
                }
            },
            retryIntervalMs = HOUR,
        )

        val first = auto.tick()
        assertEquals(AutoCheckinOutcome.EXECUTED, first.outcome)
        assertEquals(1, first.succeeded)
        assertEquals(1, first.failed)
        assertFalse(auto.doneToday(), "有一个失败就不能把今天标成已完成")
        assertEquals(null, store.read(AutoCheckin.KEY_LAST_SUCCESS_DAY), "失败不得写完成日")
        assertTrue(store.read(AutoCheckin.KEY_LAST_ATTEMPT_AT) != null, "但必须记尝试时刻以防骚扰")

        // 退避窗口是 1h：+10 分钟仍在窗口内，+1h 整点到期（>= 判定），+3h 必然已过。
        now = T1 + 10 * 60_000L
        assertEquals(AutoCheckinOutcome.SKIPPED_BACKOFF, auto.tick().outcome)
        assertEquals(1, calls.get())
        now = T1_LATER
        assertTrue(auto.shouldRun(), "恰好 1h 时按 >= 判定应视为已到期（边界语义锁死）")

        healthy = true
        now = T1_BACKOFF_EXPIRED
        val retried = auto.tick()
        assertEquals(AutoCheckinOutcome.EXECUTED, retried.outcome, "退避过期后必须真的重试")
        assertEquals(2, retried.succeeded)
        assertEquals(2, calls.get())
        assertTrue(auto.doneToday(), "全成功后才写完成日")
        assertEquals(DAY1, store.read(AutoCheckin.KEY_LAST_SUCCESS_DAY)?.toLong())
    }

    @Test
    fun `没有目标不占退避窗口 加完账号下一拍就能跑`() {
        var now = T1
        var hasAccount = false
        val rec = Recorder()
        val auto = build(
            clock = { now },
            targets = { if (hasAccount) listOf("u1") else emptyList() },
            execute = rec.execute,
            retryIntervalMs = HOUR,
        )

        assertEquals(AutoCheckinOutcome.NO_TARGETS, auto.tick().outcome)
        assertEquals(0, rec.calls.get())
        hasAccount = true
        // 若「没目标」错误地记了 attempt，这里会被退避挡住，测试即红
        assertEquals(AutoCheckinOutcome.EXECUTED, auto.tick().outcome)
        assertEquals(1, rec.calls.get())
    }

    @Test
    fun `执行抛异常记为失败 不记完成`() {
        var now = T1
        val auto = build(
            clock = { now },
            execute = { throw IllegalStateException("上游炸了") },
        )
        val report = auto.tick()
        assertEquals(AutoCheckinOutcome.FAILED, report.outcome)
        assertFalse(auto.doneToday())
        assertTrue(report.messages.single().contains("上游炸了"))
    }

    @Test
    fun `并发五个巡检只有一个真正执行`() {
        // 只锁「上游只被打一轮」这个产品语义，不锁线程交错：
        // 第一个线程跑完后其余线程可能落到 SKIPPED_IN_FLIGHT 或 SKIPPED_ALREADY_DONE，
        // 两者都是「没重复执行」的正确结果，强行要求 4 个 in-flight 会造出 flaky 夹具。
        val startGate = CountDownLatch(1)
        val rec = Recorder()
        val auto = build(clock = { T1 }, execute = rec.execute)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<AutoCheckinOutcome>())
        val threads = (1..5).map {
            Thread {
                startGate.await(5, TimeUnit.SECONDS)
                outcomes.add(auto.tick().outcome)
            }
        }
        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(1, rec.calls.get(), "并发下只能打一轮上游")
        assertEquals(1, outcomes.count { it == AutoCheckinOutcome.EXECUTED })
        assertEquals(5, outcomes.size)
        assertTrue(outcomes.none { it == AutoCheckinOutcome.FAILED })
        assertTrue(outcomes.all {
            it == AutoCheckinOutcome.EXECUTED ||
                it == AutoCheckinOutcome.SKIPPED_IN_FLIGHT ||
                it == AutoCheckinOutcome.SKIPPED_ALREADY_DONE
        })
    }

    @Test
    fun `执行期间的第二轮巡检被 in-flight 拦下`() {
        // 确定性重叠：线程 A 卡在 execute 里，主线程等它真的进去后再调一次 tick。
        // 不依赖 Thread.sleep 猜调度，因此 CI 上不会 flaky。
        val executeGate = CountDownLatch(1)
        val rec = Recorder(gate = executeGate)
        val auto = build(clock = { T1 }, execute = rec.execute)
        var firstOutcome: AutoCheckinOutcome? = null
        val worker = Thread { firstOutcome = auto.tick().outcome }
        worker.start()

        val deadline = System.currentTimeMillis() + 5_000
        while (rec.calls.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, rec.calls.get(), "第一个线程应已进入 execute")
        assertEquals(
            AutoCheckinOutcome.SKIPPED_IN_FLIGHT,
            auto.tick().outcome,
            "上一次还没结束时必须被 CAS 拦下",
        )
        executeGate.countDown()
        worker.join(5_000)

        assertEquals(AutoCheckinOutcome.EXECUTED, firstOutcome)
        assertEquals(1, rec.calls.get())
    }

    @Test
    fun `台账跨实例持久 重建后不重复跑`() {
        val store = InMemoryKeyValueStore()
        var now = T1
        val rec = Recorder()
        val first = build(store = store, clock = { now }, execute = rec.execute)
        first.tick()
        assertEquals(DAY1, store.read(AutoCheckin.KEY_LAST_SUCCESS_DAY)?.toLong())

        // 模拟进程重启：新实例读同一份 store
        val second = build(store = store, clock = { now }, execute = rec.execute)
        assertTrue(second.doneToday())
        assertEquals(AutoCheckinOutcome.SKIPPED_ALREADY_DONE, second.tick().outcome)
        assertEquals(1, rec.calls.get())
    }

    @Test
    fun `reset 清空台账后立即可跑`() {
        var now = T1
        val rec = Recorder()
        val store = InMemoryKeyValueStore()
        val auto = build(store = store, clock = { now }, execute = rec.execute)
        auto.tick()
        auto.reset()
        assertFalse(auto.doneToday())
        assertEquals(AutoCheckinOutcome.EXECUTED, auto.tick().outcome)
        assertEquals(2, rec.calls.get())
    }

    @Test
    fun `shouldRun 是纯内存快路径 不产生执行`() {
        var now = T1
        val rec = Recorder()
        val auto = build(clock = { now }, execute = rec.execute)
        assertTrue(auto.shouldRun())
        auto.tick()
        assertFalse(auto.shouldRun())
        now = T2
        assertTrue(auto.shouldRun())
        assertEquals(1, rec.calls.get(), "shouldRun 本身绝不触发执行")
    }
}
