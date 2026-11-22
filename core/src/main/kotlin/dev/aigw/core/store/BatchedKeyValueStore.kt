package dev.aigw.core.store

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 批量提交的统计快照，供 `/metrics` 与 `/v1/health` 观测。
 *
 * 排障时最需要看的是「待提交积压」与「单次提交耗时」：前者一直不为 0 说明写入速度
 * 跟不上产生速度，后者随存储体量线性增长则是整文件重写型存储的固有问题。
 */
data class FlushStats(
    val pendingWrites: Int,
    val pendingDeletes: Int,
    val flushes: Long,
    val failures: Long,
    val lastBatchKeys: Int,
    val lastFlushMillis: Long,
)

/**
 * 把高频、低价值的写入合并成批量提交的存储包装。
 *
 * Android 侧的加密存储是「所有键一个文件，每次写入整份重写 + fsync」。网关每个请求
 * 都要写调用记录、用量聚合与密钥台账，直连时就是每请求数次整文件重写，并发一高全部
 * 堆在同一个文件锁上，整机吞吐退化到「一次 fsync 的倒数」。
 *
 * 这里的做法是：把 [batchedPrefixes] 命中的键先写进内存镜像并合并，后台线程按
 * [intervalMillis] 一次提交；其余键（凭证、设置、登录中间态）仍然同步落盘——它们的写入
 * 是低频且不能丢，用批量换来的那点延迟不值得。
 *
 * 一致性约定：
 * - **读己所写**：未提交的值就在内存里，[read] 先看待提交区，再回落到 [delegate]；
 * - **不丢写**：提交按快照进行，只有确认写进 [delegate] 之后才从待提交区摘除
 *   （`remove(key, value)` 比对值），提交期间产生的新值会留到下一轮；
 * - **可强刷**：[flush] 同步落盘，进程退出/任务被划掉时由引擎调用。
 */
class BatchedKeyValueStore(
    private val delegate: KeyValueStore,
    private val batchedPrefixes: List<String> = DEFAULT_BATCHED_PREFIXES,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS,
) : KeyValueStore {

    /** 待提交的写：键 → 最新值。 */
    private val pendingWrites = ConcurrentHashMap<String, String>()

    /** 待提交的删除；与 [pendingWrites] 互斥（同一键后写覆盖前操作）。 */
    private val pendingDeletes = ConcurrentHashMap.newKeySet<String>()

    private val flushLock = Any()
    private val flushScheduled = AtomicBoolean(false)
    private val flushCount = AtomicLong(0)
    private val failureCount = AtomicLong(0)
    private val lastBatchKeys = AtomicLong(0)
    private val lastFlushMillis = AtomicLong(0)

    override fun read(key: String): String? {
        pendingWrites[key]?.let { return it }
        if (pendingDeletes.contains(key)) return null
        return delegate.read(key)
    }

    override fun write(key: String, value: String) {
        if (!isBatched(key)) {
            delegate.write(key, value)
            return
        }
        pendingDeletes.remove(key)
        pendingWrites[key] = value
        scheduleFlush()
    }

    override fun delete(key: String) {
        if (!isBatched(key)) {
            delegate.delete(key)
            return
        }
        pendingWrites.remove(key)
        pendingDeletes.add(key)
        scheduleFlush()
    }

    override fun keys(prefix: String): List<String> {
        // 待提交区要先合并进来：日志页/存储统计在提交间隔内也必须看到刚写的键，
        // 否则会出现「刚记了一条调用，列表里还没有」的空窗。
        val merged = LinkedHashSet(delegate.keys(prefix))
        for (key in pendingWrites.keys) if (key.startsWith(prefix)) merged.add(key)
        for (key in pendingDeletes) if (key.startsWith(prefix)) merged.remove(key)
        return merged.toList()
    }

    /** 立即把待提交的写入交给 [delegate]；无待提交时是空操作。 */
    override fun flush() {
        if (pendingWrites.isEmpty() && pendingDeletes.isEmpty()) return
        synchronized(flushLock) {
            val writes = HashMap(pendingWrites)
            val deletes = pendingDeletes.toList()
            if (writes.isEmpty() && deletes.isEmpty()) return
            val startedAt = System.currentTimeMillis()
            var applied = 0
            var failed = 0

            for ((key, value) in writes) {
                // 只摘除「还等于我们写下去的那个值」的条目：提交期间同一键被改成新值时，
                // 新值必须留在待提交区，否则会丢一次写入。
                try {
                    delegate.write(key, value)
                    pendingWrites.remove(key, value)
                    applied++
                } catch (_: Exception) {
                    failed++
                }
            }
            for (key in deletes) {
                try {
                    delegate.delete(key)
                    pendingDeletes.remove(key)
                    applied++
                } catch (_: Exception) {
                    failed++
                }
            }

            flushCount.incrementAndGet()
            if (failed > 0) failureCount.addAndGet(failed.toLong())
            lastBatchKeys.set(applied.toLong())
            lastFlushMillis.set(System.currentTimeMillis() - startedAt)
        }
    }

    fun flushStats(): FlushStats = FlushStats(
        pendingWrites = pendingWrites.size,
        pendingDeletes = pendingDeletes.size,
        flushes = flushCount.get(),
        failures = failureCount.get(),
        lastBatchKeys = lastBatchKeys.get().toInt(),
        lastFlushMillis = lastFlushMillis.get(),
    )

    private fun isBatched(key: String): Boolean = batchedPrefixes.any { key.startsWith(it) }

    /**
     * 合并调度：一个间隔内最多排一次提交。
     *
     * 用「已排队」标志而不是每次都 schedule，是为了让连续写入合并成一次提交；
     * 提交失败（delegate 抛异常）时不重试，条目留在待提交区等下一轮——重试风暴
     * 会把磁盘问题放大成 CPU 问题。
     */
    private fun scheduleFlush() {
        if (intervalMillis <= 0) {
            flush()
            return
        }
        if (!flushScheduled.compareAndSet(false, true)) return
        SCHEDULER.schedule(
            {
                flushScheduled.set(false)
                runCatching { flush() }
            },
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    companion object {
        /**
         * 默认走批量提交的键前缀。
         *
         * 只包含「高频、可重算或可容忍短窗口丢失」的数据：日志、用量聚合、密钥台账。
         * 凭证（`account/`）、设置（`settings/`）、登录中间态（`login/`）刻意不在其中——
         * 那些写入低频且丢了要用户重新登录，同步落盘更划算。
         */
        val DEFAULT_BATCHED_PREFIXES = listOf("logs/", "usage/", "clientkey/")

        /** 合并窗口：200ms 足够把同一波并发请求的写入收拢成一次提交，又不至于让用户看到滞后。 */
        const val DEFAULT_INTERVAL_MS = 200L

        /** 所有实例共用一条调度线程：批量存储可能被构造多次，各自起线程只会多占资源。 */
        private val SCHEDULER = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "aigw-store-flush").apply { isDaemon = true }
        }
    }
}
