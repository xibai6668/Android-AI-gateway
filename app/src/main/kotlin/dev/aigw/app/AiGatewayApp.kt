package dev.aigw.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import dev.aigw.app.data.EncryptedKeyValueStore
import dev.aigw.app.data.LanAddresses
import dev.aigw.app.ipc.GatewayClient
import dev.aigw.core.gateway.GatewayEngine

/**
 * 应用入口。
 *
 * 引擎（含 HTTP 服务与账号池）只存在于 `:gateway` 进程：主进程（UI）不构造引擎，
 * 经 [GatewayClient] 访问，这样 App 退到后台后主进程可被系统回收，只留下网关进程。
 */
class AiGatewayApp : Application() {

    /** 仅 `:gateway` 进程非空。 */
    var engine: GatewayEngine? = null
        private set

    /** 主进程侧的网关访问入口；`:gateway` 进程不使用。 */
    val client: GatewayClient by lazy { GatewayClient(this) }

    override fun onCreate() {
        super.onCreate()
        if (isGatewayProcess) {
            engine = GatewayEngine(
                store = EncryptedKeyValueStore(this),
                lanAddressProvider = { LanAddresses.current(this) },
                // 调用记录预载与过期清理改走后台，冷启动首帧不再等全量解密
                preloadCallLogs = false,
                // 完整原文溢出到缓存目录（系统空间不足时可回收）：加密存储只留 8K 摘要，
                // 详情页打开时按记录 id 回读全文
                spillDir = java.io.File(cacheDir, "call-fulltext"),
            )
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        getSystemService<NotificationManager>()?.apply {
            createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.channel_desc) },
            )
            // 实时状态通知走独立渠道：无声无震。IMPORTANCE_LOW 是下限——
            // IMPORTANCE_MIN 的通知不满足 Android 16 实时更新的提升要件。
            createNotificationChannel(
                NotificationChannel(
                    LIVE_CHANNEL_ID,
                    "实时状态",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "仅在网关处理请求时显示，点击回到应用"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                },
            )
        }
    }

    /** 当前是否运行在网关子进程。 */
    val isGatewayProcess: Boolean
        get() = currentProcessName() == "$packageName$GATEWAY_PROCESS_SUFFIX"

    companion object {
        const val CHANNEL_ID = "aigw"

        /** 实时状态通知渠道（Android 16 Live Updates 走这里）。 */
        const val LIVE_CHANNEL_ID = "aigw_live"

        /** 网关数据面所在的独立进程后缀。 */
        const val GATEWAY_PROCESS_SUFFIX = ":gateway"
    }
}

/** 当前进程名；API 28 以下回落到 /proc/self/cmdline。 */
internal fun currentProcessName(): String = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
    }
}.getOrDefault("")

/**
 * 取得进程内唯一的网关引擎。
 *
 * 只在 `:gateway` 进程可用；主进程应改用 [AiGatewayApp.client]，拿不到就早失败。
 */
val Context.gatewayEngine: GatewayEngine
    get() {
        val app = applicationContext as AiGatewayApp
        return app.engine ?: error("网关引擎尚未初始化（只能在 :gateway 进程访问）")
    }
