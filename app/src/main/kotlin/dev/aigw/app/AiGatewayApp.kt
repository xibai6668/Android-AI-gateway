package dev.aigw.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.aigw.app.data.EncryptedKeyValueStore
import dev.aigw.app.data.LanAddresses
import dev.aigw.core.gateway.GatewayEngine

/**
 * 应用入口。
 *
 * 登录改为「系统浏览器 + 本地回调监听」后，App 里不再有 WebView，
 * 也就没有独立的 `:login` 进程，引擎在主进程构造即可。
 */
class AiGatewayApp : Application() {

    /** 整个进程共享一个网关引擎。 */
    var engine: GatewayEngine? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val created = GatewayEngine(
            store = EncryptedKeyValueStore(this),
            lanAddressProvider = { LanAddresses.current(this) },
            // 调用记录预载与过期清理改走后台，冷启动首帧不再等全量解密
            preloadCallLogs = false,
            // 完整原文溢出到缓存目录（系统空间不足时可回收）：加密存储只留 8K 摘要，
            // 详情页打开时按记录 id 回读全文
            spillDir = java.io.File(cacheDir, "call-fulltext"),
        )
        engine = created
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

    companion object {
        const val CHANNEL_ID = "aigw"

        /** 实时状态通知渠道（Android 16 Live Updates 走这里）。 */
        const val LIVE_CHANNEL_ID = "aigw_live"
    }
}

/**
 * 取得进程内唯一的网关引擎。
 *
 * 早失败比拿到 null 后静默出错更容易定位。
 */
val Context.gatewayEngine: GatewayEngine
    get() {
        val app = applicationContext as AiGatewayApp
        return app.engine ?: error("网关引擎尚未初始化")
    }
