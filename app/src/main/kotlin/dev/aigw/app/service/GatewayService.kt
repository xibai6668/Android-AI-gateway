package dev.aigw.app.service

import android.app.Notification
import android.app.NotificationManager
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
import dev.aigw.app.MainActivity
import dev.aigw.app.R
import dev.aigw.app.AiGatewayApp
import dev.aigw.app.gatewayEngine

/**
 * 只负责「让进程活着」：网关本身由 UI 侧启动，成功后拉起本服务，
 * 这样锁屏或切后台后端口仍有人监听。
 */
class GatewayService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = applicationContext.gatewayEngine
        // 进程被系统回收后 START_STICKY 会重建服务，这里顺便把网关也拉起来，
        // 避免出现「通知说运行中、端口实际上没人听」的不一致状态。
        // startForeground 必须在 stopSelf 之前调：startForegroundService 拉起的服务
        // 不调它会被系统判定超时直接崩溃（ForegroundServiceDidNotStartInTime）。
        val started = engine.isRunning() || runCatching { engine.start() }.isSuccess
        startForeground(NOTIFICATION_ID, buildNotification(engine.settings().port))
        if (started) {
            engine.requestLog.info("前台服务已拉起，通知已发布")
            scheduleNotificationCheck()
        }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        return START_STICKY
    }

    override fun onDestroy() {
        retryHandler.removeCallbacksAndMessages(null)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
     * 冷启动后第一次 post 的常驻通知偶发被系统静默吞掉（服务活着、通知栏里却没有），
     * 重启一次服务才出现。这里延迟自查状态栏：发现同 ID 通知不在就补发一次（同 ID 幂等）。
     */
    private fun scheduleNotificationCheck() {
        val engine = applicationContext.gatewayEngine
        retryHandler.postDelayed({
            val visible = getSystemService(NotificationManager::class.java)
                ?.activeNotifications?.any { it.id == NOTIFICATION_ID } == true
            if (!visible) {
                runCatching {
                    NotificationManagerCompat.from(this)
                        .notify(NOTIFICATION_ID, buildNotification(engine.settings().port))
                }.onFailure {
                    engine.requestLog.warn("通知补发失败：${it.message}")
                }
                engine.requestLog.warn("检测到常驻通知缺失，已补发")
            }
        }, NOTIFICATION_RETRY_MILLIS)
    }

    private fun buildNotification(port: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("本地网关运行中 · 端口 $port")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "aigw:gateway"
        private const val WIFI_LOCK_TAG = "aigw:gateway-wifi"
        private const val NOTIFICATION_RETRY_MILLIS = 1_500L
    }

    /** 通知缺失自查用的主线程调度器，onDestroy 时清空。 */
    private val retryHandler = Handler(Looper.getMainLooper())
}
