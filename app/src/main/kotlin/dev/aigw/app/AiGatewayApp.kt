package dev.aigw.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import dev.aigw.app.data.CrashReporter
import dev.aigw.app.data.EncryptedKeyValueStore
import dev.aigw.app.data.LanAddresses
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.store.BatchedKeyValueStore
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.store.SplitKeyValueStore
import java.io.File

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
        // 必须最早安装：晚于任何可能崩溃的初始化就抓不到那次崩溃
        CrashReporter.install(this)
        val created = GatewayEngine(
            store = openStore(),
            lanAddressProvider = { LanAddresses.current(this) },
            // 调用记录预载与过期清理改走后台，冷启动首帧不再等全量解密
            preloadCallLogs = false,
            // 完整原文溢出到缓存目录（系统空间不足时可回收）：加密存储只留 8K 摘要，
            // 详情页打开时按记录 id 回读全文
            spillDir = java.io.File(cacheDir, "call-fulltext"),
            // 更新包与崩溃日志是文件系统数据，不在键值存储里，但是占用的真正大头，
            // 交给引擎一起统计与清理
            storageDirs = {
                listOf(
                    dev.aigw.core.usage.StorageAudit.DirectoryGroup(
                        name = dev.aigw.core.usage.StorageReport.NAME_UPDATE_APK,
                        dir = updateApkDir(),
                        detail = "应用内更新下载的安装包，安装后即可删除",
                        clearable = true,
                    ),
                    dev.aigw.core.usage.StorageAudit.DirectoryGroup(
                        name = dev.aigw.core.usage.StorageReport.NAME_CRASH,
                        dir = CrashReporter.crashDir(this),
                        detail = "崩溃现场日志，最多 10 份",
                        clearable = true,
                    ),
                )
            },
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

        private const val TAG = "AiGatewayApp"
    }

    /**
     * 组装落盘链路。
     *
     * 两层套在同一个加密实现上，各解一个具体问题：
     * - [SplitKeyValueStore]：加密文件每次提交都整份重写，把体积大的调用记录与用量拆到
     *   独立文件，防止日志增长拖慢凭证与设置的读写；
     * - [BatchedKeyValueStore]：日志类写入不再每条同步落盘，而是合并后由后台线程提交，
     *   请求线程不再等 fsync。
     *
     * 注意拆分只对「路由到日志文件」的前缀生效，凭证与设置仍留在主文件同步写——
     * 那部分数据量小且不能丢，用合并换来的延迟不值得。
     */
    private fun openStore(): KeyValueStore {
        val main = EncryptedKeyValueStore(this)
        val logs = EncryptedKeyValueStore(this, EncryptedKeyValueStore.LOG_FILE_NAME)
        val split = SplitKeyValueStore(
            routes = SplitKeyValueStore.LOG_STORE_PREFIXES.associateWith { logs },
            fallback = main,
        )
        // 旧版本把日志与用量存在主文件里：不搬的话升级后调用记录与用量会全空。
        // 搬运是幂等的，且带标记，只在首次启动多付一次扫描代价。
        runCatching { split.migrateLegacy() }
            .onFailure { Log.w(TAG, "日志存储拆分迁移失败，下次启动重试", it) }
        return BatchedKeyValueStore(split)
    }

    /** 应用内更新的安装包缓存目录（与 [AppViewModel.downloadUpdate] 写入的位置一致）。 */
    fun updateApkDir(): File = File(cacheDir, "updates")
}

/** 应用内更新的安装包缓存目录；见 [AiGatewayApp.updateApkDir]。 */
fun Context.updateApkDir(): File = (applicationContext as AiGatewayApp).updateApkDir()

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
