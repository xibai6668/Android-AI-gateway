package dev.aigw.core.failover

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 并发闸门参数。 */
data class ConcurrencyConfig(
    /**
     * 同一账号同时在途的请求数上限；0 = 不限。
     *
     * 默认 1（串行）：上游按账号维度限流并发流，同一账号被 N 条流同时打时，
     * 除第一条外的连接会被挂住、只回一个空的首 chunk 就不再出内容，客户端遂超时。
     * 官方客户端本身对同一账号就是一次一个请求，所以按账号串行才是忠实的行为。
     */
    val maxPerAccount: Int = 1,
    /** 全网关同时在途的请求数上限；0 = 不限。 */
    val maxGlobal: Int = 0,
    /** 所有许可被占满时最多排队等待的毫秒数；超时按并发上限拒绝（429）。 */
    val queueTimeoutMs: Long = 30_000L,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("maxPerAccount", maxPerAccount)
        addProperty("maxGlobal", maxGlobal)
        addProperty("queueTimeoutMs", queueTimeoutMs)
    }

    companion object {
        const val MIN_PER_ACCOUNT = 0
        const val MAX_PER_ACCOUNT = 20
        const val MIN_GLOBAL = 0
        const val MAX_GLOBAL = 100
        const val MIN_QUEUE_TIMEOUT_MS = 0L
        const val MAX_QUEUE_TIMEOUT_MS = 120_000L

        fun clampMaxPerAccount(value: Int): Int = value.coerceIn(MIN_PER_ACCOUNT, MAX_PER_ACCOUNT)
        fun clampMaxGlobal(value: Int): Int = value.coerceIn(MIN_GLOBAL, MAX_GLOBAL)
        fun clampQueueTimeoutMs(value: Long): Long = value.coerceIn(MIN_QUEUE_TIMEOUT_MS, MAX_QUEUE_TIMEOUT_MS)

        fun fromJson(obj: JsonObject?): ConcurrencyConfig {
            if (obj == null) return ConcurrencyConfig()
            return ConcurrencyConfig(
                maxPerAccount = clampMaxPerAccount(obj.get("maxPerAccount")?.asInt ?: 1),
                maxGlobal = clampMaxGlobal(obj.get("maxGlobal")?.asInt ?: 0),
                queueTimeoutMs = clampQueueTimeoutMs(obj.get("queueTimeoutMs")?.asLong ?: 30_000L),
            )
        }
    }
}

/**
 * 上游请求的并发闸门：全局与单账号两级在途上限。
 *
 * 上游风控对「同一账号同时挂多条长对话」很敏感，这里在上游调用前取许可、
 * 调用（含读流转发）结束后释放；许可不可重入，释放必须配对，
 * [Handle.release] 幂等以兜底调用方的重复释放。
 */
class ConcurrencyGate(
    private val config: () -> ConcurrencyConfig,
) {
    private val globalInFlight = AtomicInteger(0)
    private val accountInFlight = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 尝试取得许可；[accountKey] 为 `providerId/uid`。
     * 拿不到时在 [ConcurrencyConfig.queueTimeoutMs] 内轮询等待，超时返回 null。
     *
     * 它会阻塞调用线程，所以只适合两种情况：HTTP 层在「该供应商已无余量」时的最后一次有界排队，
     * 以及本来就跑在后台线程的调用方。不能对每个候选账号都无脑排队——那会让工作线程被占满，
     * 所有请求一起超时。
     */
    fun tryAcquire(accountKey: String): Handle? {
        val cfg = config()
        if (cfg.maxGlobal <= 0 && cfg.maxPerAccount <= 0) return Handle.DISABLED
        return tryAcquire(accountKey, cfg.queueTimeoutMs)
    }

    /**
     * 在 [timeoutMs] 内等待许可。HTTP 层用它代替「直接拒绝」：单账号用户并发两条请求时，
     * 后一条稍等片刻就能接上前一条，比直接报 429 更符合 agent 客户端的预期。
     */
    fun tryAcquire(accountKey: String, timeoutMs: Long): Handle? {
        val cfg = config()
        if (cfg.maxGlobal <= 0 && cfg.maxPerAccount <= 0) return Handle.DISABLED

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        while (true) {
            tryOnce(accountKey, cfg)?.let { return it }
            if (System.nanoTime() >= deadline) return null
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }
    /**
     * 非阻塞取许可：没有余量时立刻返回 null。
     * 选号阶段用它，避免「选中一个满载账号 → 在 HTTP 线程上苦等」把线程耗光。
     */
    fun tryAcquireNow(accountKey: String): Handle? = tryOnce(accountKey, config())

    /**
     * 该账号现在是否还有在途余量（只读探测，不占许可）。
     * 选号时据此避开已满载的账号，否则多账号也会全挤在优先级最高的那一个上排队。
     */
    fun hasCapacity(accountKey: String): Boolean {
        val cfg = config()
        if (cfg.maxGlobal > 0 && inFlightGlobal() >= cfg.maxGlobal) return false
        if (cfg.maxPerAccount > 0 && inFlightOf(accountKey) >= cfg.maxPerAccount) return false
        return true
    }

    /** 单次不等待的取许可；拿不到时把本轮的全局计数回滚，不留漂移。 */
    private fun tryOnce(accountKey: String, cfg: ConcurrencyConfig): Handle? {
        val globalLimit = cfg.maxGlobal
        val accountLimit = cfg.maxPerAccount
        if (globalLimit <= 0 && accountLimit <= 0) return Handle.DISABLED

        // 全局先入队：账号级许可一定被全局覆盖，顺序保证不会出现「账号拿到了、全局拿不到」的倒挂。
        // 句柄要记住「本轮到底有没有真的加过全局计数」：maxGlobal<=0（全局不限）时不能减，
        // 否则把从没加过的计数减成负数。
        val globalAcquired = globalLimit > 0
        if (globalAcquired && !tryIncrement(globalInFlight, globalLimit)) return null
        if (accountLimit <= 0) return Handle(this, accountKey, false, globalAcquired)

        val counter = accountInFlight.computeIfAbsent(accountKey) { AtomicInteger(0) }
        if (!tryIncrement(counter, accountLimit)) {
            if (globalAcquired) globalInFlight.decrementAndGet()
            return null
        }
        return Handle(this, accountKey, true, globalAcquired)
    }

    private fun tryIncrement(counter: AtomicInteger, limit: Int): Boolean {
        while (true) {
            val current = counter.get()
            if (current >= limit) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    private fun doRelease(accountKey: String, accountTracked: Boolean, globalTracked: Boolean) {
        if (accountTracked) {
            // 只递减不摘除条目：归零后摘除会与新请求 computeIfAbsent 拿到的旧引用竞争，
            // 新请求可能 CAS 成功一个「已脱离 Map 的计数器」，随后另一请求又新建条目从 0 起步，
            // 同账号在途数瞬时超发。空 AtomicInteger 的内存占用可忽略，正确性优先。
            accountInFlight[accountKey]?.decrementAndGet()
        }
        // 只在获取时真的加过全局计数时才递减，否则会减成负数（等效放大上限）
        if (globalTracked) globalInFlight.decrementAndGet()
    }

    /**
     * 当前在途数（全局/单账号）。这只是计数器的读出口，供测试与诊断；
     * 界面上的「在途请求」展示走 [dev.aigw.core.gateway.ActiveRequests]，不经这里。
     */
    fun inFlightGlobal(): Int = globalInFlight.get()
    fun inFlightOf(accountKey: String): Int = accountInFlight[accountKey]?.get() ?: 0

    /** 一次取得并发许可的句柄；release 释放且幂等。 */
    class Handle internal constructor(
        private val gate: ConcurrencyGate?,
        private val accountKey: String,
        private val accountTracked: Boolean,
        /** 获取时是否真的占用了全局计数（maxGlobal<=0 时为 false，release 不得递减）。 */
        private val globalTracked: Boolean,
    ) {
        private val released = AtomicBoolean(false)

        /** 释放许可；重复调用无副作用。闸门未启用时返回 false 表示无事发生。 */
        fun release(): Boolean {
            val g = gate ?: return false
            if (!released.compareAndSet(false, true)) return false
            g.doRelease(accountKey, accountTracked, globalTracked)
            return true
        }

        companion object {
            /** 闸门未启用时的空句柄：release 是无操作。 */
            internal val DISABLED = Handle(null, "", false, false)
        }
    }

    private companion object {
        private const val POLL_INTERVAL_MS = 50L
    }
}
