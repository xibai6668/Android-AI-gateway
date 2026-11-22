package dev.aigw.core.store

import dev.aigw.core.InMemoryKeyValueStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 合并提交存储的语义回归。
 *
 * 这些断言锁住的是「用批量换性能」时最容易踩坏的三件事：读不到自己刚写的值、
 * 提交期间的新写被旧快照覆盖掉、以及凭证类写入被误当成可延迟数据。
 */
class BatchedKeyValueStoreTest {

    /** 记录委托调用次数，用来断言「合并没有退化成逐条提交」。 */
    private class CountingStore(private val inner: InMemoryKeyValueStore = InMemoryKeyValueStore()) : KeyValueStore {
        val writes = AtomicInteger()
        val deletes = AtomicInteger()

        override fun read(key: String): String? = inner.read(key)
        override fun write(key: String, value: String) {
            writes.incrementAndGet()
            inner.write(key, value)
        }
        override fun delete(key: String) {
            deletes.incrementAndGet()
            inner.delete(key)
        }
        override fun keys(prefix: String): List<String> = inner.keys(prefix)
        fun raw(): Map<String, String> = inner.raw()
    }

    @Test
    fun `写入在提交前就能读到`() {
        val delegate = CountingStore()
        val store = BatchedKeyValueStore(delegate, intervalMillis = 60_000)
        store.write("logs/calls/a", "v1")
        assertEquals("v1", store.read("logs/calls/a"), "提交前也必须读到自己刚写的值")
        assertEquals(0, delegate.writes.get(), "合并窗口内不应落盘")
    }

    @Test
    fun `flush 把待提交写入一次性交给委托存储`() {
        val delegate = CountingStore()
        val store = BatchedKeyValueStore(delegate, intervalMillis = 60_000)
        store.write("logs/calls/a", "v1")
        store.write("usage/total", "v2")
        store.write("clientkey/usage/k", "v3")
        store.flush()

        assertEquals(3, delegate.writes.get(), "三个键应各提交一次")
        assertEquals("v1", delegate.raw()["logs/calls/a"])
        assertEquals(0, store.flushStats().pendingWrites, "提交后待提交区应清空")
    }

    @Test
    fun `同一键多次写入合并为一次提交`() {
        val delegate = CountingStore()
        val store = BatchedKeyValueStore(delegate, intervalMillis = 60_000)
        repeat(20) { store.write("usage/total", "v$it") }
        store.flush()
        assertEquals(1, delegate.writes.get(), "同一键的连续写入应合并成一次")
        assertEquals("v19", delegate.raw()["usage/total"])
    }

    @Test
    fun `凭证与设置类写入同步落盘`() {
        val delegate = CountingStore()
        val store = BatchedKeyValueStore(delegate, intervalMillis = 60_000)
        store.write("account/trae/u1", "cred")
        store.write("settings/gateway.json", "{}")
        assertEquals(2, delegate.writes.get(), "凭证/设置不在合并前缀内，必须立刻落盘")
        assertEquals(0, store.flushStats().pendingWrites)
    }

    @Test
    fun `删除语义覆盖待提交的写入`() {
        val delegate = CountingStore()
        val store = BatchedKeyValueStore(delegate, intervalMillis = 60_000)
        store.write("logs/calls/a", "v1")
        store.delete("logs/calls/a")
        assertNull(store.read("logs/calls/a"), "同窗口内先写后删应读到 null")
        store.flush()
        assertEquals(0, delegate.writes.get(), "被删掉的键不该再落盘")
        assertEquals(1, delegate.deletes.get())
        assertNull(delegate.raw()["logs/calls/a"])
    }

    @Test
    fun `删除已落盘的键在提交前就不可见`() {
        val delegate = CountingStore()
        delegate.write("logs/calls/old", "v")
        val store = BatchedKeyValueStore(delegate, intervalMillis = 60_000)
        store.delete("logs/calls/old")
        assertNull(store.read("logs/calls/old"))
        assertTrue(store.keys("logs/calls/").isEmpty(), "keys 也要反映未提交的删除")
    }

    @Test
    fun `keys 包含未提交的键`() {
        val delegate = CountingStore()
        val store = BatchedKeyValueStore(delegate, intervalMillis = 60_000)
        store.write("logs/calls/a", "v1")
        assertEquals(listOf("logs/calls/a"), store.keys("logs/calls/"))
    }

    @Test
    fun `提交期间产生的新值不会被旧快照覆盖`() {
        val slow = object : KeyValueStore {
            val inner = InMemoryKeyValueStore()
            var onWrite: (() -> Unit)? = null
            override fun read(key: String): String? = inner.read(key)
            override fun write(key: String, value: String) {
                onWrite?.invoke()
                inner.write(key, value)
            }
            override fun delete(key: String) = inner.delete(key)
            override fun keys(prefix: String): List<String> = inner.keys(prefix)
        }
        val store = BatchedKeyValueStore(slow, intervalMillis = 60_000)
        store.write("usage/total", "old")
        // 第一次提交写入 old 的过程中，把同一个键改成 new：new 必须留到下一轮
        slow.onWrite = {
            slow.onWrite = null
            store.write("usage/total", "new")
        }
        store.flush()
        assertEquals("new", store.read("usage/total"))
        assertEquals(1, store.flushStats().pendingWrites, "提交期间的新值应留在待提交区")
        store.flush()
        assertEquals("new", slow.inner.read("usage/total"))
    }

    @Test
    fun `并发写入不丢`() {
        val delegate = CountingStore()
        val store = BatchedKeyValueStore(delegate, intervalMillis = 5)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val keys = 400
        try {
            val futures = (1..keys).map { i ->
                pool.submit {
                    start.await()
                    store.write("logs/calls/k$i", "v$i")
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            store.flush()
            val stored = delegate.raw()
            assertEquals(keys, stored.size, "所有键都必须落盘")
            (1..keys).forEach { i -> assertEquals("v$i", stored["logs/calls/k$i"]) }
            assertEquals(0, store.flushStats().pendingWrites)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `后台节拍会自动提交`() {
        val delegate = CountingStore()
        val store = BatchedKeyValueStore(delegate, intervalMillis = 20)
        store.write("logs/calls/auto", "v")
        val deadline = System.currentTimeMillis() + 3_000
        while (delegate.raw()["logs/calls/auto"] == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("v", delegate.raw()["logs/calls/auto"], "合并窗口到点后应自动落盘")
    }

    @Test
    fun `委托存储抛错时条目留在待提交区`() {
        val failing = object : KeyValueStore {
            val inner = InMemoryKeyValueStore()
            val failuresLeft = AtomicInteger(1)
            override fun read(key: String): String? = inner.read(key)
            override fun write(key: String, value: String) {
                if (failuresLeft.getAndDecrement() > 0) throw IllegalStateException("磁盘忙")
                inner.write(key, value)
            }
            override fun delete(key: String) = inner.delete(key)
            override fun keys(prefix: String): List<String> = inner.keys(prefix)
        }
        val store = BatchedKeyValueStore(failing, intervalMillis = 60_000)
        store.write("logs/calls/a", "v")
        store.flush()
        assertEquals(1, store.flushStats().pendingWrites, "写失败的值不能丢，要留到下一轮")
        assertEquals(1, store.flushStats().failures)
        store.flush()
        assertEquals("v", failing.inner.read("logs/calls/a"))
    }

    @Test
    fun `提交统计随写入增长`() {
        val store = BatchedKeyValueStore(CountingStore(), intervalMillis = 60_000)
        store.write("logs/a", "1")
        store.write("logs/b", "2")
        store.flush()
        val stats = store.flushStats()
        assertEquals(1, stats.flushes)
        assertEquals(2, stats.lastBatchKeys)
        assertTrue(stats.lastFlushMillis >= 0)
    }
}
