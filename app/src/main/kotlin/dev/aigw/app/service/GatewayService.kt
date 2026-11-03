package dev.aigw.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.aigw.app.AiGatewayApp
import dev.aigw.app.MainActivity
import dev.aigw.app.R
import dev.aigw.app.gatewayEngine
import dev.aigw.core.gateway.EngineWatchdog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 只负责「让进程活着」：网关本身由 UI 侧启动，成功后拉起本服务，
 * 这样锁屏或切后台后端口仍有人监听。
 *
 * 服务是引擎的宿主，不只是「陪跑」：
 * - 存活期间每 [WATCHDOG_INTERVAL_MS] 做一次真实探活（不是看对象状态），
 *   端口没响应就重启引擎，连续失败就诚实收摊（见 [EngineWatchdog]）；
 * - [START_STICKY]：进程被系统回收后服务自重建，onStartCommand 幂等，会重新拉起引擎；
 * - 服务销毁时顺带停掉引擎，避免出现「端口开着但没人看管」的孤儿状态。
 */
class GatewayService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val watchdogHandler = Handler(Looper.getMainLooper())

    /** 服务是否仍处于「应该让网关运行」的状态；看门狗线程与主线程都读。 */
    @Volatile
    private var active = false

    /** 上一次探活还没返回时跳过本轮，避免请求堆积（探活本身有 2 秒上限）。 */
    @Volatile
    private var probing = false

    private lateinit var watchdog: EngineWatchdog

    private val watchdogTick = object : Runnable {
        override fun run() {
            if (!active) return
            if (probing) {
                scheduleNextTick()
                return
            }
            probing = true
            Thread({
                runCatching { watchdog.tick() }
                probing = false
                watchdogHandler.post {
                    if (active && !watchdog.gaveUp) scheduleNextTick()
                }
            }, "gateway-watchdog").start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        watchdog = EngineWatchdog(
            probe = ::engineHealthy,
            restart = ::restartEngine,
            onGaveUp = ::onEngineUnrecoverable,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = applicationContext.gatewayEngine
        // 通知必须最先发：startForegroundService 拉起的服务不调 startForeground
        // 会被系统判定超时直接崩溃（ForegroundServiceDidNotStartInTime），
        // 而且「服务已启动」本身就是用户要的反馈，不应等引擎验证完才弹。
        // NanoHTTPD.start(timeout=0) 会同步等 bind 完成/抛出，engine.start() 返回
        // 即端口已就绪，所以这里不需要任何先导耗时操作。
        startForeground(NOTIFICATION_ID, buildNotification(engine.settings().port))
        val started = engine.isRunning() || runCatching { engine.start() }.isSuccess
        if (started) {
            engine.requestLog.info(
                if (intent == null) "前台服务被系统重建，网关已恢复"
                else "前台服务已拉起，通知已发布",
            )
            acquireLocks()
            applyOverlay(engine)
            // 实时状态通知：仅在网关运行期间存在，跟随在途请求增删（onDestroy 一并注销）
            LiveStatusNotifier.start(
                context = this,
                requests = engine.activeRequests,
                enabled = dev.aigw.app.data.KeepAlivePrefs.liveNotificationEnabled(engine.store()),
                log = { engine.requestLog.info(it) },
            )
            startWatchdog()
            // START_STICKY：进程被系统回收后服务自重建并重新拉起引擎（onStartCommand 幂等）。
            // 用户主动停止走 stopService，系统不会重启；引擎起不来时下面的 stopSelf 也会清掉粘性。
            return START_STICKY
        }
        // 引擎没起来：撤掉刚发的通知再退出，避免「通知说运行中、端口没人听」
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        if (::watchdog.isInitialized) watchdog.cancel()
        watchdogHandler.removeCallbacks(watchdogTick)
        // 网关没跑就不该有「实时状态」通知
        LiveStatusNotifier.stop()
        FloatingKeepAlive.hide(this)
        releaseLocks()
        // 服务是引擎的宿主：服务没了，端口不该继续开着。「端口在、没人看管」正是
        // 僵尸态的另一面（进程退到后台，通知没了，客户端却还能连上一个无人维护的端口）。
        runCatching { applicationContext.gatewayEngine.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 看门狗

    private fun startWatchdog() {
        if (active) return
        active = true
        watchdogHandler.removeCallbacks(watchdogTick)
        watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
    }

    private fun scheduleNextTick() {
        watchdogHandler.removeCallbacks(watchdogTick)
        watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
    }

    /**
     * 真实探活：连上端口并发一个最小的 HTTP 请求，只有拿到 200 才算引擎在服务。
     *
     * 不能只看 `engine.isRunning()`：它只判断 server 对象非空，accept 线程死掉后
     * 仍然是 true——这正是「通知说运行中、端口已 refused」的来源。
     * 也不能只做 TCP connect：监听 socket 还在、accept 循环已死时，内核仍会完成
     * 三次握手，连接能建立但永远没有响应。
     */
    private fun engineHealthy(): Boolean {
        val engine = applicationContext.gatewayEngine
        if (!engine.isRunning()) return false
        val port = engine.settings().port
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    PROBE_CONNECT_TIMEOUT_MS,
                )
                socket.soTimeout = PROBE_READ_TIMEOUT_MS
                val output = socket.getOutputStream()
                output.write(PROBE_REQUEST.toByteArray(Charsets.US_ASCII))
                output.flush()
                val statusLine = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.US_ASCII),
                ).readLine()
                statusLine != null && statusLine.contains(" 200")
            }
        }.getOrDefault(false)
    }

    /** 探活失败后的重启：先清掉僵尸 server 对象再重新 bind。 */
    private fun restartEngine(): Boolean {
        val engine = applicationContext.gatewayEngine
        return runCatching {
            engine.requestLog.warn("看门狗：端口无响应，正在重启引擎")
            engine.stop()
            engine.start()
            true
        }.onFailure {
            engine.requestLog.warn("看门狗：引擎重启失败（${it.message}）")
        }.getOrDefault(false)
    }

    /**
     * 连续 [WATCHDOG_MAX_FAILURES] 次救不回来：撤掉「运行中」通知、补一条失败说明、
     * 停掉服务，不再假装网关还活着。
     */
    private fun onEngineUnrecoverable() {
        val engine = runCatching { applicationContext.gatewayEngine }.getOrNull()
        engine?.requestLog?.warn(
            "看门狗：连续 $WATCHDOG_MAX_FAILURES 次无法恢复端口监听，已停止前台服务",
        )
        runCatching { notifyUnrecoverable() }
        watchdogHandler.post {
            active = false
            stopSelf()
        }
    }

    private fun notifyUnrecoverable() {
        val notification = NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("本地网关已停止：端口无法恢复监听，请打开应用重新启动")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(this).notify(FAILED_NOTIFICATION_ID, notification)
    }

    // ------------------------------------------------------------------ 锁与悬浮窗

    /**
     * 锁屏后 CPU 与 Wi-Fi 会进省电，已有的 TCP 连接会被挂起，客户端只会看到「连接断开」；
     * 前台服务只保证进程不被杀，不保证网络与 CPU 常醒，所以这里显式持锁。
     */
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /**
     * 可选的悬浮窗保活：用户开启时才显示（需要「显示在其他应用上层」权限），
     * 服务停止时一并移除。未授权时静默跳过，不影响网关运行。
     */
    private fun applyOverlay(engine: dev.aigw.core.gateway.GatewayEngine) {
        if (!dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store())) return
        val shown = FloatingKeepAlive.show(this, "网关 ${engine.settings().port}")
        if (shown) {
            engine.requestLog.info("悬浮窗保活已开启")
        } else {
            engine.requestLog.warn("悬浮窗保活已开启但未获授权（显示在其他应用上层），本次未显示")
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(port: Int): Notification {
        return NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("本地网关运行中 · 端口 $port")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001

        /** 看门狗彻底失败时的说明通知，与前台通知（1001）分开。 */
        const val FAILED_NOTIFICATION_ID = 1003

        /** 探活周期：30 秒足以在用户「下一笔请求前」把僵尸态拉回来，又不会空转太多。 */
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        /** 连续多少次探活失败后判定引擎不可恢复。3 次 ≈ 90 秒。 */
        private const val WATCHDOG_MAX_FAILURES = 3

        private const val PROBE_CONNECT_TIMEOUT_MS = 1_000
        private const val PROBE_READ_TIMEOUT_MS = 2_000

        /** 走网关自带的 /healthz：固定 200、不鉴权、不进调用记录。 */
        private const val PROBE_REQUEST =
            "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"

        private const val WAKE_LOCK_TAG = "aigw:gateway"
        private const val WIFI_LOCK_TAG = "aigw:gateway-wifi"
    }
}
