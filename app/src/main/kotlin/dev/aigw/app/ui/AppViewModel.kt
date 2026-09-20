package dev.aigw.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import dev.aigw.app.data.KeepAlive
import dev.aigw.app.data.KeepAliveStatus
import dev.aigw.app.data.WebViewCache
import dev.aigw.app.gatewayEngine
import dev.aigw.app.service.GatewayService
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.ProviderInfo
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
import dev.aigw.core.util.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 验证码重发间隔（秒）：上游有频率限制，连点只会被拒。 */
private const val SMS_RESEND_INTERVAL_MS = 60_000L

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

data class AppUiState(
    val running: Boolean = false,
    val port: Int = GatewaySettings.DEFAULT_PORT,
    val localUrl: String = "",
    val lanUrls: List<String> = emptyList(),
    val pool: PoolSummary = PoolSummary(0, 0, 0, 0, 0, 0, 0),
    val today: UsageStats = UsageStats(),
    /** 全部记录汇总（今日 + 历史）。 */
    val total: UsageStats = UsageStats(),
    val providers: List<ProviderInfo> = emptyList(),
    val accounts: List<AccountStatus> = emptyList(),
    val customProviders: List<CustomProviderConfig> = emptyList(),
    val models: List<RoutedModel> = emptyList(),
    val modelsError: String = "",
    val calls: List<CallRecord> = emptyList(),
    /** 存储中的记录条数（含未载入内存的，仅供「数据管理」展示）。 */
    val storedCalls: Int = 0,
    /** 已拉取的额度包明细，键为 `providerId/uid`。 */
    val creditPacks: Map<String, List<QuotaPack>> = emptyMap(),
    val logs: List<LogLine> = emptyList(),
    val settings: GatewaySettings = GatewaySettings(),
    /** 代理设置。 */
    val proxy: ProxySettings = ProxySettings(),
    val dynamicColor: Boolean = false,
    val keepAlive: KeepAliveStatus? = null,
    /** 存储占用明细（含 WebView 缓存）。 */
    val storage: StorageReport? = null,
    val webViewCacheBytes: Long = 0L,
    val busy: String = "",
    val notice: String = "",
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
    val total: UsageStats,
    val providers: List<ProviderInfo>,
    val accounts: List<AccountStatus>,
    val customProviders: List<CustomProviderConfig>,
    val calls: List<CallRecord>,
    val logs: List<LogLine>,
    val settings: GatewaySettings,
    val proxy: ProxySettings,
    val keepAlive: KeepAliveStatus,
)

class AppViewModel(
    private val app: Application,
    private val engine: GatewayEngine = app.gatewayEngine,
) : ViewModel() {

    private val appearance = AppearanceStore(engine.store())

    private val _state = MutableStateFlow(AppUiState(dynamicColor = appearance.dynamicColor()))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** 短信验证码的 msgid，按供应商暂存（发送与登录是两次调用）。 */
    private val smsMsgIds = HashMap<String, String>()

    /** 下次可重新发送验证码的时间，按供应商记（界面倒计时会随页面重置，不能只靠界面拦）。 */
    private val smsResendAt = HashMap<String, Long>()

    /** 最近一次设备授权登录的 state，供「重新检查授权状态」手动重试。 */
    private val deviceStates = HashMap<String, String>()

    /** 最近一次设备授权登录的区域，保证手动重试轮询与登录时打同一个端点。 */
    private val deviceRegions = HashMap<String, String>()

    init {
        // 登录在浏览器里完成、由网关的回调监听落池，这里接住通知刷新界面
        engine.onAccountsChanged = { viewModelScope.launch { refresh() } }
        refresh()
    }

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
                    today = engine.callLogStore.stats(startOfDay(System.currentTimeMillis())),
                    total = engine.callLogStore.stats(0L),
                    providers = engine.providers(),
                    accounts = engine.accounts(),
                    customProviders = engine.settingsRepository.loadCustomProviders(),
                    calls = engine.callLogStore.list(),
                    logs = engine.requestLog.lines(),
                    settings = engine.settings(),
                    proxy = engine.proxySettings(),
                    keepAlive = KeepAlive.status(app),
                )
            }
            _state.value = _state.value.copy(
                running = snapshot.running,
                port = snapshot.port,
                localUrl = snapshot.localUrl,
                lanUrls = snapshot.lanUrls,
                pool = snapshot.pool,
                today = snapshot.today,
                total = snapshot.total,
                providers = snapshot.providers,
                accounts = snapshot.accounts,
                customProviders = snapshot.customProviders,
                calls = snapshot.calls,
                logs = snapshot.logs,
                settings = snapshot.settings,
                proxy = snapshot.proxy,
                keepAlive = snapshot.keepAlive,
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
            val (report, cached, count) = withContext(Dispatchers.IO) {
                Triple(engine.storageReport(), WebViewCache.sizeBytes(app), engine.callLogStore.storedCount())
            }
            _state.value = _state.value.copy(storage = report, webViewCacheBytes = cached, storedCalls = count)
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在拉取模型目录")
            val (models, error) = withContext(Dispatchers.IO) {
                runCatching { engine.models() }
                    .fold({ it to "" }, { emptyList<RoutedModel>() to (it.message ?: "拉取失败") })
            }
            _state.value = _state.value.copy(models = models, modelsError = error, busy = "")
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
        }
    }

    fun stopGateway() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.stop() }
            app.stopService(Intent(app, GatewayService::class.java))
            _state.value = _state.value.copy(notice = "服务已停止")
            refresh()
        }
    }

    // ------------------------------------------------------------------ 设置

    fun updateSettings(updated: GatewaySettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateSettings(updated) }
            refresh()
        }
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

    /** 添加自定义供应商账号：每个 key 一个账号，名称可选（默认用供应商名）。 */
    fun addCustomAccount(providerId: String, nickname: String, apiKey: String) = action {
        val key = apiKey.trim()
        if (key.isEmpty()) throw IllegalStateException("API Key 不能为空")
        val provider = engine.registry.get(providerId) as? CustomProvider
            ?: throw IllegalStateException("供应商不存在")
        val uid = CustomProvider.uidOf(key)
        if (engine.account(providerId, uid) != null) throw IllegalStateException("这个 API Key 已经添加过了")
        engine.addAccount(providerId, uid, nickname.trim().ifEmpty { provider.displayName }, CustomProvider.secretOf(key))
        "账号已添加"
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

    /** 保存自定义供应商的模型列表（供应商保留原样）。 */
    fun updateCustomModels(providerId: String, models: List<String>) = action {
        val repository = engine.settingsRepository
        val config = repository.loadCustomProviders().firstOrNull { it.providerId == providerId }
            ?: throw IllegalStateException("供应商不存在")
        repository.saveCustomProviders(
            repository.loadCustomProviders().map { if (it.providerId == providerId) it.copy(models = models) else it },
        )
        engine.reloadCustomProviders()
        "模型列表已保存（共 ${models.size} 个）"
    }

    private fun noticeOf(outcome: LoginOutcome): String =
        if (outcome.ok) "登录成功：${outcome.nickname.ifEmpty { outcome.uid }}" else "失败：${outcome.error}"

    // ------------------------------------------------------------------ 账号

    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean) = action {
        engine.setAccountEnabled(providerId, uid, enabled)
        if (enabled) "已启用该账号" else "已停用该账号"
    }

    /**
     * 手动解除冷却。
     *
     * 熔断只是本地保护策略，不代表上游一定不可用（例如额度刚恢复），
     * 所以给用户一个「立即重试」的出口，而不是干等 10 分钟到 12 小时。
     */
    fun clearCooldown(providerId: String, uid: String) = action {
        if (engine.clearCooldown(providerId, uid)) "已解除冷却，重新提交请求试试" else "该账号当前未处于冷却中"
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
     * 逐个执行并把每个账号的上游原话带回，避免用户只看到一句「N 个账号失败」而无法定位。
     */
    fun checkinAll() = action {
        val targets = engine.providers()
            .filter { ProviderCapability.CHECKIN in it.capabilities }
            .flatMap { info -> engine.accounts(info.id) }
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

    /**
     * 清 WebView 缓存。
     *
     * 现在只做文件系统删除（见 WebViewCache.clear 的注释：调 WebStorage/CookieManager
     * 会把 Chromium 加载进主进程，使登录页的进程隔离失效）。
     */
    fun clearWebViewCache() {
        viewModelScope.launch {
            val freed = WebViewCache.clear(app)
            _state.value = _state.value.copy(
                notice = if (freed == 0L) "WebView 缓存本来就是空的" else "已清理 WebView 缓存 ${StorageAudit.formatSize(freed)}",
            )
            refresh()
        }
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
