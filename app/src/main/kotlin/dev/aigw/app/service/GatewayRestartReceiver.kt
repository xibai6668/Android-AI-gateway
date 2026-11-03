package dev.aigw.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.aigw.app.data.KeepAlivePrefs
import dev.aigw.app.gatewayEngine

/**
 * 「划掉之后自己活过来」的执行臂。
 *
 * 背景（真机取证，HyperOS 3 / MIUI）：用户从最近任务上滑清理时，系统以
 * SwipeUpClean 杀掉进程；再过几秒按 START_STICKY 把服务重建起来——但重建出来的
 * 进程会被 MIUI 压在 cached 态，服务的 onStartCommand 迟迟不执行（实测
 * `mAllowWiu_noBinding=DENIED`、`curProcState=10`，应用自己一行日志都打不出来），
 * 网关实际不可用，只能等用户下次打开应用。
 *
 * 绕法：任务被移除的瞬间布置一枚 2 秒后的一次性闹钟。闹钟触发时系统会给应用
 * 约 10 秒的临时白名单窗口（AlarmManager 广播属于后台启动前台服务的豁免场景），
 * 在这个窗口里重新 startForegroundService，网关就能真正回来。
 *
 * 只在「用户希望网关运行」时布钟：用户主动停止后不再自愈，避免变成关不掉的服务。
 */
class GatewayRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val engine = context.applicationContext.gatewayEngine
        if (!KeepAlivePrefs.wantRunning(engine.store())) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }
    }

    companion object {
        private const val ACTION = "dev.aigw.app.action.GATEWAY_RESTART"
        private const val REQUEST_CODE = 1001
        private const val DELAY_MS = 2_000L

        /** 布置一枚一次性自愈闹钟；[DELAY_MS] 后由 [GatewayRestartReceiver] 重新拉起服务。 */
        fun schedule(context: Context) {
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, GatewayRestartReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val triggerAt = SystemClock.elapsedRealtime() + DELAY_MS
            runCatching {
                // 没有精确闹钟权限时退回不精确版本：仍会唤醒，只是可能晚几秒
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
                } else {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
                }
            }
        }
    }
}
