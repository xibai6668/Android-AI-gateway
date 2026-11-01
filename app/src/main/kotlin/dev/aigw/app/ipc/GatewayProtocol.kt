package dev.aigw.app.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.gateway.ProviderInfo
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.failover.ModelFailoverSettings
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.security.OutboundEvidence
import dev.aigw.core.security.SecuritySettings
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.LogLine
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats

/**
 * 主进程 ↔ :gateway 进程的命令名与事件名。
 *
 * 所有跨进程调用都经 Messenger 通道：[GatewayIpc.MSG_INVOKE] 同步返回 JSON 结果，
 * [GatewayIpc.MSG_EVENT] 单向推送事件。参数/返回一律 JSON 字符串，
 * 由 [Gson] 直接映射到 core 的数据类，避免为每个方法手写 parcel。
 */
object GatewayCommands {
    const val SNAPSHOT = "snapshot"
    const val RECORDS = "records"
    const val STORAGE = "storage"

    const val GATEWAY_START = "gateway.start"
    const val GATEWAY_STOP = "gateway.stop"

    const val SETTINGS_UPDATE = "settings.update"
    const val SECURITY_UPDATE = "security.update"
    const val FAILOVER_UPDATE = "failover.update"
    const val PROXY_UPDATE = "proxy.update"
    const val EXPOSURE_UPDATE = "exposure.update"

    const val SANITIZER_RELOAD = "sanitizer.reload"
    const val SANITIZER_CLEAR_EVIDENCE = "sanitizer.clearEvidence"

    const val KEEPALIVE_OVERLAY = "keepalive.overlay"
    const val KEEPALIVE_LIVE = "keepalive.live"
    const val OVERLAY_REAPPLY = "overlay.reapply"

    const val PROVIDERS_RELOAD_CUSTOM = "providers.reloadCustom"
    const val PROVIDERS_ENABLED_IDS = "providers.enabledIds"
    const val CUSTOM_UPSERT = "custom.upsert"
    const val CUSTOM_REMOVE = "custom.remove"
    const val CUSTOM_SAVE_MODELS = "custom.saveModels"

    const val PROVIDER_UPDATE_SETTINGS = "provider.updateSettings"

    const val MODELS_STREAM = "models.stream"
    const val MODEL_PROBE = "model.probe"

    const val ACCOUNTS_MIGRATE = "accounts.migrate"
    const val ACCOUNTS_LIST = "accounts.list"
    const val ACCOUNT_GET = "account.get"
    const val ACCOUNT_ADD = "account.add"
    const val ACCOUNT_REMOVE = "account.remove"
    const val ACCOUNT_SET_ENABLED = "account.setEnabled"
    const val ACCOUNT_MOVE_PRIORITY = "account.movePriority"
    const val ACCOUNT_UPDATE_SECRET = "account.updateSecret"
    const val ACCOUNT_IMPORT_JSON = "account.importJson"
    const val ACCOUNT_EXPORT = "account.export"
    const val ACCOUNT_PARSE_TRANSFER = "account.parseTransfer"
    const val ACCOUNT_IMPORT = "account.import"
    const val ACCOUNT_SYNC_CUSTOM = "account.syncCustom"

    const val LOGIN_BEGIN_BROWSER = "login.beginBrowser"
    const val LOGIN_POLL_DEVICE = "login.pollDevice"
    const val LOGIN_SEND_SMS = "login.sendSms"
    const val LOGIN_COMPLETE_SMS = "login.completeSms"

    /** Trae 专属：基于账号凭证重建设备指纹（凭证解析留在引擎侧）。 */
    const val TRAE_REGENERATE = "trae.regenerate"
    const val TRAE_SET_DEVICE_ID = "trae.setDeviceId"

    const val CREDITS_REFRESH = "credits.refresh"
    const val CREDITS_REFRESH_ALL_PROVIDER = "credits.refreshAllProvider"
    const val CREDITS_REFRESH_ALL = "credits.refreshAll"
    const val CREDITS_PACKS = "credits.packs"

    const val TASK_LIST = "task.list"
    const val POINTS_BILLS = "points.bills"

    const val ACTION_PERFORM = "action.perform"
    const val ACTION_PERFORM_ALL = "action.performAll"
    const val ACTION_SUPPORTS = "action.supports"
    const val ACTION_BATCH_TARGETS = "action.batchTargets"

    const val CALL_FULL_TEXT = "call.fullText"
    const val CALL_DELETE = "call.delete"
    const val CALL_CLEAR = "call.clear"
    const val LOG_CLEAR = "log.clear"

    /** 外观/更新等仅 UI 使用的偏好：从引擎存储读出，供主进程一次性迁移到本地。 */
    const val PREFS_EXPORT_UI = "prefs.exportUi"
}

object GatewayEvents {
    const val ACTIVE_REQUESTS = "activeRequests"
    const val ACCOUNTS_CHANGED = "accountsChanged"
    const val STARTUP_DATA = "startupData"
    const val MODELS_CHUNK = "modelsChunk"
    const val BATCH_PROGRESS = "batchProgress"
}

/** Messenger 消息码与 Bundle 键（主进程 ↔ :gateway 的唯一传输通道）。 */
object GatewayIpc {
    const val MSG_INVOKE = 1
    const val MSG_REPLY = 2
    const val MSG_REGISTER = 3
    const val MSG_UNREGISTER = 4
    const val MSG_EVENT = 5

    const val KEY_REQUEST_ID = "requestId"
    const val KEY_CMD = "cmd"
    const val KEY_ARGS = "args"
    const val KEY_RESULT = "result"
    const val KEY_ERROR = "error"
    const val KEY_EVENT = "event"
    const val KEY_PAYLOAD = "payload"
}

/** 跨进程 JSON 编解码。字段名与 core 数据类一致，直接映射。 */
object GatewayJson {
    val gson: Gson = Gson()

    fun toJson(value: Any?): String = gson.toJson(value)

    fun <T> fromJson(json: String?, type: Class<T>): T = gson.fromJson(json, type)

    inline fun <reified T> fromJson(json: String?): T = gson.fromJson(json, object : TypeToken<T>() {}.type)

    fun obj(json: String?): JsonObject =
        if (json.isNullOrBlank()) JsonObject() else gson.fromJson(json, JsonObject::class.java) ?: JsonObject()
}

/**
 * UI 首屏需要的全部只读数据，一次 IPC 取回。
 *
 * 大多数字段直接来自引擎；`providerSettings` / `customApiKeys` / `deviceIds` / `regions` /
 * `checkinTargets` / `taskTargets` 是为「UI 在主线程同步读取」准备的派生镜像，
 * 避免在 Compose 组合期做跨进程调用。
 */
data class UiSnapshot(
    val running: Boolean,
    val port: Int,
    val localUrl: String,
    val lanUrls: List<String>,
    val pool: PoolSummary,
    val today: UsageStats,
    val daily: List<UsageStats>,
    val total: UsageStats,
    val providers: List<ProviderInfo>,
    val accounts: List<AccountStatus>,
    val customProviders: List<CustomProviderConfig>,
    val calls: List<CallRecord>,
    val logs: List<LogLine>,
    val settings: GatewaySettings,
    val proxy: ProxySettings,
    val security: SecuritySettings,
    val evidences: List<OutboundEvidence>,
    val modelExposure: ModelExposureSettings,
    val failover: ModelFailoverSettings,
    val overlayKeepAlive: Boolean,
    val liveNotification: Boolean,
    val loginPending: Boolean = false,
    val providerSettings: Map<String, ProviderSettings> = emptyMap(),
    val customApiKeys: Map<String, String> = emptyMap(),
    val deviceIds: Map<String, String> = emptyMap(),
    val regions: Map<String, String> = emptyMap(),
    val checkinTargets: List<BatchTargetCount> = emptyList(),
    val taskTargets: List<BatchTargetCount> = emptyList(),
)

/** 批量目标概览的一项（供应商显示名 → 会被执行的账号数）。 */
data class BatchTargetCount(val displayName: String, val count: Int)

/** 记录页数据：调用记录（不含大段原文）+ 请求日志。 */
data class RecordsPayload(val calls: List<CallRecord>, val logs: List<LogLine>)

/** 存储占用：明细 + 记录条数。 */
data class StoragePayload(val report: StorageReport, val storedCount: Int)

/**
 * [DeviceAuthPoll]（sealed）的跨进程表示。
 *
 * Gson 不能直接处理 sealed 多态，这里用 [state] 标记三态：
 * `pending` / `success`（带 [uid]/[nickname]）/ `failed`（带 [error]）。
 */
data class DeviceAuthPollDto(
    val state: String,
    val uid: String = "",
    val nickname: String = "",
    val error: String = "",
)

/** Trae 设备指纹操作的返回：出错时 [error] 非空，否则 [secret] 为新凭证。 */
data class DeviceIdResult(val error: String = "", val secret: String = "")
