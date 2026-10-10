package dev.aigw.core.usage

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「数据管理」的核心逻辑：保留天数、过期清理、内容截断、存储统计。
 *
 * 重点回归两件事：
 * 1) 清理必须覆盖**未载入内存**的记录（`load()` 有容量上限）；
 * 2) 保留天数越界要被夹回合法区间，不能出现「0 天全删」或「99999 天永不清理」。
 */
class StorageMaintenanceTest {

    private val day = 24L * 3600 * 1000

    private fun record(id: String, at: Long, request: String = "req", response: String = "resp", raw: String = "") = CallRecord(
        id = id,
        startedAtMillis = at,
        providerId = "trae",
        model = "glm-5.3",
        accountUid = "u1",
        accountNickname = "昵称",
        streaming = false,
        status = CallStatus.SUCCESS,
        httpStatus = 200,
        promptTokens = 1,
        completionTokens = 2,
        totalTokens = 3,
        durationMillis = 5,
        error = "",
        requestBody = request,
        responseBody = response,
        rawResponse = raw,
    )

    // ------------------------------------------------------------------ 保留天数

    @Test
    fun `保留天数默认三十天并夹在合法区间`() {
        assertEquals(30, GatewaySettings().logRetentionDays)
        assertEquals(1, GatewaySettings.clampRetentionDays(0))
        assertEquals(1, GatewaySettings.clampRetentionDays(-5))
        assertEquals(3650, GatewaySettings.clampRetentionDays(99999))
        assertEquals(30, GatewaySettings.clampRetentionDays(30))
    }

    @Test
    fun `保留天数会被持久化并读回`() {
        val json = GatewaySettings(logRetentionDays = 7).toJson().toString()
        assertEquals(7, GatewaySettings.fromJson(json).logRetentionDays)

        // 存量配置没有该字段时回落到默认值，不能读成 0（否则一保存就清库）
        val legacy = GatewaySettings().toJson().apply { remove("logRetentionDays") }.toString()
        assertEquals(30, GatewaySettings.fromJson(legacy).logRetentionDays)
    }

    // ------------------------------------------------------------------ 局域网暴露默认值

    @Test
    fun `exposeLan 默认关闭仅本机`() {
        assertEquals(false, GatewaySettings().exposeLan, "新默认应只绑 127.0.0.1")
        // 字段缺失（老配置/首次运行）才落到新默认
        val legacy = GatewaySettings().toJson().apply { remove("exposeLan") }.toString()
        assertEquals(false, GatewaySettings.fromJson(legacy).exposeLan)
    }

    @Test
    fun `老用户已存的 exposeLan 值不会被新默认覆盖`() {
        // 之前存过 true 的用户读回必须还是 true，不能被新默认 false 静默改掉
        val storedTrue = GatewaySettings(exposeLan = true).toJson().toString()
        assertEquals(true, GatewaySettings.fromJson(storedTrue).exposeLan)

        val storedFalse = GatewaySettings(exposeLan = false).toJson().toString()
        assertEquals(false, GatewaySettings.fromJson(storedFalse).exposeLan)
    }

    // ------------------------------------------------------------------ 过期清理

    @Test
    fun `只清理早于截止时间的记录`() {
        val store = InMemoryKeyValueStore()
        val log = CallLogStore(store)
        val now = 1_800_000_000_000L
        log.add(record("old", now - 40 * day))
        log.add(record("fresh", now - 3 * day))

        assertEquals(1, log.purgeOlderThan(now - 30 * day))
        assertEquals(listOf("fresh"), log.list().map { it.id })
        assertTrue(store.keys(CallLogStore.PREFIX).none { it.endsWith("old") }, "存储里的过期键也须删除")
    }

    @Test
    fun `清理能覆盖超出内存容量的旧记录`() {
        val store = InMemoryKeyValueStore()
        val now = 1_800_000_000_000L
        // 默认容量下写入 3 条过期记录
        val writer = CallLogStore(store)
        writer.add(record("a", now - 100 * day))
        writer.add(record("b", now - 90 * day))
        writer.add(record("c", now - 80 * day))
        assertEquals(3, writer.storedCount())

        // 新实例容量只有 1，内存里只载入 1 条，但清理必须把 3 条都删掉
        val reader = CallLogStore(store, capacity = 1)
        assertEquals(1, reader.list().size)
        assertEquals(3, reader.purgeOlderThan(now - 30 * day))
        assertEquals(0, reader.storedCount())
    }

    @Test
    fun `引擎按设置的保留天数清理`() {
        val store = InMemoryKeyValueStore()
        val now = 1_800_000_000_000L
        val engine = GatewayEngine(store, nowMillis = { now })
        engine.callLogStore.add(record("old", now - 10 * day))
        engine.callLogStore.add(record("recent", now - 2 * day))

        engine.updateSettings(engine.settings().copy(logRetentionDays = 5))
        assertEquals(1, engine.purgeExpiredRecords())
        assertEquals(listOf("recent"), engine.callLogStore.list().map { it.id })

        // 放宽到 30 天后已无可清理项
        engine.updateSettings(engine.settings().copy(logRetentionDays = 30))
        assertEquals(0, engine.purgeExpiredRecords())
    }

    @Test
    fun `自动清理每天只跑一次，跨天后才再清`() {
        val store = InMemoryKeyValueStore()
        var now = 1_800_000_000_000L
        val engine = GatewayEngine(store, nowMillis = { now })
        engine.updateSettings(engine.settings().copy(logRetentionDays = 1))
        // 构造时已清过一次，同一天内新增的过期记录不会再被自动清
        engine.callLogStore.add(record("old", now - 100 * day))

        engine.autoPurgeIfDue()
        assertEquals(1, engine.callLogStore.storedCount(), "同一天内不应重复清理")

        // 跨一天后再清，此时应把过期记录删掉
        now += day
        engine.autoPurgeIfDue()
        assertEquals(0, engine.callLogStore.storedCount(), "跨天后应清理过期记录")
    }

    @Test
    fun `手动清理与自动清理互不影响`() {
        val store = InMemoryKeyValueStore()
        val now = 1_800_000_000_000L
        val engine = GatewayEngine(store, nowMillis = { now })
        engine.updateSettings(engine.settings().copy(logRetentionDays = 1))
        engine.callLogStore.add(record("old", now - 100 * day))

        // 「清理过期记录」按钮是用户显式动作，不受每日一次的限制
        assertEquals(1, engine.purgeExpiredRecords())
        assertEquals(0, engine.callLogStore.storedCount())
    }

    // ------------------------------------------------------------------ 内容截断

    @Test
    fun `截断只影响超长内容且不丢记录`() {
        val store = InMemoryKeyValueStore()
        val log = CallLogStore(store)
        val long = "x".repeat(5_000)
        log.add(record("long", 1L, request = long, response = long))
        log.add(record("short", 2L, request = "tiny", response = "tiny"))

        val (changed, saved) = log.truncateFields(1_000)
        assertEquals(1, changed, "只有超长那条该被截")
        // 释放量 = 2 × (5000 - 1000 - 截断标记长度)
        assertTrue(saved > 7_900, "释放量应接近两条超长字段之和，实际 $saved")

        val kept = log.list().first { it.id == "long" }
        assertEquals(1_000 + CallLogStore.TRUNCATE_MARK.length, kept.requestBody.length)
        assertTrue(kept.requestBody.endsWith(CallLogStore.TRUNCATE_MARK))
        assertEquals("tiny", log.list().first { it.id == "short" }.responseBody)
        assertEquals(2, log.storedCount(), "截断不能删记录")
    }

    @Test
    fun `清空调用记录会一并删除未载入内存的旧键`() {
        val store = InMemoryKeyValueStore()
        val writer = CallLogStore(store)
        repeat(3) { writer.add(record("r$it", 1_000L + it)) }
        assertEquals(3, writer.storedCount())

        // 容量只有 1 的实例内存里只看到 1 条，但清空必须把存储里的键都删完
        val reader = CallLogStore(store, capacity = 1)
        reader.clear()
        assertEquals(0, reader.storedCount())
        assertEquals(0L, reader.storageChars())
    }

    @Test
    fun `截断同时覆盖请求响应与返回原文`() {
        val store = InMemoryKeyValueStore()
        val log = CallLogStore(store)
        val long = "x".repeat(5_000)
        log.add(record("long", 1L, request = long, response = long, raw = long))

        val (changed, saved) = log.truncateFields(1_000)
        assertEquals(1, changed)
        val kept = log.list().first { it.id == "long" }
        assertEquals(1_000 + CallLogStore.TRUNCATE_MARK.length, kept.rawResponse.length)
        assertTrue(kept.rawResponse.endsWith(CallLogStore.TRUNCATE_MARK))
        assertTrue(saved > 11_900, "三个字段都要计入释放量，实际 $saved")
    }

    @Test
    fun `返回原文随记录持久化并在重启后读回`() {
        val store = InMemoryKeyValueStore()
        CallLogStore(store).add(record("a", 1L, raw = "data: {\"ok\":true}"))

        val reloaded = CallLogStore(store).list().first()
        assertEquals("data: {\"ok\":true}", reloaded.rawResponse)
    }

    @Test
    fun `截断阈值过低时拒绝执行`() {
        val log = CallLogStore(InMemoryKeyValueStore())
        log.add(record("a", 1L, request = "x".repeat(3_000), response = "y".repeat(3_000)))
        val (changed, saved) = log.truncateFields(10)
        assertEquals(0, changed)
        assertEquals(0L, saved)
        assertEquals(3_000, log.list().first().requestBody.length, "应原样保留")
    }

    // ------------------------------------------------------------------ 存储统计

    @Test
    fun `存储统计按前缀归类并算出总量`() {
        val store = InMemoryKeyValueStore()
        store.write("logs/calls/a", "x".repeat(2_000))
        store.write("logs/requests.json", "y".repeat(500))
        store.write("account/trae/u1", "z".repeat(300))

        val report = StorageAudit.audit(store)
        assertEquals(2_800, report.totalChars)
        val calls = report.entries.first { it.name == StorageReport.NAME_CALLS }
        assertEquals(2_000, calls.chars)
        assertEquals(1, calls.items)
        assertEquals(2_000, report.recordsChars)
        // 明细按占用从大到小排，界面直接顺序渲染即可
        assertEquals(StorageReport.NAME_CALLS, report.entries.first().name)
    }

    @Test
    fun `存储统计不会把单键前缀漏掉`() {
        val store = InMemoryKeyValueStore()
        store.write("settings/gateway.json", "s".repeat(120))
        val report = StorageAudit.audit(store)
        val settings = report.entries.first { it.name == StorageReport.NAME_SETTINGS }
        assertEquals(120, settings.chars)
        assertEquals(1, settings.items)
    }

    @Test
    fun `体积文案随量级切换单位`() {
        assertEquals("0 B", StorageAudit.formatSize(0))
        assertEquals("900 B", StorageAudit.formatSize(900))
        assertEquals("1.0 KB", StorageAudit.formatSize(1_024))
        assertEquals("1.20 MB", StorageAudit.formatSize((1.2 * 1024 * 1024).toLong()))
        assertEquals("12.00 MB", StorageAudit.formatSize((12.0 * 1024 * 1024).toLong()))
    }
}
