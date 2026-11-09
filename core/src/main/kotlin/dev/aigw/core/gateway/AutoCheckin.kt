package dev.aigw.core.gateway

import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.startOfDay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个账号的签到回执（从 [BatchActionResult] 映射而来，让本类不依赖网关引擎）。 */
data class AutoCheckinResult(
    /** 展示用名字：昵称，缺失时退回 uid。 */
    val label: String,
    val ok: Boolean,
    /** 上游原话，例如「签到成功」/「今日已签到」/ 失败原因。 */
    val message: String,
)

/** 一次巡检的结果。 */
data class AutoCheckinReport(
    val outcome: AutoCheckinOutcome,
    val targetCount: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    /** 逐条回执（成功与失败都在内），供界面与日志核对链路是否真的跑通。 */
    val messages: List<String> = emptyList(),
) {
    /** 一句话总结，供顶部提示与 issue 粘贴。 */
    fun summary(): String = when (outcome) {
        AutoCheckinOutcome.EXECUTED ->
            "自动签到：执行 $targetCount 个账号，成功 $succeeded，失败 $failed"
        AutoCheckinOutcome.SKIPPED_ALREADY_DONE -> "自动签到：今天已跑过，跳过"
        AutoCheckinOutcome.SKIPPED_BACKOFF -> "自动签到：上次未全成功，退避中"
        AutoCheckinOutcome.SKIPPED_IN_FLIGHT -> "自动签到：已有一次正在执行"
        AutoCheckinOutcome.NO_TARGETS -> "自动签到：没有支持签到的账号"
        AutoCheckinOutcome.FAILED -> "自动签到：执行异常（详见日志页）"
    }
}

enum class AutoCheckinOutcome {
    EXECUTED,
    SKIPPED_ALREADY_DONE,
    SKIPPED_BACKOFF,
    SKIPPED_IN_FLIGHT,
    NO_TARGETS,
    FAILED,
}

/**
 * 每日自动签到的闸门与台账：只决定「什么时候该跑」，选目标与执行都由调用方注入。
 *
 * 之所以把闸门单独抽成纯 JVM 类，是为了让「一天只跑一次 / 失败重试 / 并发不重入 /
 * 重启后不重复」这些语义能在 `:core` 单测里用假时钟直接验证 —— 不需要 Android、不需要
 * 等到明天、也不需要真的去签到。[tick] 的 [force] 则是给「今天已签到」的场景做链路自检：
 * 上游对已签到的账号回「今日已签到」（ok=true），不会重复领取，但整条
 * 「选目标 → 批量执行 → 逐条回执 → 日志」会真实走一遍。
 *
 * 语义约定：
 * - 一天（系统默认时区的自然日）最多**成功**执行一次；**全部**账号成功才记完成。
 *   部分失败不记完成，留待退避后重试，避免一个账号网络抖动让其它账号当天错过。
 * - 失败要退避：前台服务的看门狗 30 秒一拍，无限立即重试等于骚扰上游。
 * - 没有可选账号时不占退避窗口：用户刚加完账号，下一拍就该能跑。
 * - 并发（服务启动 / 看门狗 / 手动试跑可能同时发生）用 CAS 保证只有一个真正在执行。
 *
 * 刻意不做：不自建 WorkManager / 闹钟（蹭前台服务已有节拍，无需新权限）、不限定
 * 「白天才签」（上游按服务端日期发奖，客户端限时段只会漏签）。
 *
 * [T] 是目标账号的载体类型，本类只关心它是否为空、有几个，因此不依赖网关引擎的具体类型。
 */
class AutoCheckin<T>(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val selectTargets: () -> List<T> = { emptyList() },
    private val execute: (List<T>) -> List<AutoCheckinResult> = { emptyList() },
    private val log: (String) -> Unit = {},
    private val retryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS,
) {
    companion object {
        /** 台账键：上次**全部成功**的自然日（[startOfDay] 的时间戳）。 */
        const val KEY_LAST_SUCCESS_DAY = "checkin/auto/last_success_day"

        /** 台账键：上次真正发起执行的时刻（epoch millis），用于失败退避。 */
        const val KEY_LAST_ATTEMPT_AT = "checkin/auto/last_attempt_at"

        /** 失败后的退避窗口：1 小时。 */
        const val DEFAULT_RETRY_INTERVAL_MS = 60 * 60 * 1000L
    }

    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var lastSuccessDay: Long = readLong(KEY_LAST_SUCCESS_DAY)

    @Volatile
    private var lastAttemptAt: Long = readLong(KEY_LAST_ATTEMPT_AT)

    private fun readLong(key: String): Long = store.read(key)?.toLongOrNull() ?: -1L

    /**
     * 纯内存快路径：给 30 秒一拍的巡检用。绝大多数轮次在这里就返回 false，
     * 连线程都不必起。[tick] 内部仍会重新判定一次，避免这里与执行之间的竞态。
     */
    fun shouldRun(force: Boolean = false): Boolean {
        if (inFlight.get()) return false
        if (force) return true
        val now = nowMillis()
        if (lastSuccessDay == startOfDay(now)) return false
        return now - lastAttemptAt >= retryIntervalMs
    }

    /** 今天是否已经成功跑过（界面展示用）。 */
    fun doneToday(): Boolean = lastSuccessDay == startOfDay(nowMillis())

    /** 上次成功日（[startOfDay] 时间戳），从未成功过返回 -1。 */
    fun lastSuccessDay(): Long = lastSuccessDay

    /** 清台账。只给「试跑 / 排障」用，正常运行不调用。 */
    fun reset() {
        lastSuccessDay = -1L
        lastAttemptAt = -1L
        store.delete(KEY_LAST_SUCCESS_DAY)
        store.delete(KEY_LAST_ATTEMPT_AT)
    }

    /**
     * 巡检一次。
     *
     * [force] = true 绕过「今天已跑过」与退避，用于手动试跑验证整条链路；
     * 它**不**绕过 [AutoCheckinOutcome.SKIPPED_IN_FLIGHT]（真有并发时仍只跑一次）。
     */
    fun tick(force: Boolean = false): AutoCheckinReport {
        if (!inFlight.compareAndSet(false, true)) {
            return AutoCheckinReport(AutoCheckinOutcome.SKIPPED_IN_FLIGHT)
        }
        try {
            val now = nowMillis()
            val day = startOfDay(now)
            if (!force && lastSuccessDay == day) {
                return AutoCheckinReport(AutoCheckinOutcome.SKIPPED_ALREADY_DONE)
            }
            if (!force && now - lastAttemptAt < retryIntervalMs) {
                return AutoCheckinReport(AutoCheckinOutcome.SKIPPED_BACKOFF)
            }

            // 先记尝试时刻再发请求：即使进程被杀，也不会在退避窗口内重复打上游。
            lastAttemptAt = now
            store.write(KEY_LAST_ATTEMPT_AT, now.toString())

            val targets = runCatching { selectTargets() }.getOrElse { error ->
                log("自动签到：选目标失败：${error.message}")
                return AutoCheckinReport(
                    AutoCheckinOutcome.FAILED,
                    messages = listOf(error.message ?: "选目标失败"),
                )
            }
            if (targets.isEmpty()) {
                // 没账号不该占用退避窗口，否则用户加完账号还要等一小时。
                lastAttemptAt = -1L
                store.delete(KEY_LAST_ATTEMPT_AT)
                log("自动签到：没有支持签到的账号，跳过")
                return AutoCheckinReport(AutoCheckinOutcome.NO_TARGETS)
            }

            log("自动签到：本轮选中 ${targets.size} 个账号（force=$force）")
            val results = runCatching { execute(targets) }.getOrElse { error ->
                log("自动签到：执行异常：${error.message}")
                return AutoCheckinReport(
                    AutoCheckinOutcome.FAILED,
                    targetCount = targets.size,
                    failed = targets.size,
                    messages = listOf(error.message ?: "执行异常"),
                )
            }

            val messages = results.map { result ->
                "${result.label}：${if (result.ok) result.message.ifEmpty { "成功" } else "失败 · ${result.message}"}"
            }
            val succeeded = results.count { it.ok }
            val failed = results.size - succeeded
            messages.forEach { log("自动签到 · $it") }

            if (failed == 0) {
                lastSuccessDay = day
                store.write(KEY_LAST_SUCCESS_DAY, day.toString())
                log("自动签到：全部成功（$succeeded/${results.size}），今日不再重复执行")
            } else {
                log("自动签到：成功 $succeeded、失败 $failed，${retryIntervalMs / 60_000} 分钟后重试")
            }
            return AutoCheckinReport(
                outcome = AutoCheckinOutcome.EXECUTED,
                targetCount = results.size,
                succeeded = succeeded,
                failed = failed,
                messages = messages,
            )
        } finally {
            inFlight.set(false)
        }
    }
}
