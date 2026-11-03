package dev.aigw.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.aigw.app.AiGatewayApp
import dev.aigw.app.MainActivity
import dev.aigw.app.R

/**
 * 浏览器登录期间临时拉起的前台服务：只负责把进程抬离「后台 idle」，不碰引擎。
 *
 * 登录用的回环回调服务器跑在本进程里（[dev.aigw.core.gateway.LoopbackCallbackServer]）。
 * 进程一旦被系统判为 idle 就会被冻结，冻结后监听 socket 不再 accept，浏览器把授权结果
 * 重定向过来只能停在 listen backlog 里没人应答 —— 表现就是授权页点完确认后一直转圈。
 * 实测 MIUI 在切到外部浏览器后 3 秒即把本应用从 adj 200 降到 adj 700（idle）。
 *
 * 前台服务让进程在后台期间保持可见级 adj，这是 rikkahub 处理同一问题的做法
 * （OAuthCallbackForegroundService 的职责注释即「在浏览器授权期间保持应用进程和
 * loopback callback server 活跃」）。Custom Tab 只解决外观与返回栈，保不住端口。
 *
 * 生命周期：登录发起时启动，[TIMEOUT_MS] 后自行退出，不常驻；用户可见一条低优先级通知。
 */
class LoginWaitService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知必须先发：startForegroundService 拉起的服务若不在数秒内 startForeground，
        // 系统会判定超时并直接崩溃（ForegroundServiceDidNotStartInTime）
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.login_wait_title))
            .setContentText(getString(R.string.login_wait_text))
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1002

        /**
         * 引擎侧的浏览器回调窗口是 5 分钟（GatewayEngine.CALLBACK_TIMEOUT_MS），
         * 服务多留一分钟余量后自行退出。
         */
        private const val TIMEOUT_MS = 6L * 60 * 1000

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LoginWaitService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LoginWaitService::class.java)) }
        }
    }
}
