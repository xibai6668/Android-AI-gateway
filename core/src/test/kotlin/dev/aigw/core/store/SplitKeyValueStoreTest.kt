package dev.aigw.core.store

import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 按前缀拆分存储的语义回归。
 *
 * 关键是三件事：路由不能串（凭证不能被写进日志文件）、[keys] 要跨库合并（存储统计
 * 与日志页看不到就相当于丢数据）、旧数据迁移必须幂等且搬完即从源删除。
 */
class SplitKeyValueStoreTest {

    private fun newSplit(): Triple<SplitKeyValueStore, InMemoryKeyValueStore, InMemoryKeyValueStore> {
        val main = InMemoryKeyValueStore()
        val logs = InMemoryKeyValueStore()
        val split = SplitKeyValueStore(
            routes = SplitKeyValueStore.LOG_STORE_PREFIXES.associateWith { logs },
            fallback = main,
        )
        return Triple(split, main, logs)
    }

    @Test
    fun `日志与用量落到日志库，其余留在主库`() {
        val (split, main, logs) = newSplit()
        split.write("logs/calls/a", "call")
        split.write("usage/total", "usage")
        split.write("account/trae/u1", "cred")
        split.write("settings/gateway.json", "settings")

        assertEquals("call", logs.read("logs/calls/a"))
        assertEquals("usage", logs.read("usage/total"))
        assertEquals("cred", main.read("account/trae/u1"))
        assertEquals("settings", main.read("settings/gateway.json"))
        assertNull(main.read("logs/calls/a"), "日志不应落到主库")
        assertNull(logs.read("account/trae/u1"), "凭证不应落到日志库")
    }

    @Test
    fun `读取与删除按同一个前缀路由`() {
        val (split, _, logs) = newSplit()
        split.write("logs/calls/a", "v")
        assertEquals("v", split.read("logs/calls/a"))
        split.delete("logs/calls/a")
        assertNull(split.read("logs/calls/a"))
        assertNull(logs.read("logs/calls/a"))
    }

    @Test
    fun `keys 跨库合并且按前缀过滤`() {
        val (split, _, _) = newSplit()
        split.write("logs/calls/a", "1")
        split.write("logs/requests.json", "2")
        split.write("account/trae/u1", "3")

        assertEquals(
            listOf("logs/calls/a", "logs/requests.json"),
            split.keys("logs/").sorted(),
        )
        assertEquals(listOf("account/trae/u1"), split.keys("account/"))
        assertEquals(3, split.keys("").size, "空前缀要能取到全部库的键")
    }

    @Test
    fun `最长前缀优先`() {
        val main = InMemoryKeyValueStore()
        val logs = InMemoryKeyValueStore()
        val special = InMemoryKeyValueStore()
        val split = SplitKeyValueStore(
            routes = mapOf("logs/" to logs, "logs/calls/" to special),
            fallback = main,
        )
        split.write("logs/calls/a", "x")
        split.write("logs/requests.json", "y")
        assertEquals("x", special.read("logs/calls/a"))
        assertEquals("y", logs.read("logs/requests.json"))
    }

    @Test
    fun `flush 会转发给所有库`() {
        val flushed = mutableListOf<String>()
        fun counting(name: String) = object : KeyValueStore {
            val inner = InMemoryKeyValueStore()
            override fun read(key: String): String? = inner.read(key)
            override fun write(key: String, value: String) = inner.write(key, value)
            override fun delete(key: String) = inner.delete(key)
            override fun keys(prefix: String): List<String> = inner.keys(prefix)
            override fun flush() {
                flushed.add(name)
            }
        }
        val main = counting("main")
        val logs = counting("logs")
        val split = SplitKeyValueStore(mapOf("logs/" to logs), main)
        split.flush()
        assertEquals(listOf("logs", "main"), flushed.sorted())
    }

    @Test
    fun `旧数据迁移把日志搬到日志库并清除源数据`() {
        val main = InMemoryKeyValueStore()
        main.write("logs/calls/old", "old-call")
        main.write("usage/total", "old-usage")
        main.write("account/trae/u1", "cred")
        val logs = InMemoryKeyValueStore()
        val split = SplitKeyValueStore(mapOf("logs/" to logs, "usage/" to logs), main)

        split.migrateLegacy()

        assertEquals("old-call", split.read("logs/calls/old"))
        assertEquals("old-usage", split.read("usage/total"))
        assertNull(main.read("logs/calls/old"), "搬完应从主库删除，否则主文件永远不会瘦下来")
        assertNull(main.read("usage/total"))
        assertEquals("cred", main.read("account/trae/u1"), "非日志数据不能被搬走")
    }

    @Test
    fun `迁移只执行一次`() {
        val main = InMemoryKeyValueStore()
        main.write("logs/calls/old", "v")
        val logs = InMemoryKeyValueStore()
        val split = SplitKeyValueStore(mapOf("logs/" to logs), main)
        split.migrateLegacy()
        // 模拟迁移后又有数据落在主库（例如回退再升级）：标记存在时不再搬
        main.write("logs/calls/new", "n")
        split.migrateLegacy()
        assertEquals("n", main.read("logs/calls/new"), "标记存在时不应重复搬运")
        assertEquals("v", logs.read("logs/calls/old"))
    }

    @Test
    fun `迁移后新写入仍然按前缀路由`() {
        val main = InMemoryKeyValueStore()
        val logs = InMemoryKeyValueStore()
        val split = SplitKeyValueStore(mapOf("logs/" to logs), main)
        split.migrateLegacy()
        split.write("logs/calls/fresh", "v")
        assertEquals("v", logs.read("logs/calls/fresh"))
    }

    @Test
    fun `空路由时所有键落到兜底存储`() {
        val main = InMemoryKeyValueStore()
        val split = SplitKeyValueStore(emptyMap(), main)
        split.write("logs/calls/a", "v")
        assertEquals("v", main.read("logs/calls/a"))
        assertTrue(split.keys("logs/").contains("logs/calls/a"))
    }
}
