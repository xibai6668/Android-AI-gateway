package dev.aigw.app.ipc

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.aigw.app.gatewayEngine
import dev.aigw.core.gateway.BatchProgress
import dev.aigw.core.gateway.BatchTarget
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.RegionAwareSupport
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.usage.CallTextField

/**
 * 只在 :gateway 进程运行的绑定服务：把主进程的命令翻译成引擎调用。
 *
 * 引擎（含 HTTP 服务、账号池、存储）是 :gateway 进程的唯一数据面；
 * 主进程不持有引擎，一律经本服务访问，从而在 App 退到后台时只保留本进程。
 *
 * 本服务只 `onBind`（不 `start`）：网关关闭且 UI 解绑后随之销毁，
 * :gateway 进程也就不会被 UI 的绑定「钉」住。
 */
class GatewayBridgeService : Service() {

    /** 已注册的客户端（主进程）Messenger，按 binder 去重，随客户端进程死亡自动移除。 */
    private val clients = java.util.concurrent.ConcurrentHashMap<IBinder, Messenger>()

    /** 命令执行线程池：单个长命令（如模型目录拉取）不得阻塞其它请求。 */
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "aigw-bridge").apply { isDaemon = true }
    }

    private lateinit var incomingThread: HandlerThread
    private lateinit var bridgeMessenger: Messenger

    private val activeRequestsListener: (List<dev.aigw.core.gateway.ActiveRequest>) -> Unit = { list ->
        broadcast(GatewayEvents.ACTIVE_REQUESTS, GatewayJson.toJson(list))
    }

    override fun onCreate() {
        super.onCreate()
        incomingThread = HandlerThread("aigw-bridge-in").apply { start() }
        val handler = object : Handler(incomingThread.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    GatewayIpc.MSG_INVOKE -> handleInvoke(msg)
                    GatewayIpc.MSG_REGISTER -> registerClient(msg)
                    GatewayIpc.MSG_UNREGISTER -> unregisterClient(msg)
                }
            }
        }
        bridgeMessenger = Messenger(handler)
        wireEngineEvents()
    }

    override fun onBind(intent: Intent?): IBinder = bridgeMessenger.binder

    override fun onDestroy() {
        unwireEngineEvents()
        clients.clear()
        incomingThread.quitSafely()
        pool.shutdownNow()
        super.onDestroy()
    }

    /** 命令在池线程执行，结果经 replyTo 回发；不阻塞接收线程。 */
    private fun handleInvoke(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val data = msg.data ?: return
        val requestId = data.getInt(GatewayIpc.KEY_REQUEST_ID)
        val cmd = data.getString(GatewayIpc.KEY_CMD).orEmpty()
        val args = data.getString(GatewayIpc.KEY_ARGS) ?: "{}"
        pool.execute {
            val bundle = Bundle().apply { putInt(GatewayIpc.KEY_REQUEST_ID, requestId) }
            try {
                bundle.putString(GatewayIpc.KEY_RESULT, dispatch(cmd, args))
            } catch (e: Throwable) {
                bundle.putString(GatewayIpc.KEY_ERROR, e.message ?: "命令执行失败")
            }
            val reply = Message.obtain(null, GatewayIpc.MSG_REPLY).apply { this.data = bundle }
            runCatching { replyTo.send(reply) }
        }
    }

    private fun registerClient(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val binder = replyTo.binder
        val previous = clients.put(binder, replyTo)
        if (previous == null) {
            val recipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    clients.remove(binder)
                }
            }
            runCatching { binder.linkToDeath(recipient, 0) }
        }
        // 补一次当前在途请求：UI 首次绑定后不应等到下一次变化才显示
        sendEvent(replyTo, GatewayEvents.ACTIVE_REQUESTS, GatewayJson.toJson(gatewayEngine.activeRequests.snapshot()))
    }

    private fun unregisterClient(msg: Message) {
        val binder = msg.replyTo?.binder ?: return
        clients.remove(binder)
    }

    // ------------------------------------------------------------------ 事件

    private fun wireEngineEvents() {
        val engine = gatewayEngine
        engine.onAccountsChanged = { broadcast(GatewayEvents.ACCOUNTS_CHANGED, "{}") }
        engine.setStartupDataReadyListener { broadcast(GatewayEvents.STARTUP_DATA, "{}") }
        engine.activeRequests.addListener(activeRequestsListener)
    }

    private fun unwireEngineEvents() {
        runCatching {
            val engine = gatewayEngine
            engine.onAccountsChanged = null
            engine.setStartupDataReadyListener(null)
            engine.activeRequests.removeListener(activeRequestsListener)
        }
    }

    private fun broadcast(event: String, payloadJson: String) {
        for ((binder, messenger) in clients) {
            val message = Message.obtain(null, GatewayIpc.MSG_EVENT).apply {
                data = Bundle().apply {
                    putString(GatewayIpc.KEY_EVENT, event)
                    putString(GatewayIpc.KEY_PAYLOAD, payloadJson)
                }
            }
            runCatching { messenger.send(message) }.onFailure { clients.remove(binder) }
        }
    }

    private fun sendEvent(target: Messenger, event: String, payloadJson: String) {
        val message = Message.obtain(null, GatewayIpc.MSG_EVENT).apply {
            data = Bundle().apply {
                putString(GatewayIpc.KEY_EVENT, event)
                putString(GatewayIpc.KEY_PAYLOAD, payloadJson)
            }
        }
        runCatching { target.send(message) }
    }

    // ------------------------------------------------------------------ 命令分发

    private fun dispatch(cmd: String, argsJson: String): String {
        val engine = gatewayEngine
        val args = GatewayJson.obj(argsJson)
        return when (cmd) {
            GatewayCommands.SNAPSHOT -> GatewayJson.toJson(snapshot(engine))
            GatewayCommands.RECORDS -> GatewayJson.toJson(
                RecordsPayload(
                    calls = engine.callLogStore.list().map { it.withoutBodies() },
                    logs = engine.requestLog.lines(),
                ),
            )
            GatewayCommands.STORAGE -> GatewayJson.toJson(
                StoragePayload(engine.storageReport(), engine.callLogStore.storedCount()),
            )

            GatewayCommands.GATEWAY_START -> { engine.start(); "{}" }
            GatewayCommands.GATEWAY_STOP -> { engine.stop(); "{}" }

            GatewayCommands.SETTINGS_UPDATE -> {
                engine.updateSettings(GatewayJson.fromJson(argsJson, GatewaySettings::class.java)); "{}"
            }
            GatewayCommands.SECURITY_UPDATE -> {
                engine.updateSecuritySettings(
                    GatewayJson.fromJson(argsJson, dev.aigw.core.security.SecuritySettings::class.java),
                ); "{}"
            }
            GatewayCommands.FAILOVER_UPDATE -> {
                engine.updateFailoverSettings(
                    GatewayJson.fromJson(argsJson, dev.aigw.core.failover.ModelFailoverSettings::class.java),
                ); "{}"
            }
            GatewayCommands.PROXY_UPDATE -> {
                engine.updateProxySettings(GatewayJson.fromJson(argsJson, ProxySettings::class.java)); "{}"
            }
            GatewayCommands.EXPOSURE_UPDATE -> {
                engine.updateModelExposure(GatewayJson.fromJson(argsJson, ModelExposureSettings::class.java)); "{}"
            }

            GatewayCommands.SANITIZER_RELOAD -> GatewayJson.toJson(engine.reloadSanitizerPipeline())
            GatewayCommands.SANITIZER_CLEAR_EVIDENCE -> { engine.sanitizer.clearEvidence(); "{}" }

            GatewayCommands.KEEPALIVE_OVERLAY -> {
                val enabled = args.bool("enabled")
                dev.aigw.app.data.KeepAlivePrefs.setOverlayEnabled(engine.store(), enabled)
                var notice = ""
                if (enabled) {
                    if (!engine.isRunning()) {
                        notice = "已开启，悬浮窗将在网关服务启动后显示"
                    } else {
                        val label = "网关 ${engine.settings().port}"
                        val shown = dev.aigw.app.service.FloatingKeepAlive.show(this, label)
                        notice = if (shown) {
                            "悬浮窗保活已开启"
                        } else {
                            "已开启，但缺少「显示在其他应用上层」权限，请在上方授权"
                        }
                    }
                } else {
                    dev.aigw.app.service.FloatingKeepAlive.hide(this)
                    notice = "悬浮窗保活已关闭"
                }
                GatewayJson.toJson(notice)
            }
            GatewayCommands.KEEPALIVE_LIVE -> {
                val enabled = args.bool("enabled")
                dev.aigw.app.data.KeepAlivePrefs.setLiveNotificationEnabled(engine.store(), enabled)
                dev.aigw.app.service.LiveStatusNotifier.setEnabled(this, enabled)
                GatewayJson.toJson(if (enabled) "实时状态通知已开启" else "实时状态通知已关闭")
            }
            GatewayCommands.OVERLAY_REAPPLY -> {
                if (engine.isRunning() &&
                    dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store()) &&
                    !dev.aigw.app.service.FloatingKeepAlive.isShowing()
                ) {
                    dev.aigw.app.service.FloatingKeepAlive.show(this, "网关 ${engine.settings().port}")
                }
                "{}"
            }

            GatewayCommands.PROVIDERS_RELOAD_CUSTOM -> { engine.reloadCustomProviders(); "{}" }
            GatewayCommands.PROVIDERS_ENABLED_IDS -> GatewayJson.toJson(
                engine.providers().filter { it.enabled }.map { it.id },
            )
            GatewayCommands.CUSTOM_UPSERT -> {
                val config = GatewayJson.fromJson(argsJson, CustomProviderConfig::class.java)
                val repository = engine.settingsRepository
                val list = repository.loadCustomProviders().toMutableList()
                val index = list.indexOfFirst { it.key == config.key }
                if (index >= 0) list[index] = config else list.add(config)
                repository.saveCustomProviders(list)
                engine.reloadCustomProviders()
                engine.syncCustomAccounts(config)
                "{}"
            }
            GatewayCommands.CUSTOM_REMOVE -> {
                val key = args.str("key")
                val providerId = "custom:$key"
                for (account in engine.accounts(providerId)) {
                    engine.removeAccount(providerId, account.uid)
                }
                val repository = engine.settingsRepository
                repository.saveCustomProviders(repository.loadCustomProviders().filterNot { it.key == key })
                engine.reloadCustomProviders()
                "{}"
            }
            GatewayCommands.CUSTOM_SAVE_MODELS -> {
                val providerId = args.str("providerId")
                val models = args.strList("models")
                val repository = engine.settingsRepository
                val list = repository.loadCustomProviders()
                if (list.none { it.providerId == providerId }) return "{}"
                repository.saveCustomProviders(
                    list.map { if (it.providerId == providerId) it.copy(models = models) else it },
                )
                engine.reloadCustomProviders()
                "{}"
            }

            GatewayCommands.PROVIDER_UPDATE_SETTINGS -> {
                val providerId = args.str("providerId")
                val settings = GatewayJson.fromJson(args.get("settings").toString(), ProviderSettings::class.java)
                engine.updateProviderSettings(providerId, settings)
                "{}"
            }

            GatewayCommands.MODELS_STREAM -> {
                val forceRefresh = args.bool("forceRefresh")
                val catalog = engine.modelsStreaming(forceRefresh = forceRefresh) { chunk ->
                    broadcast(GatewayEvents.MODELS_CHUNK, GatewayJson.toJson(chunk))
                }
                GatewayJson.toJson(catalog)
            }
            GatewayCommands.MODEL_PROBE -> {
                val region = args.strOrNull("region")
                GatewayJson.toJson(
                    engine.probeModelLatency(args.str("providerId"), args.str("modelId"), region),
                )
            }

            GatewayCommands.ACCOUNTS_MIGRATE -> GatewayJson.toJson(engine.migrateAccounts())
            GatewayCommands.ACCOUNTS_LIST -> GatewayJson.toJson(engine.accounts())
            GatewayCommands.ACCOUNT_ADD -> {
                val outcome = engine.addAccount(
                    args.str("providerId"), args.str("uid"), args.str("nickname"), args.str("secret"),
                )
                GatewayJson.toJson(outcome)
            }
            GatewayCommands.ACCOUNT_REMOVE -> GatewayJson.toJson(engine.removeAccount(args.str("providerId"), args.str("uid")))
            GatewayCommands.ACCOUNT_SET_ENABLED -> {
                engine.setAccountEnabled(args.str("providerId"), args.str("uid"), args.bool("enabled")); "{}"
            }
            GatewayCommands.ACCOUNT_MOVE_PRIORITY -> GatewayJson.toJson(
                engine.moveAccountPriority(args.str("providerId"), args.str("uid"), args.bool("up")),
            )
            GatewayCommands.ACCOUNT_UPDATE_SECRET -> {
                engine.updateAccountSecret(args.str("providerId"), args.str("uid"), args.str("secret")); "{}"
            }
            GatewayCommands.ACCOUNT_IMPORT_JSON -> GatewayJson.toJson(
                engine.importAccountJson(args.str("providerId"), args.str("raw")),
            )
            GatewayCommands.ACCOUNT_EXPORT -> GatewayJson.toJson(
                engine.exportAccounts(args.strSet("selection")),
            )
            GatewayCommands.ACCOUNT_PARSE_TRANSFER -> GatewayJson.toJson(
                engine.parseAccountTransfer(args.str("json")),
            )
            GatewayCommands.ACCOUNT_IMPORT -> GatewayJson.toJson(
                engine.importAccounts(
                    json = args.str("json"),
                    overwrite = args.bool("overwrite"),
                    selection = args.strSet("selection"),
                ),
            )
            GatewayCommands.ACCOUNT_SYNC_CUSTOM -> {
                engine.syncCustomAccounts(GatewayJson.fromJson(argsJson, CustomProviderConfig::class.java)); "{}"
            }
            GatewayCommands.ACCOUNT_GET -> GatewayJson.toJson(
                engine.account(args.str("providerId"), args.str("uid")),
            )

            GatewayCommands.LOGIN_BEGIN_BROWSER -> GatewayJson.toJson(
                engine.beginBrowserLogin(args.str("providerId"), args.str("region")),
            )
            GatewayCommands.LOGIN_POLL_DEVICE -> GatewayJson.toJson(
                devicePollToDto(engine.pollDeviceAuth(args.str("providerId"), args.str("state"), args.str("region"))),
            )
            GatewayCommands.LOGIN_SEND_SMS -> GatewayJson.toJson(
                engine.sendSmsCode(args.str("providerId"), args.str("phone"), args.str("ccode").ifBlank { "86" }),
            )
            GatewayCommands.LOGIN_COMPLETE_SMS -> GatewayJson.toJson(
                engine.completeSmsLogin(
                    args.str("providerId"),
                    args.str("phone"),
                    args.str("code"),
                    args.str("msgid"),
                    args.str("ccode").ifBlank { "86" },
                ),
            )

            GatewayCommands.TRAE_REGENERATE -> {
                val provider = engine.registry.get(TraeProvider.ID) as? TraeProvider
                val account = engine.account(args.str("providerId"), args.str("uid"))
                when {
                    provider == null -> GatewayJson.toJson(DeviceIdResult(error = "Trae 供应商不可用"))
                    account == null -> GatewayJson.toJson(DeviceIdResult(error = "账号不存在"))
                    else -> GatewayJson.toJson(DeviceIdResult(secret = provider.regenerateDeviceIds(account)))
                }
            }
            GatewayCommands.TRAE_SET_DEVICE_ID -> {
                val provider = engine.registry.get(TraeProvider.ID) as? TraeProvider
                val value = args.str("value")
                val error = provider?.validateDeviceId(value) ?: "Trae 供应商不可用"
                val account = if (error.isEmpty()) {
                    engine.account(args.str("providerId"), args.str("uid"))
                } else {
                    null
                }
                when {
                    error.isNotEmpty() -> GatewayJson.toJson(DeviceIdResult(error = error))
                    account == null -> GatewayJson.toJson(DeviceIdResult(error = "账号不存在"))
                    else -> GatewayJson.toJson(DeviceIdResult(secret = provider!!.withDeviceId(account, value)))
                }
            }

            GatewayCommands.CREDITS_REFRESH -> GatewayJson.toJson(
                engine.refreshCredits(args.str("providerId"), args.str("uid")),
            )
            GatewayCommands.CREDITS_REFRESH_ALL_PROVIDER -> GatewayJson.toJson(
                engine.refreshAllCredits(args.strOrNull("providerId")),
            )
            GatewayCommands.CREDITS_REFRESH_ALL -> GatewayJson.toJson(
                engine.refreshAllCredits(onProgress = { progress -> broadcastProgress(progress) }),
            )
            GatewayCommands.CREDITS_PACKS -> GatewayJson.toJson(
                engine.creditPacks(args.str("providerId"), args.str("uid")),
            )

            GatewayCommands.TASK_LIST -> GatewayJson.toJson(
                engine.taskList(args.str("providerId"), args.str("uid")),
            )
            GatewayCommands.POINTS_BILLS -> GatewayJson.toJson(
                engine.pointsBills(args.str("providerId"), args.str("uid")),
            )

            GatewayCommands.ACTION_PERFORM -> GatewayJson.toJson(
                engine.performAction(
                    args.str("providerId"),
                    args.str("uid"),
                    args.str("action"),
                    args.objOrEmpty("payload"),
                ),
            )
            GatewayCommands.ACTION_PERFORM_ALL -> GatewayJson.toJson(
                engine.performActionAll(
                    targets = args.batchTargets(),
                    action = args.str("action"),
                    onProgress = { progress -> broadcastProgress(progress) },
                ),
            )
            GatewayCommands.ACTION_SUPPORTS -> GatewayJson.toJson(
                engine.supportsAction(args.str("providerId"), args.str("uid"), args.str("action")),
            )
            GatewayCommands.ACTION_BATCH_TARGETS -> GatewayJson.toJson(
                batchTargetsFor(engine, args.str("action")),
            )

            GatewayCommands.CALL_FULL_TEXT -> GatewayJson.toJson(fullTextOf(engine, args))
            GatewayCommands.CALL_DELETE -> { engine.callLogStore.delete(args.str("id")); "{}" }
            GatewayCommands.CALL_CLEAR -> { engine.callLogStore.clear(); "{}" }
            GatewayCommands.LOG_CLEAR -> { engine.requestLog.clear(); "{}" }

            GatewayCommands.PREFS_EXPORT_UI -> GatewayJson.toJson(exportUiPrefs(engine))

            else -> throw IllegalArgumentException("未知命令：$cmd")
        }
    }

    // ------------------------------------------------------------------ 快照与派生镜像

    private fun snapshot(engine: dev.aigw.core.gateway.GatewayEngine): UiSnapshot {
        val accountStatus = engine.accounts()
        val providerSettings = engine.providers().associate { it.id to engine.providerSettings(it.id) }
        return UiSnapshot(
            running = engine.isRunning(),
            port = engine.settings().port,
            localUrl = engine.localUrl(),
            lanUrls = engine.lanUrls(),
            pool = engine.pool.summary(),
            today = engine.callLogStore.todayStats(System.currentTimeMillis()),
            daily = engine.callLogStore.dailyStats(System.currentTimeMillis()),
            total = engine.callLogStore.totalStats(),
            providers = engine.providers(),
            accounts = accountStatus,
            customProviders = engine.settingsRepository.loadCustomProviders(),
            calls = engine.callLogStore.list().map { it.withoutBodies() },
            logs = engine.requestLog.lines(),
            settings = engine.settings(),
            proxy = engine.proxySettings(),
            security = engine.securitySettings(),
            evidences = engine.sanitizer.evidenceList(),
            modelExposure = engine.modelExposureSettings(),
            failover = engine.failoverSettings(),
            overlayKeepAlive = dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store()),
            liveNotification = dev.aigw.app.data.KeepAlivePrefs.liveNotificationEnabled(engine.store()),
            loginPending = engine.loginPending(),
            providerSettings = providerSettings,
            customApiKeys = customApiKeys(engine),
            deviceIds = deviceIds(engine),
            regions = regions(engine),
            checkinTargets = batchTargetsFor(engine, ACTION_CHECKIN),
            taskTargets = batchTargetsFor(engine, ACTION_TASKS),
        )
    }

    /** 自定义供应商账号的 API Key（仅 UI 展示用；从凭证 JSON 里取）。 */
    private fun customApiKeys(engine: dev.aigw.core.gateway.GatewayEngine): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (status in engine.accounts()) {
            if (!status.providerId.startsWith("custom:")) continue
            val account = engine.account(status.providerId, status.uid) ?: continue
            val key = runCatching {
                com.google.gson.JsonParser.parseString(account.secret).asJsonObject.get("apiKey")?.asString.orEmpty()
            }.getOrDefault("")
            if (key.isNotEmpty()) result["${status.providerId}/${status.uid}"] = key
        }
        return result
    }

    /** Trae 账号的设备指纹（仅 UI 展示用）。 */
    private fun deviceIds(engine: dev.aigw.core.gateway.GatewayEngine): Map<String, String> {
        val provider = engine.registry.get(TraeProvider.ID) as? TraeProvider ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (status in engine.accounts(TraeProvider.ID)) {
            val account = engine.account(status.providerId, status.uid) ?: continue
            val deviceId = runCatching { provider.deviceIdOf(account) }.getOrDefault("")
            if (deviceId.isNotEmpty()) result["${status.providerId}/${status.uid}"] = deviceId
        }
        return result
    }

    /** 区域型供应商的账号所属区域（仅 UI 展示用）。 */
    private fun regions(engine: dev.aigw.core.gateway.GatewayEngine): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (provider in engine.registry.all()) {
            val regionAware = provider as? RegionAwareSupport ?: continue
            for (status in engine.accounts(provider.id)) {
                val account = engine.account(provider.id, status.uid) ?: continue
                val region = runCatching { regionAware.regionOf(account).orEmpty() }.getOrDefault("")
                if (region.isNotEmpty()) result["${provider.id}/${status.uid}"] = region
            }
        }
        return result
    }

    private fun batchTargetsFor(engine: dev.aigw.core.gateway.GatewayEngine, action: String): List<BatchTargetCount> {
        val capability = when (action) {
            ACTION_CHECKIN -> ProviderCapability.CHECKIN
            ACTION_TASKS -> ProviderCapability.TASKS
            else -> null
        } ?: return emptyList()
        return engine.providers()
            .filter { capability in it.capabilities }
            .map { info ->
                val count = engine.accounts(info.id).count { engine.supportsAction(it.providerId, it.uid, action) }
                BatchTargetCount(info.displayName, count)
            }
            .filter { it.count > 0 }
    }

    private fun fullTextOf(engine: dev.aigw.core.gateway.GatewayEngine, args: JsonObject): String? {
        val id = args.str("id")
        val fieldName = args.str("field")
        val field = runCatching { CallTextField.valueOf(fieldName) }.getOrNull() ?: return null
        val raw = engine.callLogStore.fullText(id, field) ?: run {
            // 没有溢出文件（未超摘要长度或已清理）时回落记录里的摘要原文
            val record = engine.callLogStore.list().firstOrNull { it.id == id } ?: return null
            when (field) {
                CallTextField.REQUEST -> record.requestBody
                CallTextField.RESPONSE -> record.responseBody
                CallTextField.RAW -> record.rawResponse
            }
        }
        // Binder 单次事务约 1MB：溢出原文可达 512K 字符，直接回传可能超限而静默丢失
        return if (raw.length <= FULL_TEXT_LIMIT) raw else raw.take(FULL_TEXT_LIMIT) + "\n…（原文过长，已截断）"
    }

    private fun exportUiPrefs(engine: dev.aigw.core.gateway.GatewayEngine): Map<String, String> {
        val store = engine.store()
        val result = LinkedHashMap<String, String>()
        for (prefix in UI_PREF_PREFIXES) {
            for (key in store.keys(prefix)) {
                store.read(key)?.let { result[key] = it }
            }
        }
        return result
    }

    private fun broadcastProgress(progress: BatchProgress) {
        broadcast(GatewayEvents.BATCH_PROGRESS, GatewayJson.toJson(progress))
    }

    private fun devicePollToDto(poll: DeviceAuthPoll): DeviceAuthPollDto = when (poll) {
        is DeviceAuthPoll.Pending -> DeviceAuthPollDto("pending")
        is DeviceAuthPoll.Success -> DeviceAuthPollDto("success", poll.account.uid, poll.account.nickname)
        is DeviceAuthPoll.Failed -> DeviceAuthPollDto("failed", error = poll.error)
    }

    private companion object {
        val UI_PREF_PREFIXES = listOf("settings/appearance.", "settings/update.")

        /** 单条回传原文的上限，避免超出 Binder 事务上限而静默丢失。 */
        const val FULL_TEXT_LIMIT = 120_000
    }
}

/** 列表传输时去掉大段原文（Binder 事务有约 1MB 上限），详情页按 id 回读。 */
private fun dev.aigw.core.usage.CallRecord.withoutBodies(): dev.aigw.core.usage.CallRecord =
    copy(requestBody = "", responseBody = "", rawResponse = "")

private fun JsonObject.str(key: String): String =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } ?: ""

private fun JsonObject.strOrNull(key: String): String? = str(key).takeIf { it.isNotEmpty() }

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

private fun JsonObject.strList(key: String): List<String> {
    val arr = get(key)?.takeIf { it.isJsonArray } as? JsonArray ?: return emptyList()
    return arr.mapNotNull { runCatching { it.asString }.getOrNull() }
}

private fun JsonObject.strSet(key: String): Set<String> = strList(key).toSet()

private fun JsonObject.objOrEmpty(key: String): JsonObject =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

private fun JsonObject.batchTargets(): List<BatchTarget> {
    val arr = get("targets")?.takeIf { it.isJsonArray } as? JsonArray ?: return emptyList()
    return arr.mapNotNull { element ->
        val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
        val providerId = obj.str("providerId")
        val uid = obj.str("uid")
        if (providerId.isEmpty() || uid.isEmpty()) null else BatchTarget(providerId, uid)
    }
}
