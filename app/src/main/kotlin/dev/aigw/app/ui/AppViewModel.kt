package dev.aigw.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.app.data.KeepAlive
import dev.aigw.app.data.KeepAliveStatus
import dev.aigw.app.gatewayEngine
import dev.aigw.app.service.GatewayService
import dev.aigw.app.service.GatewayTileService
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.ModelsCatalog
import dev.aigw.core.gateway.ProviderInfo
import dev.aigw.core.gateway.ProviderModelsChunk
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.custom.CustomProvider
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.provider.codebuddy.CodeBuddyProvider
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.LogLine
import dev.aigw.core.usage.StorageAudit
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 验证码重发间隔（秒）：上游有频率限制，连点只会被拒。 */
private const val SMS_RESEND_INTERVAL_MS = 60_000L

/** 项目仓库地址（GitHub）。 */
private const val PROJECT_URL = "https://github.com/xibai6668/Android-AI-gateway"

/** GitHub 最新 Release 接口（API 地址；网页地址 .../releases/latest 返回 HTML，不能当 JSON 解析）。 */
private const val RELEASES_API = "https://api.github.com/repos/xibai6668/Android-AI-gateway/releases/latest"

/** GitHub API 要求请求带 User-Agent。 */
private const val UPDATE_UA = "ai-gateway-android"

/** 外观偏好：是否跟随系统取色。 */
class AppearanceStore(private val store: KeyValueStore) {
    fun dynamicColor(): Boolean = store.read(KEY) == "1"

    fun setDynamicColor(enabled: Boolean) {
        store.write(KEY, if (enabled) "1" else "0")
    }

    private companion object {
        const val KEY = "settings/appearance.dynamicColor"
    }
}

/** 更新检查偏好与上次检查日期。 */
class UpdateStore(private val store: KeyValueStore) {
    /** 是否自动检查更新，默认开启。 */
    fun autoCheck(): Boolean = store.read(KEY_AUTO) != "0"

    fun setAutoCheck(enabled: Boolean) {
        store.write(KEY_AUTO, if (enabled) "1" else "0")
    }

    /** 上次自动检查的日期（epoch day），从未检查过返回 Long.MIN_VALUE。 */
    fun lastCheckEpochDay(): Long = store.read(KEY_LAST)?.toLongOrNull() ?: Long.MIN_VALUE

    fun setLastCheckEpochDay(day: Long) {
        store.write(KEY_LAST, day.toString())
    }

    private companion object {
        const val KEY_AUTO = "settings/update.autoCheck"
        const val KEY_LAST = "settings/update.lastCheckEpochDay"
    }
}

/** 检查更新发现的新版本信息。 */
data class UpdateInfo(val version: String, val url: String, val notes: String)

sealed interface ModelTestStatus {
    data object Testing : ModelTestStatus
    data class Success(val latencyMs: Long) : ModelTestStatus
    data object Timeout : ModelTestStatus
}

/**
 * 单个供应商模型目录的流式加载状态（键为 providerId，见 [AppUiState.modelLoadStates]）。
 * 模型页可用它画骨架屏/逐组点亮的动画，而不必等全部供应商拉完。
 */
sealed interface ModelLoadState {
    /** 已发起、尚未就绪。 */
    data object Loading : ModelLoadState
    /** 就绪，[count] 为该供应商贡献的模型条数（区域型供应商按区域拆分后合计）。 */
    data class Done(val count: Int) : ModelLoadState
    /** 拉取失败，[reason] 为异常消息或供应商自报的目录错误。 */
    data class Failed(val reason: String) : ModelLoadState
}

data class AppUiState(
    val running: Boolean = false,
    val port: Int = GatewaySettings.DEFAULT_PORT,
    val localUrl: String = "",
    val lanUrls: List<String> = emptyList(),
    val pool: PoolSummary = PoolSummary(0, 0, 0, 0, 0, 0),
    val today: UsageStats = UsageStats(),
    /** 最近 30 天的每日聚合（下标 0 最早、末尾今天）。 */
    val daily: List<UsageStats> = emptyList(),
    /** 全部记录汇总（今日 + 历史）。 */
    val total: UsageStats = UsageStats(),
    val providers: List<ProviderInfo> = emptyList(),
    val accounts: List<AccountStatus> = emptyList(),
    val customProviders: List<CustomProviderConfig> = emptyList(),
    val models: List<RoutedModel> = emptyList(),
    val modelsError: String = "",
    /** 模型目录是否正在流式加载（各供应商就绪即增量写入 [models]）。 */
    val modelsLoading: Boolean = false,
    /** 进行中这轮加载里每个供应商的状态，键为 providerId；加载结束后保留终态供回看。 */
    val modelLoadStates: Map<String, ModelLoadState> = emptyMap(),
    /** 模型测速状态，键为 `model.fullId`。 */
    val modelTestLatencies: Map<String, ModelTestStatus> = emptyMap(),
    val calls: List<CallRecord> = emptyList(),
    /** 存储中的记录条数（含未载入内存的，仅供「数据管理」展示）。 */
    val storedCalls: Int = 0,
    /** 已拉取的额度包明细，键为 `providerId/uid`。 */
    val creditPacks: Map<String, List<QuotaPack>> = emptyMap(),
    /** 已拉取的成长任务明细，键为 `providerId/uid`。 */
    val taskLists: Map<String, dev.aigw.core.provider.ProviderTaskListView> = emptyMap(),
    val logs: List<LogLine> = emptyList(),
    val settings: GatewaySettings = GatewaySettings(),
    /** 代理设置。 */
    val proxy: ProxySettings = ProxySettings(),
    val dynamicColor: Boolean = false,
    val keepAlive: KeepAliveStatus? = null,
    /** 存储占用明细（含各类记录与设置）。 */
    val storage: StorageReport? = null,
    /** 安全防护与风控设置。 */
    val security: dev.aigw.core.security.SecuritySettings = dev.aigw.core.security.SecuritySettings(),
    /** 出网取证记录列表。 */
    val evidences: List<dev.aigw.core.security.OutboundEvidence> = emptyList(),
    /** 当前生效的脱敏规则词汇数。 */
    val sanitizerRulesCount: Int = 0,
    val busy: String = "",
    val notice: String = "",
    /** 是否正在检查更新。 */
    val updateChecking: Boolean = false,
    /** 检查到的新版本；非空时界面弹更新窗。 */
    val updateInfo: UpdateInfo? = null,
    /** 是否自动检查更新（启动时每天最多一次）。 */
    val autoCheckUpdate: Boolean = true,
) {
    /** 某个供应商的账号。 */
    fun accountsOf(providerId: String): List<AccountStatus> = accounts.filter { it.providerId == providerId }

    fun providerOf(providerId: String): ProviderInfo? = providers.firstOrNull { it.id == providerId }

    /** 至少有一个可用账号的供应商数量（首页展示「有几个能用」而不是供应商总数）。 */
    val usableProviders: Int get() = providers.count { it.usableCount > 0 }
}

private class Snapshot(
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
    val keepAlive: KeepAliveStatus,
    val security: dev.aigw.core.security.SecuritySettings,
    val evidences: List<dev.aigw.core.security.OutboundEvidence>,
    val sanitizerRulesCount: Int,
)

class AppViewModel(
    private val app: Application,
    private val engine: GatewayEngine = app.gatewayEngine,
) : ViewModel() {

    private val appearance = AppearanceStore(engine.store())
    private val updateStore = UpdateStore(engine.store())

    private val _state = MutableStateFlow(AppUiState(dynamicColor = appearance.dynamicColor(), autoCheckUpdate = updateStore.autoCheck()))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** 短信验证码的 msgid，按供应商暂存（发送与登录是两次调用）。 */
    private val smsMsgIds = HashMap<String, String>()

    /** 下次可重新发送验证码的时间，按供应商记（界面倒计时会随页面重置，不能只靠界面拦）。 */
    private val smsResendAt = HashMap<String, Long>()

    /** 最近一次设备授权登录的 state，供「重新检查授权状态」手动重试。 */
    private val deviceStates = HashMap<String, String>()

    /** 最近一次设备授权登录的区域，保证手动重试轮询与登录时打同一个端点。 */
    private val deviceRegions = HashMap<String, String>()

    /** 账号集合签名：判断账号变化是否真的影响模型目录，避免刷新凭证也重复拉取。 */
    private var lastAccountSignature: String = ""

    /** 流式加载代次：连发多次刷新时，只有最新一轮的增量与终态能写入 state。 */
    @Volatile
    private var modelsGeneration = 0

    init {
        // 登录在浏览器里完成、由网关的回调监听落池，这里接住通知刷新界面。
        // 只有账号集合真的变了才重拉模型：刷新凭证（refreshAccount 回存）也会触发本回调，
        // 那类更新不影响模型列表，重复拉取只是白白多打一轮上游。
        engine.onAccountsChanged = {
            viewModelScope.launch {
                val signature = withContext(Dispatchers.IO) { accountSignature(engine.accounts()) }
                if (signature != lastAccountSignature) {
                    lastAccountSignature = signature
                    refreshModels(silent = true)
                }
                refresh()
            }
        }
        refresh()
        // 启动时在后台静默预拉取一次模型，进入模型页直接秒开（不弹「正在拉取」提示）
        refreshModels(silent = true)
        // 自动检查更新：默认开启，每天最多一次，失败静默
        maybeAutoCheckUpdate()
    }

    private fun accountSignature(accounts: List<AccountStatus>): String =
        accounts.joinToString("|") { "${it.providerId}/${it.uid}/${it.disabled}/${it.enabled}" }

    override fun onCleared() {
        engine.onAccountsChanged = null
    }

    // ------------------------------------------------------------------ 读取

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                Snapshot(
                    running = engine.isRunning(),
                    port = engine.settings().port,
                    localUrl = engine.localUrl(),
                    lanUrls = engine.lanUrls(),
                    pool = engine.pool.summary(),
                    today = engine.callLogStore.todayStats(System.currentTimeMillis()),
                    daily = engine.callLogStore.dailyStats(System.currentTimeMillis()),
                    total = engine.callLogStore.totalStats(),
                    providers = engine.providers(),
                    accounts = engine.accounts(),
                    customProviders = engine.settingsRepository.loadCustomProviders(),
                    calls = engine.callLogStore.list(),
                    logs = engine.requestLog.lines(),
                    settings = engine.settings(),
                    proxy = engine.proxySettings(),
                    keepAlive = KeepAlive.status(app),
                    security = engine.securitySettings(),
                    evidences = engine.sanitizer.evidenceList(),
                    sanitizerRulesCount = engine.sanitizer.currentRulesCount(),
                )
            }
            _state.value = _state.value.copy(
                running = snapshot.running,
                port = snapshot.port,
                localUrl = snapshot.localUrl,
                lanUrls = snapshot.lanUrls,
                pool = snapshot.pool,
                today = snapshot.today,
                daily = snapshot.daily,
                total = snapshot.total,
                providers = snapshot.providers,
                accounts = snapshot.accounts,
                customProviders = snapshot.customProviders,
                calls = snapshot.calls,
                logs = snapshot.logs,
                settings = snapshot.settings,
                proxy = snapshot.proxy,
                keepAlive = snapshot.keepAlive,
                security = snapshot.security,
                evidences = snapshot.evidences,
                sanitizerRulesCount = snapshot.sanitizerRulesCount,
            )
        }
    }

    /**
     * 只刷新记录（调用记录 + 请求日志）。
     *
     * 供「记录」页的定时轮询用：那里每 5 秒刷一次，没必要顺带枚举局域网、
     * 复制账号池状态与设置（这些都是低频变化的数据）。
     */
    fun refreshRecords() {
        viewModelScope.launch {
            val (calls, logs) = withContext(Dispatchers.IO) {
                engine.callLogStore.list() to engine.requestLog.lines()
            }
            _state.value = _state.value.copy(calls = calls, logs = logs)
        }
    }

    /**
     * 单独刷新「数据管理」的存储占用。
     *
     * 刻意不放在 [refresh] 里：它要扫全部加密存储键并遍历缓存目录，代价高，
     * 而记录页每 5 秒就会调一次 refresh。
     */
    fun refreshStorage() {
        viewModelScope.launch {
            val (report, count) = withContext(Dispatchers.IO) {
                engine.storageReport() to engine.callLogStore.storedCount()
            }
            _state.value = _state.value.copy(storage = report, storedCalls = count)
        }
    }

    fun refreshModels(silent: Boolean = false) {
        val gen = ++modelsGeneration
        viewModelScope.launch {
            // 已有目录可展示时降级为静默：旧数据立即可见，新数据分批到货逐组替换，不弹全屏 busy
            val quiet = silent || _state.value.models.isNotEmpty()
            if (!quiet) _state.value = _state.value.copy(busy = "正在拉取模型目录")
            val participants = withContext(Dispatchers.IO) {
                engine.providers().filter { it.enabled }.map { it.id }
            }
            if (gen != modelsGeneration) return@launch
            _state.value = _state.value.copy(
                modelsLoading = true,
                modelsError = "",
                modelLoadStates = participants.associateWith { ModelLoadState.Loading as ModelLoadState },
            )
            val (catalog, error) = withContext(Dispatchers.IO) {
                runCatching {
                    engine.modelsStreaming { chunk ->
                        viewModelScope.launch {
                            if (gen == modelsGeneration) applyModelsChunk(chunk)
                        }
                    }
                }.fold(
                    { it to "" },
                    { ModelsCatalog(emptyList()) to (it.message ?: "拉取失败") },
                )
            }
            if (gen != modelsGeneration) return@launch
            // 全部完成后用最终聚合覆盖：失败供应商的旧条目随之清除（与整体刷新语义一致）
            val providerError = catalog.failures.entries.joinToString("；") { "${it.key}：${it.value}" }
            _state.value = _state.value.copy(
                models = catalog.models,
                modelsLoading = false,
                modelsError = listOf(error, providerError).filter { it.isNotEmpty() }.joinToString("；"),
                busy = if (quiet) _state.value.busy else "",
            )
        }
    }

    /** 流式增量：把一个供应商就绪的目录合并进 state（已在主线程串行执行，替换该供应商旧条目）。 */
    private fun applyModelsChunk(chunk: ProviderModelsChunk) {
        val current = _state.value
        val states = current.modelLoadStates.toMutableMap()
        val failure = chunk.error
        states[chunk.providerId] =
            if (failure != null) ModelLoadState.Failed(failure) else ModelLoadState.Done(chunk.models.size)
        _state.value = current.copy(
            models = current.models.filter { it.providerId != chunk.providerId } + chunk.models,
            modelLoadStates = states,
        )
    }

    /** 对指定模型执行一次轻量连通性与延迟探测。 */
    fun testModel(routedModel: RoutedModel) {
        val fullId = routedModel.fullId
        val current = _state.value.modelTestLatencies.toMutableMap()
        current[fullId] = ModelTestStatus.Testing
        _state.value = _state.value.copy(modelTestLatencies = current)

        viewModelScope.launch {
            val latency = withContext(Dispatchers.IO) {
                engine.probeModelLatency(routedModel.providerId, routedModel.model.id, routedModel.region)
            }
            val updated = _state.value.modelTestLatencies.toMutableMap()
            if (latency > 0) {
                updated[fullId] = ModelTestStatus.Success(latency)
            } else {
                updated[fullId] = ModelTestStatus.Timeout
            }
            _state.value = _state.value.copy(modelTestLatencies = updated)
        }
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = "")
    }

    /** 直接弹一条提示（供 UI 组件上报本地错误）。 */
    fun notice(message: String) {
        _state.value = _state.value.copy(notice = message)
    }

    // ------------------------------------------------------------------ 服务

    fun startGateway() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在启动服务")
            // 先拉前台服务再启动引擎：startForegroundService 立即弹出通知，
            // engine.start() 幂等（server != null 直接返回），Service 里会再调一次兑底
            ContextCompat.startForegroundService(app, Intent(app, GatewayService::class.java))
            val error = withContext(Dispatchers.IO) {
                runCatching { engine.start() }.exceptionOrNull()?.let { it.message ?: "启动失败" }
            }
            _state.value = _state.value.copy(busy = "")
            if (error != null) {
                // 引擎没起来就撤掉前台通知，避免「通知说运行中、端口没人听」
                app.stopService(Intent(app, GatewayService::class.java))
                _state.value = _state.value.copy(notice = "启动失败：$error")
            }
            refresh()
            requestTileUpdate()
        }
    }

    fun stopGateway() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.stop() }
            app.stopService(Intent(app, GatewayService::class.java))
            _state.value = _state.value.copy(notice = "服务已停止")
            refresh()
            requestTileUpdate()
        }
    }

    /** App 内启停后让控制中心磁贴同步状态。 */
    private fun requestTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                app,
                ComponentName(app, GatewayTileService::class.java),
            )
        }
    }

    // ------------------------------------------------------------------ 设置

    fun updateSettings(updated: GatewaySettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateSettings(updated) }
            refresh()
        }
    }

    fun updateSecuritySettings(updated: dev.aigw.core.security.SecuritySettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateSecuritySettings(updated) }
            refresh()
        }
    }

    /** 重新加载反审核脱敏处理链。 */
    fun reloadSanitizerPipeline() = action {
        val count = engine.reloadSanitizerPipeline()
        "处理链已重载，生效规则数：$count"
    }

    /** 清空出网取证流水。 */
    fun clearEvidences() {
        engine.sanitizer.clearEvidence()
        _state.value = _state.value.copy(evidences = emptyList())
    }

    fun setDynamicColor(enabled: Boolean) {
        appearance.setDynamicColor(enabled)
        _state.value = _state.value.copy(dynamicColor = enabled)
    }

    fun providerSettings(providerId: String): ProviderSettings = engine.providerSettings(providerId)

    fun updateProviderSettings(providerId: String, settings: ProviderSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateProviderSettings(providerId, settings) }
            refresh()
        }
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 设备授权的区域感知版本（WorkBuddy）：先把区域同步写进供应商设置，
     * 引擎开登录页时才能读到正确的端点（cn=copilot.tencent.com / global=workbuddy.ai），
     * 同时记住本轮登录的区域，保证「重新检查授权状态」轮询同一端点。
     */
    fun beginDeviceLogin(providerId: String, region: String) {
        deviceRegions[providerId] = region
        val settings = providerSettings(providerId)
        engine.updateProviderSettings(providerId, settings.copy(options = settings.options + ("region" to region)))
        beginBrowserLogin(providerId)
    }

    fun providerOption(providerId: String, key: String, fallback: String = ""): String =
        providerSettings(providerId).option(key, fallback)

    fun updateProviderOption(providerId: String, key: String, value: String) {
        val settings = providerSettings(providerId)
        updateProviderSettings(providerId, settings.copy(options = settings.options + (key to value)))
    }

    /**
     * 打开系统浏览器完成登录（Trae / Antigravity / WorkBuddy）。
     *
     * 网关会在本地临时监听回调端口（Trae 51120、Antigravity 51121），
     * 浏览器完成登录后由网关接住回调并落池；WorkBuddy 没有本地回调，
     * 返回轮询用的 state，由 [pollDeviceLogin] 换取 token。
     */
    fun beginBrowserLogin(providerId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在准备登录")
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.beginBrowserLogin(providerId) }
            }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = { ticket ->
                    if (!openBrowser(ticket.loginUrl)) {
                        _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开登录页")
                        return@fold
                    }
                    if (ticket.pollState.isNotEmpty()) {
                        deviceStates[providerId] = ticket.pollState
                        pollDeviceLogin(providerId, ticket.pollState)
                    } else {
                        _state.value = _state.value.copy(notice = "已在浏览器打开登录页，完成后会自动添加账号")
                    }
                },
                onFailure = { _state.value = _state.value.copy(notice = it.message ?: "打开登录页失败") },
            )
        }
    }

    private fun openBrowser(url: String): Boolean = runCatching {
        app.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /**
     * 设备授权（WorkBuddy）：浏览器里登录完成后轮询换取 token，最长 5 分钟。
     */
    fun pollDeviceLogin(providerId: String, state: String, region: String = "") {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "等待授权完成")
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000
            var message = "授权超时，请重试"
            while (System.currentTimeMillis() < deadline) {
                val poll = withContext(Dispatchers.IO) { engine.pollDeviceAuth(providerId, state, region) }
                when (poll) {
                    is DeviceAuthPoll.Pending -> delay(3_000)
                    is DeviceAuthPoll.Success -> {
                        _state.value = _state.value.copy(busy = "", notice = "登录成功：${poll.account.nickname.ifEmpty { poll.account.uid }}")
                        refresh()
                        return@launch
                    }
                    is DeviceAuthPoll.Failed -> {
                        message = poll.error
                        break
                    }
                }
            }
            _state.value = _state.value.copy(busy = "", notice = message)
            refresh()
        }
    }

    /** 用当前填写的地址与 Key 拉取模型列表（新建供应商时还没保存也能用）。 */
    fun fetchCustomModels(baseUrl: String, apiKey: String, onResult: (List<String>, String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在拉取模型")
            val result = withContext(Dispatchers.IO) {
                runCatching { CustomProvider.fetchModels(baseUrl, apiKey) }
            }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = { models ->
                    if (models.isEmpty()) {
                        onResult(emptyList(), "没有拉到模型，检查接口地址与 API Key")
                    } else {
                        onResult(models, "")
                    }
                },
                onFailure = { onResult(emptyList(), it.message ?: "拉取失败") },
            )
        }
    }

    /** 手动重新检查设备授权状态（浏览器里已完成登录但轮询错过时）。 */
    fun checkDeviceAuth(providerId: String, region: String = "") {
        val state = deviceStates[providerId]
        if (state.isNullOrEmpty()) {
            notice("请先点「在浏览器中打开登录页」，完成登录后再回来检查")
            return
        }
        if (region.isNotEmpty()) deviceRegions[providerId] = region
        pollDeviceLogin(providerId, state, deviceRegions[providerId].orEmpty())
    }

    /** 发短信验证码（msgid 内部暂存）；[onResult] 回传错误信息（空串表示成功）。 */
    fun sendSmsCode(providerId: String, phone: String, onResult: (String) -> Unit = {}) {
        val remain = smsResendRemaining(providerId)
        if (remain > 0) {
            val message = "请 $remain 秒后再获取验证码"
            notice(message)
            onResult(message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在发送验证码")
            val result = withContext(Dispatchers.IO) { runCatching { engine.sendSmsCode(providerId, phone) } }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = {
                    smsMsgIds[providerId] = it
                    smsResendAt[providerId] = System.currentTimeMillis() + SMS_RESEND_INTERVAL_MS
                    _state.value = _state.value.copy(notice = "验证码已发送")
                    onResult("")
                },
                onFailure = {
                    val message = it.message ?: "发送验证码失败"
                    _state.value = _state.value.copy(notice = message)
                    onResult(message)
                },
            )
        }
    }

    /** 距离下次可发送验证码还有多少秒（0 表示可以发）。 */
    fun smsResendRemaining(providerId: String): Int {
        val at = smsResendAt[providerId] ?: return 0
        val remain = (at - System.currentTimeMillis()) / 1000
        return remain.coerceAtLeast(0).toInt()
    }

    fun smsLogin(providerId: String, phone: String, code: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在登录")
            val msgid = smsMsgIds[providerId].orEmpty()
            val outcome = withContext(Dispatchers.IO) {
                engine.completeSmsLogin(providerId, phone, code, msgid)
            }
            _state.value = _state.value.copy(busy = "", notice = noticeOf(outcome))
            onResult(if (outcome.ok) "" else outcome.error)
            refresh()
        }
    }

    /** 粘贴凭证导入（Trae 的凭证 JSON、自定义供应商的 API Key）。 */
    fun importCredentials(providerId: String, raw: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在导入")
            val outcome = withContext(Dispatchers.IO) { engine.importAccountJson(providerId, raw) }
            _state.value = _state.value.copy(busy = "", notice = noticeOf(outcome))
            refresh()
        }
    }

    /** 添加自定义供应商账号：每个 key 一个账号，名称可选（默认用供应商名）；同时保存供应商的模型列表。 */
    fun addCustomAccount(providerId: String, nickname: String, apiKey: String, models: List<String>) = action {
        val key = apiKey.trim()
        if (key.isEmpty()) throw IllegalStateException("API Key 不能为空")
        val provider = engine.registry.get(providerId) as? CustomProvider
            ?: throw IllegalStateException("供应商不存在")
        val uid = CustomProvider.uidOf(key)
        if (engine.account(providerId, uid) != null) throw IllegalStateException("这个 API Key 已经添加过了")
        engine.addAccount(providerId, uid, nickname.trim().ifEmpty { provider.displayName }, CustomProvider.secretOf(key))
        saveCustomModels(providerId, models)
        "账号已添加"
    }

    /** 把模型列表写入供应商配置（供「添加账号」时一并保存）。 */
    private fun saveCustomModels(providerId: String, models: List<String>) {
        val repository = engine.settingsRepository
        if (repository.loadCustomProviders().none { it.providerId == providerId }) return
        repository.saveCustomProviders(
            repository.loadCustomProviders().map { if (it.providerId == providerId) it.copy(models = models) else it },
        )
        engine.reloadCustomProviders()
    }

    /** 修改账号显示名称。 */
    fun renameAccount(providerId: String, uid: String, nickname: String) = action {
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        val name = nickname.trim()
        if (name.isEmpty()) throw IllegalStateException("名称不能为空")
        engine.addAccount(providerId, uid, name, account.secret)
        "名称已更新"
    }

    /** 读账号的 API Key（仅自定义供应商的凭证里有）。 */
    fun accountApiKey(providerId: String, uid: String): String {
        val account = engine.account(providerId, uid) ?: return ""
        return runCatching {
            com.google.gson.JsonParser.parseString(account.secret).asJsonObject.get("apiKey")?.asString.orEmpty()
        }.getOrDefault("")
    }

    /** 读自定义供应商已保存的模型列表。 */
    fun customModels(providerId: String): List<String> {
        val provider = engine.registry.get(providerId) as? CustomProvider ?: return emptyList()
        return provider.savedModels()
    }

    /** 读自定义供应商的接口地址。 */
    fun customBaseUrl(providerId: String): String {
        val provider = engine.registry.get(providerId) as? CustomProvider ?: return ""
        return provider.baseUrl
    }

    private fun noticeOf(outcome: LoginOutcome): String =
        if (outcome.ok) "登录成功：${outcome.nickname.ifEmpty { outcome.uid }}" else "失败：${outcome.error}"

    // ------------------------------------------------------------------ 账号

    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean) = action {
        engine.setAccountEnabled(providerId, uid, enabled)
        if (enabled) "已启用该账号" else "已停用该账号"
    }

    fun removeAccount(providerId: String, uid: String) = action {
        if (!engine.removeAccount(providerId, uid)) throw IllegalStateException("账号不存在")
        "账号已删除"
    }

    /** 刷新额度；uid 为空表示刷新该供应商的全部账号。 */
    fun refreshCredits(providerId: String, uid: String?) = action {
        if (uid != null) {
            val result = engine.refreshCredits(providerId, uid)
            if (result.error.isNotEmpty()) throw IllegalStateException(result.error)
            "额度已刷新"
        } else {
            val results = engine.refreshAllCredits(providerId)
            if (results.isEmpty()) throw IllegalStateException("该供应商还没有账号")
            val failed = results.filter { it.error.isNotEmpty() }
            if (failed.isNotEmpty()) {
                throw IllegalStateException(failed.joinToString("；") { "${it.uid}：${it.error}" })
            }
            "额度已刷新（${results.size} 个账号）"
        }
    }

    /** 刷新全部供应商的额度。 */
    fun refreshAllCredits() = action {
        val results = engine.refreshAllCredits()
        if (results.isEmpty()) throw IllegalStateException("还没有账号，先去「供应商」页添加")
        val failed = results.filter { it.error.isNotEmpty() }
        if (failed.isNotEmpty()) {
            throw IllegalStateException(failed.joinToString("；") { "${it.providerId}/${it.uid}：${it.error}" })
        }
        "额度已刷新（${results.size} 个账号）"
    }

    /**
     * 对所有支持签到的账号批量签到。
     *
     * 只签到 Trae 与 WorkBuddy 国内版（cn）：WorkBuddy 国际版（global）没有签到制度，
     * 跳过可避免每次批量签到都报错。
     *
     * 逐个执行并把每个账号的上游原话带回，避免用户只看到一句「N 个账号失败」而无法定位。
     */
    fun checkinAll() = action {
        val targets = engine.providers()
            .filter { ProviderCapability.CHECKIN in it.capabilities }
            .flatMap { info -> engine.accounts(info.id) }
            .filter { account ->
                account.providerId != CodeBuddyProvider.ID ||
                    accountRegion(account.providerId, account.uid) != CodeBuddyProvider.REGION_GLOBAL
            }
        if (targets.isEmpty()) throw IllegalStateException("还没有支持签到的账号")

        val results = targets.map { account ->
            account to engine.performAction(account.providerId, account.uid, ACTION_CHECKIN)
        }
        val failed = results.filter { !it.second.ok }
        if (failed.isNotEmpty()) {
            throw IllegalStateException(
                failed.joinToString("；") {
                    "${it.first.nickname.ifEmpty { it.first.uid }}：${it.second.message}"
                },
            )
        }
        "签到完成（${results.size} 个账号）"
    }

    /**
     * 对所有支持任务的账号批量执行任务（目前是 Loomy 的新手任务）。
     *
     * Trae / WorkBuddy 的「任务」实际就是每日签到，已由 [checkinAll] 覆盖。
     */
    fun runAllTasks() = action {
        val targets = engine.providers()
            .filter { ProviderCapability.TASKS in it.capabilities }
            .flatMap { info -> engine.accounts(info.id) }
        if (targets.isEmpty()) throw IllegalStateException("还没有支持任务的账号")

        val results = targets.map { account ->
            account to engine.performAction(account.providerId, account.uid, ACTION_TASKS)
        }
        val failed = results.filter { !it.second.ok }
        if (failed.isNotEmpty()) {
            throw IllegalStateException(
                failed.joinToString("；") {
                    "${it.first.nickname.ifEmpty { it.first.uid }}：${it.second.message}"
                },
            )
        }
        results.joinToString("；") {
            "${it.first.nickname.ifEmpty { it.first.uid }}：${it.second.message}"
        }
    }

    fun performAction(providerId: String, uid: String, action: String, payload: JsonObject = JsonObject()) = action {
        val result: ProviderActionResult = engine.performAction(providerId, uid, action, payload)
        if (!result.ok) throw IllegalStateException(result.message)
        result.message.ifEmpty { "操作完成" }
    }

    fun updateAccountSecret(providerId: String, uid: String, secret: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateAccountSecret(providerId, uid, secret) }
            refresh()
        }
    }

    // ------------------------------------------------------------------ 设备指纹（Trae 专属）

    private fun traeProvider(): TraeProvider =
        engine.registry.get(TraeProvider.ID) as? TraeProvider
            ?: throw IllegalStateException("Trae 供应商不可用")

    fun deviceIdOf(providerId: String, uid: String): String {
        if (providerId != TraeProvider.ID) return ""
        val provider = engine.registry.get(providerId) as? TraeProvider ?: return ""
        val account = engine.account(providerId, uid) ?: return ""
        return provider.deviceIdOf(account)
    }

    /** 账号所属区域（WorkBuddy 凭证 domain 在 workbuddy.ai = global，否则 cn）；无区域概念返回空串。 */
    fun accountRegion(providerId: String, uid: String): String {
        if (providerId != CodeBuddyProvider.ID) return ""
        val account = engine.account(providerId, uid) ?: return ""
        val domain = runCatching {
            com.google.gson.JsonParser.parseString(account.secret).asJsonObject.get("domain")?.asString.orEmpty()
        }.getOrDefault("").lowercase()
        return if (domain.endsWith("workbuddy.ai")) CodeBuddyProvider.REGION_GLOBAL else CodeBuddyProvider.REGION_CN
    }

    fun regenerateDeviceId(providerId: String, uid: String) = action {
        val provider = traeProvider()
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        engine.updateAccountSecret(providerId, uid, provider.regenerateDeviceIds(account))
        "已重新生成设备指纹，重新签到试试"
    }

    fun setDeviceId(providerId: String, uid: String, value: String) = action {
        val provider = traeProvider()
        val error = provider.validateDeviceId(value)
        if (error.isNotEmpty()) throw IllegalStateException(error)
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        engine.updateAccountSecret(providerId, uid, provider.withDeviceId(account, value))
        "设备指纹已更新，重新签到试试"
    }

    // ------------------------------------------------------------------ 自定义供应商

    fun saveCustomProvider(config: CustomProviderConfig) = action {
        val list = engine.settingsRepository.loadCustomProviders().toMutableList()
        val index = list.indexOfFirst { it.key == config.key }
        if (index >= 0) list[index] = config else list.add(config)
        engine.settingsRepository.saveCustomProviders(list)
        engine.reloadCustomProviders()
        engine.syncCustomAccounts(config)
        "供应商已保存"
    }

    fun removeCustomProvider(key: String) = action {
        val providerId = "custom:$key"
        // 先清掉账号池里的 key，再删配置
        for (account in engine.accounts(providerId)) {
            engine.removeAccount(providerId, account.uid)
        }
        val list = engine.settingsRepository.loadCustomProviders().filterNot { it.key == key }
        engine.settingsRepository.saveCustomProviders(list)
        engine.reloadCustomProviders()
        "供应商已删除"
    }

    // ------------------------------------------------------------------ 代理

    fun proxySettings(): ProxySettings = engine.proxySettings()

    fun updateProxySettings(settings: ProxySettings) {
        engine.updateProxySettings(settings)
        _state.value = _state.value.copy(proxy = settings)
    }

    // ------------------------------------------------------------------ 额度包

    /** 拉取某账号的额度包明细（会打上游）。 */
    fun loadCreditPacks(providerId: String, uid: String) {
        viewModelScope.launch {
            val packs = withContext(Dispatchers.IO) { engine.creditPacks(providerId, uid) }
            _state.value = _state.value.copy(
                creditPacks = _state.value.creditPacks + ("$providerId/$uid" to packs),
            )
        }
    }

    /** 拉取某账号的成长任务明细（会打上游）；支持任务中心的供应商才返回结果。 */
    fun loadTaskList(providerId: String, uid: String) {
        viewModelScope.launch {
            val view = withContext(Dispatchers.IO) { engine.taskList(providerId, uid) }
            if (view == null) return@launch
            _state.value = _state.value.copy(
                taskLists = _state.value.taskLists + ("$providerId/$uid" to view),
            )
        }
    }

    // ------------------------------------------------------------------ 数据管理

    /** 更新保留天数（越界由 core 夹回 1~3650）。 */
    fun setLogRetentionDays(days: Int) = action {
        val clamped = GatewaySettings.clampRetentionDays(days)
        engine.updateSettings(engine.settings().copy(logRetentionDays = clamped))
        if (clamped != days) "保留天数需在 1~3650 之间，已按 $clamped 天保存" else "保留天数已设为 $clamped 天"
    }

    fun purgeExpiredRecords() = action {
        val removed = engine.purgeExpiredRecords()
        val days = engine.settings().logRetentionDays
        if (removed == 0) "没有超过 $days 天的记录可清理" else "已清理 $removed 条过期记录"
    }

    fun truncateLongRecords() = action {
        val (count, saved) = engine.truncateLongRecords()
        if (count == 0) "没有超长记录需要截断" else "已截断 $count 条记录，释放约 ${StorageAudit.formatSize(saved)}"
    }

    // ------------------------------------------------------------------ 记录

    fun deleteCall(id: String) = action {
        engine.callLogStore.delete(id)
        "记录已删除"
    }

    fun clearCalls() = action {
        engine.callLogStore.clear()
        "调用记录已清空"
    }

    fun clearLogs() = action {
        engine.requestLog.clear()
        "请求日志已清空"
    }

    // ------------------------------------------------------------------ 关于与更新

    /** 手动检查更新：无论有无新版本都给出提示。 */
    fun checkUpdate() = performUpdateCheck(manual = true)

    /**
     * 自动检查更新：默认开启，每天最多一次（当天检查过即跳过），失败静默。
     * 启动进入主页时调用。
     */
    fun maybeAutoCheckUpdate() {
        if (!_state.value.autoCheckUpdate) return
        if (updateStore.lastCheckEpochDay() == currentEpochDay()) return
        performUpdateCheck(manual = false)
    }

    fun setAutoCheckUpdate(enabled: Boolean) {
        updateStore.setAutoCheck(enabled)
        _state.value = _state.value.copy(autoCheckUpdate = enabled)
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(updateInfo = null)
    }

    fun openProjectRepo() {
        if (!openBrowser(PROJECT_URL)) {
            _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开项目地址")
        }
    }

    fun openReleasePage(url: String) {
        if (!openBrowser(url)) {
            _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开下载页")
        }
    }

    /**
     * 检查更新的统一实现。
     *
     * [manual] 为 true（用户点「检查更新」）时无论结果都给提示；false（自动检查）只在
     * 发现新版本时弹窗，其余情况静默，避免打扰。
     */
    private fun performUpdateCheck(manual: Boolean) {
        if (_state.value.updateChecking) return
        _state.value = _state.value.copy(updateChecking = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { fetchLatestRelease() } }
            // 自动检查无论成败都记一次「今天已检查」，保证每天最多一次
            if (!manual) updateStore.setLastCheckEpochDay(currentEpochDay())
            outcome.fold(
                onSuccess = { info ->
                    if (isNewer(info.version, installedVersionName())) {
                        _state.value = _state.value.copy(updateInfo = info)
                    } else if (manual) {
                        _state.value = _state.value.copy(notice = "已是最新版本（${installedVersionName()}）")
                    }
                },
                onFailure = { e ->
                    if (manual) {
                        _state.value = _state.value.copy(notice = "检查更新失败：${e.message ?: "网络异常"}")
                    }
                },
            )
            _state.value = _state.value.copy(updateChecking = false)
        }
    }

    /** 直连 GitHub 取最新 Release（不走代理，按用户选择）。 */
    private fun fetchLatestRelease(): UpdateInfo {
        val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UPDATE_UA)
        }
        try {
            val status = conn.responseCode
            if (status == 404) throw IllegalStateException("暂无发布版本")
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JsonParser.parseString(body).asJsonObject
            val version = obj.get("tag_name")?.asString?.trim().orEmpty().removePrefix("v")
            if (version.isEmpty()) throw IllegalStateException("未找到版本信息")
            val url = obj.get("html_url")?.asString.orEmpty().ifEmpty { "$PROJECT_URL/releases" }
            val notes = obj.get("body")?.asString.orEmpty().trim()
            return UpdateInfo(version = version, url = url, notes = notes)
        } finally {
            conn.disconnect()
        }
    }

    /** 逐段比较版本号，远端更大返回 true。 */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun installedVersionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun currentEpochDay(): Long = java.time.LocalDate.now().toEpochDay()

    // ------------------------------------------------------------------ 内部

    /** 跑一个耗时操作，返回的字符串就是给用户的提示；抛异常则展示异常消息。 */
    private fun action(block: () -> String) {
        viewModelScope.launch {
            val notice = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    e.message ?: "操作失败"
                }
            }
            _state.value = _state.value.copy(notice = notice)
            refresh()
            // 清理/截断类操作会改变存储占用，顺带更新一次（低频，代价可接受）
            refreshStorage()
        }
    }
}
