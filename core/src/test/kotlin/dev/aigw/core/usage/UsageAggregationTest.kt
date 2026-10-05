package dev.aigw.core.usage

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.util.startOfDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归「累计显示的是今日的数据」：累计与今日必须是两套独立持久化的聚合，
 * 不能从有容量上限的调用记录里现算——记录被淘汰或过期清理后，
 * 现算出来的「累计」就只剩最近几条，和今日混成一套数。
 */
class UsageAggregationTest {

    private val day = 24L * 3600 * 1000

    private fun record(id: String, at: Long, totalTokens: Long = 3) = CallRecord(
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
        completionTokens = totalTokens - 1,
        totalTokens = totalTokens,
        durationMillis = 5,
        error = "",
        requestBody = "req",
        responseBody = "resp",
    )

    @Test
    fun `累计与今日是两套数据`() {
        val log = CallLogStore(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        log.add(record("today-1", now))
        log.add(record("today-2", now, totalTokens = 7))
        log.add(record("yesterday", now - day))

        assertEquals(3L, log.totalStats().requests, "累计含跨天前的记录")
        assertEquals(13L, log.totalStats().totalTokens)
        assertEquals(2L, log.todayStats(now).requests, "今日只算今天的记录")
        assertEquals(10L, log.todayStats(now).totalTokens)
    }

    @Test
    fun `容量淘汰旧记录后累计依然完整`() {
        val store = InMemoryKeyValueStore()
        val now = System.currentTimeMillis()
        val writer = CallLogStore(store, capacity = 1)
        writer.add(record("old", now - day))
        writer.add(record("new", now))

        assertEquals(1, writer.list().size, "内存里只剩最新一条")
        assertEquals(2L, writer.totalStats().requests, "累计不受容量淘汰影响")
        assertEquals(6L, writer.totalStats().totalTokens)
    }

    @Test
    fun `聚合随存储持久化并在重启后读回`() {
        val store = InMemoryKeyValueStore()
        val now = System.currentTimeMillis()
        val writer = CallLogStore(store, capacity = 1)
        writer.add(record("old", now - day))
        writer.add(record("new", now))

        val reader = CallLogStore(store, capacity = 1)
        assertEquals(2L, reader.totalStats().requests)
        assertEquals(1L, reader.todayStats(now).requests)
    }

    @Test
    fun `跨天后今日归零且旧日聚合被清理`() {
        val store = InMemoryKeyValueStore()
        val log = CallLogStore(store)
        val todayStart = startOfDay(System.currentTimeMillis())
        log.add(record("a", todayStart + 3_600_000))
        log.add(record("b", todayStart + day + 3_600_000))

        assertEquals(1, store.keys("usage/day/").size, "只保留新一天的聚合键")
        assertEquals(1L, log.todayStats(todayStart + day + 3_600_000).requests, "今日只算新一天的一条")
        assertEquals(2L, log.totalStats().requests, "累计含跨天前的记录")
    }

    @Test
    fun `删除单条记录会从统计中扣除`() {
        val log = CallLogStore(InMemoryKeyValueStore())
        val now = System.currentTimeMillis()
        log.add(record("a", now))
        log.add(record("b", now, totalTokens = 7))

        log.delete("b")

        assertEquals(1L, log.totalStats().requests)
        assertEquals(3L, log.totalStats().totalTokens)
        assertEquals(1L, log.todayStats(now).requests)
    }

    @Test
    fun `清空调用记录会一并清零聚合`() {
        val store = InMemoryKeyValueStore()
        val log = CallLogStore(store)
        val now = System.currentTimeMillis()
        log.add(record("a", now))
        log.add(record("b", now - day))

        log.clear()

        assertEquals(0L, log.totalStats().requests)
        assertEquals(0L, log.todayStats(now).requests)
        assertTrue(store.keys("usage/").isEmpty(), "聚合键也要从存储里删掉")
    }

    @Test
    fun `无聚合键时从已有记录兜底重建`() {
        val store = InMemoryKeyValueStore()
        val writer = CallLogStore(store)
        val now = System.currentTimeMillis()
        writer.add(record("a", now))
        // 模拟老版本升级：老版本存储里没有 usage/ 聚合键
        for (key in store.keys("usage/")) store.delete(key)

        val reader = CallLogStore(store)
        assertEquals(1L, reader.totalStats().requests, "老数据按已载入记录兜底")
        assertEquals(1L, reader.todayStats(now).requests)
    }
}
