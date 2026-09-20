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
) {
    val allGood: Boolean
        get() = notificationsGranted && batteryUnrestricted && !backgroundRestricted && !dataSaverRestricted
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
