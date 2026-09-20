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
        engine = GatewayEngine(
            store = EncryptedKeyValueStore(this),
            lanAddressProvider = { LanAddresses.current(this) },
        )
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_desc) },
        )
    }

    companion object {
        const val CHANNEL_ID = "aigw"
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
