package dev.aigw.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.aigw.app.data.KeepAlivePrefs
import dev.aigw.app.gatewayEngine

/**
 * 开机 / 应用更新后按需恢复网关。
 *
 * 只做一件事：用户显式开启「开机自动恢复」时，把前台服务拉起来（服务自己会拉起引擎）。
 * 开关默认关闭，与 CMFA 的自启做法一致——是否要在开机时占用资源由用户决定，
 * 不是安装即生效。
 *
 * 说明：Android 15 起 BOOT_COMPLETED 拉起前台服务受类型限制，specialUse 属于允许
 * 直接从开机广播启动的类型，因此这里不需要额外豁免。
 */
class GatewayBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit
            else -> return
        }
        val engine = runCatching { context.gatewayEngine }.getOrNull() ?: return
        if (!KeepAlivePrefs.startOnBoot(engine.store())) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GatewayService::class.java),
            )
        }
    }
}
