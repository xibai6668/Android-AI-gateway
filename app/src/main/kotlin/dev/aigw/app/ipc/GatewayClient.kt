package dev.aigw.app.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.google.gson.JsonObject
import dev.aigw.core.gateway.BatchActionResult
import dev.aigw.core.gateway.BatchProgress
import dev.aigw.core.gateway.BatchTarget
import dev.aigw.core.gateway.BrowserLoginTicket
import dev.aigw.core.gateway.CreditRefreshResult
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ModelsCatalog
import dev.aigw.core.gateway.ProbeResult
import dev.aigw.core.gateway.ProviderModelsChunk
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.gateway.ActiveRequest
import dev.aigw.core.pool.AccountImportResult
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.PointsBillListView
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderTaskListView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.usage.CallTextField
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 主进程侧的 :gateway 访问入口。
 *
 * 绑定 [GatewayBridgeService]（Messenger 通道）并把命令包装成与引擎同名的方法：
 * UI 侧（AppViewModel）改调本类即可，不再持有引擎。
 *
 * 同时维护一份「快照镜像」：`providerSettings` / 自定义配置 / 设备指纹 / 区域等
 * 由 [snapshot] 带回并缓存，供 UI 在**主线程同步读取**（Compose 组合期不能做跨进程调用）。
 *
 * 线程约定：除镜像读取外，所有方法都会同步等待跨进程结果，**只能在后台线程调用**。
 */
class GatewayClient(private val app: Context) {

    private val lock = Object()

    /** 网关进程的 Messenger；未连接时为 null。 */
    @Volatile
    private var server: Messenger? = null

    @Volatile
    private var bindRequested = false

    @Volatile
    private var cache: UiSnapshot? = null

    @Volatile
    private var activeRequestsCache: List<ActiveRequest> = emptyList()

    /** 引擎侧账号池变化的通知（登录、导入、凭证回存等），由 UI 设值。 */
    @Volatile
    var onAccountsChanged: (() -> Unit)? = null

    /** 首屏数据（调用记录/统计）在引擎后台就绪后的通知，由 UI 设值。 */
    @Volatile
    var onStartupDataReady: (() -> Unit)? = null

    private val activeRequestsListeners = CopyOnWriteArrayList<(List<ActiveRequest>) -> Unit>()

    /** 模型目录流式分片的监听者（并发两轮刷新时各自保留，互不丢增量）。 */
    private val modelsChunkHandlers = CopyOnWriteArrayList<(ProviderModelsChunk) -> Unit>()

    /** 带进度的批量命令的监听者。 */
    private val batchProgressHandlers = CopyOnWriteArrayList<(BatchProgress) -> Unit>()

    /** 等待中的命令回复；键为请求序号。 */
    private class PendingReply {
        val latch = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var result: String? = null

        @Volatile
        var error: String? = null
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<Int, PendingReply>()
    private val requestSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private lateinit var incomingThread: HandlerThread
    private lateinit var incomingMessenger: Messenger

    /** 接收回复与事件；回复唤醒等待的调用线程，事件分发给监听者。 */
    private fun startIncoming(): Messenger {
        incomingThread = HandlerThread("aigw-bridge-in").apply { start() }
        val handler = object : Handler(incomingThread.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    GatewayIpc.MSG_REPLY -> {
                        val id = msg.data?.getInt(GatewayIpc.KEY_REQUEST_ID) ?: return
                        val reply = pending[id] ?: return
                        reply.result = msg.data?.getString(GatewayIpc.KEY_RESULT)
                        reply.error = msg.data?.getString(GatewayIpc.KEY_ERROR)
                        reply.latch.countDown()
                    }
                    GatewayIpc.MSG_EVENT -> {
                        val event = msg.data?.getString(GatewayIpc.KEY_EVENT) ?: return
                        val payload = msg.data?.getString(GatewayIpc.KEY_PAYLOAD) ?: "{}"
                        dispatchEvent(event, payload)
                    }
                }
            }
        }
        return Messenger(handler)
    }

    private fun dispatchEvent(event: String, payloadJson: String) {
        when (event) {
            GatewayEvents.ACTIVE_REQUESTS -> {
                val list: List<ActiveRequest> = GatewayJson.gson.fromJson(
                    payloadJson,
                    object : com.google.gson.reflect.TypeToken<List<ActiveRequest>>() {}.type,
                )
                activeRequestsCache = list
                for (listener in activeRequestsListeners) runCatching { listener(list) }
            }
            GatewayEvents.ACCOUNTS_CHANGED -> onAccountsChanged?.invoke()
            GatewayEvents.STARTUP_DATA -> onStartupDataReady?.invoke()
            GatewayEvents.MODELS_CHUNK -> {
                val chunk: ProviderModelsChunk = GatewayJson.fromJson(payloadJson, ProviderModelsChunk::class.java)
                for (handler in modelsChunkHandlers) runCatching { handler(chunk) }
            }
            GatewayEvents.BATCH_PROGRESS -> {
                val progress: BatchProgress = GatewayJson.fromJson(payloadJson, BatchProgress::class.java)
                for (handler in batchProgressHandlers) runCatching { handler(progress) }
            }
        }
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            synchronized(lock) {
                server = null
                bindRequested = false
            }
            failPending("网关进程已结束")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            runCatching { service.linkToDeath(deathRecipient, 0) }
            synchronized(lock) {
                server = Messenger(service)
                lock.notifyAll()
            }
            runCatching {
                val register = Message.obtain(null, GatewayIpc.MSG_REGISTER).apply { replyTo = incomingMessenger }
                server?.send(register)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            resetConnection()
        }

        override fun onBindingDied(name: ComponentName?) {
            resetConnection()
        }

        override fun onNullBinding(name: ComponentName?) {
            resetConnection()
        }
    }

    private fun resetConnection() {
        synchronized(lock) {
            server = null
            bindRequested = false
        }
        // 连接断了就把在途请求一次性唤醒，别让调用方白等到超时
        failPending("网关连接已断开")
    }

    private fun failPending(reason: String) {
        for (id in pending.keys.toList()) {
            val reply = pending.remove(id) ?: continue
            reply.error = reason
            reply.latch.countDown()
        }
    }

    // ------------------------------------------------------------------ 连接

    private fun ensureBound() {
        if (bindRequested) return
        synchronized(lock) {
            if (bindRequested) return
            if (!::incomingMessenger.isInitialized) incomingMessenger = startIncoming()
            val intent = Intent(app, GatewayBridgeService::class.java)
            bindRequested = runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        }
    }

    private fun awaitServer(timeoutMillis: Long = BIND_TIMEOUT_MS): Messenger {
        ensureBound()
        val deadline = System.currentTimeMillis() + timeoutMillis
        synchronized(lock) {
            while (server == null) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) throw IllegalStateException("网关进程未就绪")
                lock.wait(remain)
            }
            return server!!
        }
    }

    fun isConnected(): Boolean = server != null

    fun activeRequests(): List<ActiveRequest> = activeRequestsCache

    fun addActiveRequestsListener(listener: (List<ActiveRequest>) -> Unit) {
        activeRequestsListeners.add(listener)
    }

    fun removeActiveRequestsListener(listener: (List<ActiveRequest>) -> Unit) {
        activeRequestsListeners.remove(listener)
    }

    // ------------------------------------------------------------------ 底层调用

    private fun invoke(cmd: String, argsJson: String): String {
        val messenger = awaitServer()
        val id = requestSeq.incrementAndGet()
        val reply = PendingReply()
        pending[id] = reply
        val message = Message.obtain(null, GatewayIpc.MSG_INVOKE).apply {
            data = Bundle().apply {
                putInt(GatewayIpc.KEY_REQUEST_ID, id)
                putString(GatewayIpc.KEY_CMD, cmd)
                putString(GatewayIpc.KEY_ARGS, argsJson)
            }
            replyTo = incomingMessenger
        }
        if (!runCatching { messenger.send(message) }.isSuccess) {
            pending.remove(id)
            resetConnection()
            throw IllegalStateException("网关进程已断开，请重试")
        }
        if (!reply.latch.await(INVOKE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            pending.remove(id)
            throw IllegalStateException("网关调用超时：$cmd")
        }
        pending.remove(id)
        reply.error?.let { throw IllegalStateException(it) }
        return reply.result ?: "null"
    }

    private fun invokeArgs(cmd: String, args: Any?): String =
        invoke(cmd, if (args == null) "{}" else GatewayJson.gson.toJson(args))

    private inline fun <reified T> invokeAs(cmd: String, args: Any? = null): T =
        GatewayJson.gson.fromJson(invokeArgs(cmd, args), object : com.google.gson.reflect.TypeToken<T>() {}.type)

    // ------------------------------------------------------------------ 快照与镜像

    fun snapshot(): UiSnapshot {
        val snapshot: UiSnapshot = invokeAs(GatewayCommands.SNAPSHOT)
        cache = snapshot
        return snapshot
    }

    fun records(): RecordsPayload = invokeAs(GatewayCommands.RECORDS)

    fun storage(): StoragePayload = invokeAs(GatewayCommands.STORAGE)

    fun providerSettings(providerId: String): ProviderSettings =
        cache?.providerSettings?.get(providerId) ?: ProviderSettings()

    fun customProviderConfig(providerId: String): CustomProviderConfig? =
        cache?.customProviders?.firstOrNull { it.providerId == providerId }

    fun customApiKey(providerId: String, uid: String): String =
        cache?.customApiKeys?.get("$providerId/$uid").orEmpty()

    fun deviceId(providerId: String, uid: String): String =
        cache?.deviceIds?.get("$providerId/$uid").orEmpty()

    fun region(providerId: String, uid: String): String =
        cache?.regions?.get("$providerId/$uid").orEmpty()

    fun checkinTargets(): List<BatchTargetCount> = cache?.checkinTargets.orEmpty()

    fun taskTargets(): List<BatchTargetCount> = cache?.taskTargets.orEmpty()

    fun cachedSnapshot(): UiSnapshot? = cache

    // 主线程用：直接读镜像，不跨进程
    fun isRunningCached(): Boolean = cache?.running ?: false

    // ------------------------------------------------------------------ 网关生命周期

    fun startGateway() {
        invokeArgs(GatewayCommands.GATEWAY_START, null)
    }

    fun stopGateway() {
        invokeArgs(GatewayCommands.GATEWAY_STOP, null)
    }

    // ------------------------------------------------------------------ 设置

    fun updateSettings(settings: GatewaySettings) =
        invokeArgs(GatewayCommands.SETTINGS_UPDATE, settings).let { }

    fun updateSecuritySettings(settings: dev.aigw.core.security.SecuritySettings) =
        invokeArgs(GatewayCommands.SECURITY_UPDATE, settings).let { }

    fun updateFailoverSettings(settings: dev.aigw.core.failover.ModelFailoverSettings) =
        invokeArgs(GatewayCommands.FAILOVER_UPDATE, settings).let { }

    fun updateProxySettings(settings: ProxySettings) =
        invokeArgs(GatewayCommands.PROXY_UPDATE, settings).let { }

    fun updateModelExposure(settings: ModelExposureSettings) =
        invokeArgs(GatewayCommands.EXPOSURE_UPDATE, settings).let { }

    fun reloadSanitizerPipeline(): Int = invokeAs(GatewayCommands.SANITIZER_RELOAD)

    fun clearEvidences() = invokeArgs(GatewayCommands.SANITIZER_CLEAR_EVIDENCE, null).let { }

    fun setOverlayKeepAlive(enabled: Boolean): String =
        invokeAs(GatewayCommands.KEEPALIVE_OVERLAY, mapOf("enabled" to enabled))

    fun setLiveNotification(enabled: Boolean): String =
        invokeAs(GatewayCommands.KEEPALIVE_LIVE, mapOf("enabled" to enabled))

    fun reapplyOverlay() = invokeArgs(GatewayCommands.OVERLAY_REAPPLY, null).let { }

    // ------------------------------------------------------------------ 供应商

    fun reloadCustomProviders() = invokeArgs(GatewayCommands.PROVIDERS_RELOAD_CUSTOM, null).let { }

    fun enabledProviderIds(): List<String> = invokeAs(GatewayCommands.PROVIDERS_ENABLED_IDS)

    fun saveCustomProvider(config: CustomProviderConfig) =
        invokeArgs(GatewayCommands.CUSTOM_UPSERT, config).let { }

    fun removeCustomProvider(key: String) =
        invokeArgs(GatewayCommands.CUSTOM_REMOVE, mapOf("key" to key)).let { }

    fun saveCustomModels(providerId: String, models: List<String>) =
        invokeArgs(GatewayCommands.CUSTOM_SAVE_MODELS, mapOf("providerId" to providerId, "models" to models)).let { }

    fun updateProviderSettings(providerId: String, settings: ProviderSettings) {
        invokeArgs(GatewayCommands.PROVIDER_UPDATE_SETTINGS, mapOf("providerId" to providerId, "settings" to settings))
        // 镜像同步更新，避免刚保存完读回旧值
        cache?.let { snapshot ->
            cache = snapshot.copy(providerSettings = snapshot.providerSettings + (providerId to settings))
        }
    }

    fun syncCustomAccounts(config: CustomProviderConfig) =
        invokeArgs(GatewayCommands.ACCOUNT_SYNC_CUSTOM, config).let { }

    // ------------------------------------------------------------------ 模型

    fun modelsStreaming(forceRefresh: Boolean, onChunk: (ProviderModelsChunk) -> Unit): ModelsCatalog {
        modelsChunkHandlers.add(onChunk)
        try {
            return invokeAs(GatewayCommands.MODELS_STREAM, mapOf("forceRefresh" to forceRefresh))
        } finally {
            modelsChunkHandlers.remove(onChunk)
        }
    }

    fun probeModelLatency(providerId: String, modelId: String, region: String? = null): ProbeResult =
        invokeAs(
            GatewayCommands.MODEL_PROBE,
            mapOf("providerId" to providerId, "modelId" to modelId, "region" to region),
        )

    // ------------------------------------------------------------------ 账号

    fun migrateAccounts(): Int = invokeAs(GatewayCommands.ACCOUNTS_MIGRATE)

    fun accounts(): List<dev.aigw.core.pool.AccountStatus> = invokeAs(GatewayCommands.ACCOUNTS_LIST)

    fun account(providerId: String, uid: String): ProviderAccount? =
        invokeAs(GatewayCommands.ACCOUNT_GET, mapOf("providerId" to providerId, "uid" to uid))

    fun addAccount(providerId: String, uid: String, nickname: String, secret: String): LoginOutcome =
        invokeAs(
            GatewayCommands.ACCOUNT_ADD,
            mapOf("providerId" to providerId, "uid" to uid, "nickname" to nickname, "secret" to secret),
        )

    fun removeAccount(providerId: String, uid: String): Boolean =
        invokeAs(GatewayCommands.ACCOUNT_REMOVE, mapOf("providerId" to providerId, "uid" to uid))

    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean) =
        invokeArgs(GatewayCommands.ACCOUNT_SET_ENABLED, mapOf("providerId" to providerId, "uid" to uid, "enabled" to enabled)).let { }

    fun moveAccountPriority(providerId: String, uid: String, up: Boolean): Boolean =
        invokeAs(GatewayCommands.ACCOUNT_MOVE_PRIORITY, mapOf("providerId" to providerId, "uid" to uid, "up" to up))

    fun updateAccountSecret(providerId: String, uid: String, secret: String) =
        invokeArgs(GatewayCommands.ACCOUNT_UPDATE_SECRET, mapOf("providerId" to providerId, "uid" to uid, "secret" to secret)).let { }

    fun importAccountJson(providerId: String, raw: String): LoginOutcome =
        invokeAs(GatewayCommands.ACCOUNT_IMPORT_JSON, mapOf("providerId" to providerId, "raw" to raw))

    fun exportAccounts(selection: Set<String>): String =
        invokeAs(GatewayCommands.ACCOUNT_EXPORT, mapOf("selection" to selection))

    fun parseAccountTransfer(json: String): List<ProviderAccount> =
        invokeAs(GatewayCommands.ACCOUNT_PARSE_TRANSFER, mapOf("json" to json))

    fun importAccounts(json: String, overwrite: Boolean, selection: Set<String>): AccountImportResult =
        invokeAs(
            GatewayCommands.ACCOUNT_IMPORT,
            mapOf("json" to json, "overwrite" to overwrite, "selection" to selection),
        )

    // ------------------------------------------------------------------ 登录

    fun beginBrowserLogin(providerId: String, region: String = ""): BrowserLoginTicket =
        invokeAs(GatewayCommands.LOGIN_BEGIN_BROWSER, mapOf("providerId" to providerId, "region" to region))

    fun pollDeviceAuth(providerId: String, state: String, region: String = ""): DeviceAuthPoll {
        val dto: DeviceAuthPollDto =
            invokeAs(GatewayCommands.LOGIN_POLL_DEVICE, mapOf("providerId" to providerId, "state" to state, "region" to region))
        return when (dto.state) {
            "success" -> DeviceAuthPoll.Success(ProviderAccount(providerId, dto.uid, dto.nickname, ""))
            "failed" -> DeviceAuthPoll.Failed(dto.error)
            else -> DeviceAuthPoll.Pending
        }
    }

    fun sendSmsCode(providerId: String, phone: String, ccode: String = "86"): String =
        invokeAs(GatewayCommands.LOGIN_SEND_SMS, mapOf("providerId" to providerId, "phone" to phone, "ccode" to ccode))

    fun completeSmsLogin(
        providerId: String,
        phone: String,
        code: String,
        msgid: String,
        ccode: String = "86",
    ): LoginOutcome =
        invokeAs(
            GatewayCommands.LOGIN_COMPLETE_SMS,
            mapOf(
                "providerId" to providerId, "phone" to phone, "code" to code,
                "msgid" to msgid, "ccode" to ccode,
            ),
        )

    fun regenerateTraeDeviceIds(providerId: String, uid: String): DeviceIdResult =
        invokeAs(GatewayCommands.TRAE_REGENERATE, mapOf("providerId" to providerId, "uid" to uid))

    fun setTraeDeviceId(providerId: String, uid: String, value: String): DeviceIdResult =
        invokeAs(
            GatewayCommands.TRAE_SET_DEVICE_ID,
            mapOf("providerId" to providerId, "uid" to uid, "value" to value),
        )

    // ------------------------------------------------------------------ 额度 / 任务 / 动作

    fun refreshCredits(providerId: String, uid: String): CreditRefreshResult =
        invokeAs(GatewayCommands.CREDITS_REFRESH, mapOf("providerId" to providerId, "uid" to uid))

    fun refreshAllCreditsForProvider(providerId: String): List<CreditRefreshResult> =
        invokeAs(GatewayCommands.CREDITS_REFRESH_ALL_PROVIDER, mapOf("providerId" to providerId))

    fun refreshAllCredits(onProgress: ((BatchProgress) -> Unit)? = null): List<CreditRefreshResult> {
        if (onProgress != null) batchProgressHandlers.add(onProgress)
        try {
            return invokeAs(GatewayCommands.CREDITS_REFRESH_ALL)
        } finally {
            if (onProgress != null) batchProgressHandlers.remove(onProgress)
        }
    }

    fun creditPacks(providerId: String, uid: String): List<QuotaPack> =
        invokeAs(GatewayCommands.CREDITS_PACKS, mapOf("providerId" to providerId, "uid" to uid))

    fun taskList(providerId: String, uid: String): ProviderTaskListView? =
        invokeAs(GatewayCommands.TASK_LIST, mapOf("providerId" to providerId, "uid" to uid))

    fun pointsBills(providerId: String, uid: String): PointsBillListView? =
        invokeAs(GatewayCommands.POINTS_BILLS, mapOf("providerId" to providerId, "uid" to uid))

    fun performAction(providerId: String, uid: String, action: String, payload: JsonObject = JsonObject()): ProviderActionResult =
        invokeAs(
            GatewayCommands.ACTION_PERFORM,
            mapOf("providerId" to providerId, "uid" to uid, "action" to action, "payload" to payload),
        )

    fun performActionAll(
        targets: List<BatchTarget>,
        action: String,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<BatchActionResult> {
        if (onProgress != null) batchProgressHandlers.add(onProgress)
        try {
            return invokeAs(
                GatewayCommands.ACTION_PERFORM_ALL,
                mapOf(
                    "targets" to targets.map { mapOf("providerId" to it.providerId, "uid" to it.uid) },
                    "action" to action,
                ),
            )
        } finally {
            if (onProgress != null) batchProgressHandlers.remove(onProgress)
        }
    }

    fun supportsAction(providerId: String, uid: String, action: String): Boolean =
        invokeAs(GatewayCommands.ACTION_SUPPORTS, mapOf("providerId" to providerId, "uid" to uid, "action" to action))

    fun batchTargets(action: String): List<BatchTarget> =
        invokeAs(GatewayCommands.ACTION_BATCH_TARGETS, mapOf("action" to action))

    // ------------------------------------------------------------------ 记录

    fun callFullText(id: String, field: CallTextField): String? =
        invokeAs(GatewayCommands.CALL_FULL_TEXT, mapOf("id" to id, "field" to field.name))

    fun deleteCall(id: String) = invokeArgs(GatewayCommands.CALL_DELETE, mapOf("id" to id)).let { }

    fun clearCalls() = invokeArgs(GatewayCommands.CALL_CLEAR, null).let { }

    fun clearLogs() = invokeArgs(GatewayCommands.LOG_CLEAR, null).let { }

    // ------------------------------------------------------------------ 偏好迁移

    /** 从引擎存储里导出仅 UI 使用的偏好键（外观/更新），供主进程一次性迁移到本地存储。 */
    fun exportUiPrefs(): Map<String, String> = invokeAs(GatewayCommands.PREFS_EXPORT_UI)

    private companion object {
        /** 首次绑定可能要等 :gateway 进程冷启动并构造引擎。 */
        const val BIND_TIMEOUT_MS = 6_000L

        /** 单个命令的最长等待：批量动作/模型目录拉取可能跑几分钟。 */
        const val INVOKE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
