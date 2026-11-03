package dev.aigw.app.data

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** 保活相关的系统状态快照。 */
data class KeepAliveStatus(
    /** 通知权限（API 33+ 需要申请，否则常驻通知不展示）。 */
    val notificationsGranted: Boolean,
    /** 是否已加入电池优化白名单（「不限制耗电」）。 */
    val batteryUnrestricted: Boolean,
    /** 系统是否把本应用限制了后台（API 28+）。 */
    val backgroundRestricted: Boolean,
    /** 是否开启了流量节省模式（可能影响后台联网）。 */
    val dataSaverRestricted: Boolean,
    /** 是否已允许「显示在其他应用上层」（可选的悬浮窗保活需要）。 */
    val overlayGranted: Boolean,
) {
    val allGood: Boolean
        get() = notificationsGranted && batteryUnrestricted && !backgroundRestricted && !dataSaverRestricted
}

/** 可选的悬浮窗保活开关（加密存储，服务与界面共读）。 */
object KeepAlivePrefs {
    private const val KEY_OVERLAY = "keepalive/overlay"
    private const val KEY_LIVE_NOTIFICATION = "keepalive/live_notification"
    private const val KEY_START_ON_BOOT = "keepalive/start_on_boot"
    private const val KEY_WANT_RUNNING = "keepalive/want_running"

    fun overlayEnabled(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_OVERLAY) == "1"

    fun setOverlayEnabled(store: dev.aigw.core.store.KeyValueStore, enabled: Boolean) {
        store.write(KEY_OVERLAY, if (enabled) "1" else "0")
    }

    /**
     * 实时状态通知（Android 16 Live Updates）开关：默认**开启**。
     *
     * 与 [overlayEnabled] 的默认相反：那条通知只在有请求在途时出现，打扰极小，
     * 所以键缺失（从未设过）时视为开启；只有用户显式写过 "0" 才是关闭。
     */
    fun liveNotificationEnabled(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_LIVE_NOTIFICATION) != "0"

    fun setLiveNotificationEnabled(store: dev.aigw.core.store.KeyValueStore, enabled: Boolean) {
        store.write(KEY_LIVE_NOTIFICATION, if (enabled) "1" else "0")
    }

    /**
     * 「开机自动恢复」开关：默认**关闭**。
     *
     * 打开后 [dev.aigw.app.service.GatewayBootReceiver] 会在 BOOT_COMPLETED /
     * MY_PACKAGE_REPLACED 时直接拉起前台服务；关闭时不注册任何后台唤醒路径。
     * 与悬浮窗开关不同，它不依赖任何运行时权限。
     */
    fun startOnBoot(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_START_ON_BOOT) == "1"

    fun setStartOnBoot(store: dev.aigw.core.store.KeyValueStore, enabled: Boolean) {
        store.write(KEY_START_ON_BOOT, if (enabled) "1" else "0")
    }

    /**
     * 用户的「希望网关运行」意图（不是「此刻是否在跑」）。
     *
     * 用来支撑「打开应用即恢复」：系统强行停止（force-stop）会清掉粘性服务与所有
     * 定时唤醒，应用没有任何自救手段——这是 Android 的语义。但只要意图还记着，
     * 下次打开应用就能自动回到上次的状态，不用用户再点一次「启动网关服务」。
     * 用户主动点“停止”时同步清零，避免自动恢复变成“我不想跑它却自己起来”。
     */
    fun wantRunning(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_WANT_RUNNING) == "1"

    fun setWantRunning(store: dev.aigw.core.store.KeyValueStore, running: Boolean) {
        store.write(KEY_WANT_RUNNING, if (running) "1" else "0")
    }
}

/** 保活相关的状态检测与系统设置跳转。 */
object KeepAlive {

    fun status(context: Context): KeepAliveStatus {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        return KeepAliveStatus(
            notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activity?.isBackgroundRestricted == true
            } else {
                false
            },
            dataSaverRestricted = connectivity?.restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            overlayGranted = Settings.canDrawOverlays(context),
        )
    }

    /** 请求加入电池优化白名单（需在 Manifest 声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。 */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** 电池优化设置页（用户拒绝直接授权时的兜底入口）。 */
    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * 国产 ROM 的「自启动 / 后台运行」白名单页。
     *
     * 这些页面都是厂商私有的、未导出的组件，跳转失败是常态，所以逐个尝试，
     * 全部失败时由调用方回退到应用详情页（并提供手动路径说明）。
     */
    fun autoStartIntents(): List<Intent> = listOf(
        // 小米 / 红米
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // 华为 / 荣耀
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        // OPPO / 一加 / realme
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        // vivo / iQOO
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // 魅族 / 三星 / 联想
        ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"),
    ).map { component ->
        Intent().apply {
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 厂商引导的手动路径提示，跳转失败时展示。 */
    fun manualHint(manufacturer: String): String = when {
        manufacturer.contains("xiaomi", true) || manufacturer.contains("redmi", true) ->
            "小米：设置 → 应用设置 → 应用管理 → 本应用 → 省电策略选「无限制」"
        manufacturer.contains("huawei", true) || manufacturer.contains("honor", true) ->
            "华为/荣耀：设置 → 应用 → 应用启动管理 → 本应用改为「手动管理」并勾选全部"
        manufacturer.contains("oppo", true) || manufacturer.contains("realme", true) || manufacturer.contains("oneplus", true) ->
            "OPPO/一加：设置 → 电池 → 更多设置 → 应用耗电管理 → 允许完全后台行为"
        manufacturer.contains("vivo", true) || manufacturer.contains("iqoo", true) ->
            "vivo/iQOO：设置 → 电池 → 后台高耗电 → 允许本应用"
        manufacturer.contains("meizu", true) ->
            "魅族：手机管家 → 权限管理 → 后台管理 → 允许后台运行"
        else ->
            "在系统设置的电池 / 应用管理里，把本应用的省电策略改为「无限制」并允许后台运行"
    }
}
