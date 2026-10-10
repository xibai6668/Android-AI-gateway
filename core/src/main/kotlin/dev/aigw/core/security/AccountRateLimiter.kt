package dev.aigw.core.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * 账号级请求限速与节拍抖动器：
 * 保证同一个账号发往上游的两次请求之间保持一定的最小间隔，并加入随机抖动，
 * 避免固定间隔节拍被上游风控识别为自动化脚本/机器人。
 */
class AccountRateLimiter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 记录每个账号（`providerId/uid`）上次向上游发送请求的时间戳。 */
    private val lastDispatchTimeMap = ConcurrentHashMap<String, Long>()

    /** 每账号锁对象：避免用 `String.intern()` 做锁（会污染字符串常量池且跨类意外竞争）。 */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * 在即将调用上游前阻塞等待，直到满足该账号的节拍约束。
     *
     * @param providerId 供应商 ID
     * @param uid 账号 UID
     * @param minIntervalMillis 最小间隔（毫秒）
     * @param jitterMillis 抖动幅度（毫秒）
     * @return 实际等待延迟（毫秒），0 表示无需等待
     */
    fun acquire(
        providerId: String,
        uid: String,
        minIntervalMillis: Long,
        jitterMillis: Long,
    ): Long {
        if (minIntervalMillis <= 0L && jitterMillis <= 0L) {
            return 0L
        }
        val key = "$providerId/$uid"
        val now = nowMillis()

        // 计算带抖动的期望间隔：minInterval + 随机[0..jitter]
        val jitter = if (jitterMillis > 0L) {
            ThreadLocalRandom.current().nextLong(jitterMillis + 1)
        } else {
            0L
        }
        val targetInterval = minIntervalMillis + jitter

        synchronized(locks.computeIfAbsent(key) { Any() }) {
            val lastTime = lastDispatchTimeMap[key] ?: 0L
            val elapsed = now - lastTime
            val waitTime = (targetInterval - elapsed).coerceAtLeast(0L)

            if (waitTime > 0L) {
                try {
                    Thread.sleep(waitTime)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastDispatchTimeMap[key] = nowMillis()
            return waitTime
        }
    }

    fun clear() {
        lastDispatchTimeMap.clear()
        locks.clear()
    }
}
