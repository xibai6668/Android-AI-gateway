package dev.aigw.core.gateway

/**
 * 前台服务的引擎看门狗。
 *
 * 解决的是「前台服务还在、引擎的监听已经死了」这种僵尸态：[GatewayEngine.isRunning]
 * 只看 `server` 对象是否为空，NanoHTTPD 的 accept 循环死掉之后它仍然返回 true，
 * 于是通知写着「运行中」，端口却已经拒绝连接，客户端表现为「网关开着却连不上」。
 *
 * 看门狗只信**可观测事实**（一次真实的探活），不信对象状态：探活失败就尝试重启引擎；
 * 连续 [maxFailedTicks] 次都救不回来，就通过 [onGaveUp] 让服务诚实收摊（停通知、停服务），
 * 而不是继续假装运行。
 *
 * 本类不做任何 Android / 网络操作：探活与重启都由调用方注入，因此全部状态迁移
 * 都能在 `:core` 的单测里用假函数驱动。
 */
class EngineWatchdog(
    private val probe: () -> Boolean,
    private val restart: () -> Boolean,
    private val onGaveUp: () -> Unit,
    private val maxFailedTicks: Int = 3,
) {
    init {
        require(maxFailedTicks >= 1) { "maxFailedTicks must be >= 1" }
    }

    private var failedTicks = 0
    private var stopped = false

    /** 已连续失败的巡检次数（日志与测试用）。 */
    val consecutiveFailures: Int
        get() = failedTicks

    /** 是否已经放弃（放弃后 [tick] 不再做任何事）。 */
    val gaveUp: Boolean
        get() = stopped

    /**
     * 巡检一次。返回 true 表示本次探活确认引擎在服务。
     *
     * 探活失败时尝试重启一次；连续 [maxFailedTicks] 次探活失败后调用 [onGaveUp]，
     * 且此后不再探活、不再重启。探活抛异常按失败处理；重启抛异常也按失败计数。
     */
    fun tick(): Boolean {
        if (stopped) return false

        val healthy = runCatching { probe() }.getOrDefault(false)
        if (healthy) {
            failedTicks = 0
            return true
        }

        val restarted = runCatching { restart() }.getOrDefault(false)
        failedTicks += 1
        if (failedTicks >= maxFailedTicks) {
            stopped = true
            runCatching { onGaveUp() }
        }
        return restarted && !stopped
    }

    /**
     * 服务主动停止时调用：之后 [tick] 不再探活或重启。
     *
     * 与服务自身的 stop 路径配合，避免「用户已经停了网关，看门狗又把引擎拉起来」
     * 这种反向竞态。
     */
    fun cancel() {
        stopped = true
    }
}
