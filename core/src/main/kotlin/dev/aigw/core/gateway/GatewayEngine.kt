package dev.aigw.core.gateway

import com.google.gson.JsonObject
import dev.aigw.core.pool.AccountPool
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.CoolKind
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.LoopbackOAuthSupport
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderRegistry
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.provider.SmsLoginSupport
import dev.aigw.core.provider.WebLoginSupport
import dev.aigw.core.provider.WebLoginTicket
import dev.aigw.core.provider.antigravity.AntigravityProvider
import dev.aigw.core.provider.custom.CustomProvider
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.usage.CallLogStore
import dev.aigw.core.usage.RequestLog
import dev.aigw.core.usage.StorageAudit
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.util.startOfDay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 登录状态。 */
enum class LoginState { SUCCESS, FAILED }

/** 一次登录/导入的结果。 */
data class LoginOutcome(
    val state: LoginState,
    val uid: String = "",
    val nickname: String = "",
    val error: String = "",
) {
    val ok: Boolean get() = state == LoginState.SUCCESS
}

/** 额度刷新结果。 */
data class CreditRefreshResult(
    val providerId: String,
    val uid: String,
    val nickname: String,
    val balance: Long,
    val known: Boolean,
    val error: String,
)

/** 供应商概览，供「供应商」列表展示。 */
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val authKind: AuthKind,
    val capabilities: Set<ProviderCapability>,
    val enabled: Boolean,
    val accountCount: Int,
    val usableCount: Int,
    /** 用户手动导入的供应商（可编辑/删除）。 */
    val custom: Boolean,
)

/** 服务运行状态，供「首页」展示。 */
data class GatewayStatus(
    val running: Boolean,
    val port: Int,
    val localUrl: String,
    val lanUrls: List<String>,
    val pool: PoolSummary,
    val today: UsageStats,
    val missingApiKey: Boolean,
    val providerCount: Int,
)

/** 模型路由结果：哪个供应商的哪个模型。 */
data class Route(val providerId: String, val model: String)

/**
 * 浏览器登录入口的返回值。
 *
 * [loginUrl] 交给系统浏览器打开；[pollState] 非空表示该供应商靠轮询换取 token
 * （WorkBuddy），调用方需要在打开浏览器后启动轮询。
 */
data class BrowserLoginTicket(val loginUrl: String, val pollState: String = "")

/** 局域网地址来源。Android 侧用系统 API 提供，命令行/测试用默认实现。 */
fun interface LanAddressProvider {
    fun lanAddresses(): List<String>
}

/**
 * 网关引擎：把账号池、各供应商、HTTP 服务、日志与设置串起来。
 * UI 直接调用这里的方法，不需要走 HTTP。
 */
class GatewayEngine(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val lanAddressProvider: LanAddressProvider = DefaultLanAddressProvider,
    private val onLog: (String) -> Unit = {},
) {
    val settingsRepository = SettingsRepository(store)
    val pool = AccountPool(store, nowMillis)
    val callLogStore = CallLogStore(store)
    val requestLog = RequestLog(store, nowMillis = nowMillis)
    val registry = ProviderRegistry()

    @Volatile
    private var settings: GatewaySettings = settingsRepository.load()

    private val providerSettingsCache = ConcurrentHashMap<String, ProviderSettings>()

    /** 对话成功后的额度补刷新：没有流内计费回报的供应商（如 Loomy）靠它让池里的数字跟上消耗。 */
    private val creditRefresher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "credit-refresh").apply { isDaemon = true }
    }
    private val lastAutoCreditRefresh = ConcurrentHashMap<String, Long>()

    private var server: GatewayHttpServer? = null

    /** 串行化 start/stop：startForegroundService 与 UI 协程可能同时调 start()，无锁会出现双建 server 或假失败。 */
    private val lifecycleLock = Any()

    @Volatile
    private var lastAutoPurgeDay: Long = -1L

    internal val hooks = ProviderHooks(
        onBilling = { providerId, uid, credits ->
            pool.updateCredits(providerId, uid, credits, known = true, detail = "来自对话计费回报")
            log("计费回报（$providerId/$uid）：可用额度=$credits")
        },
        onAccountError = { providerId, uid, error ->
            applyCooling(providerId, uid, error.kind, "流内错误：${error.message}")
        },
        onLog = { log(it) },
    )

    /** 账号池发生变化（登录成功、导入等）时回调，供 UI 刷新。 */
    var onAccountsChanged: (() -> Unit)? = null

    /** 代理设置（境外供应商需要）。 */
    @Volatile
    private var proxy: ProxySettings = ProxySettings.fromJson(store.read(ProxySettings.STORE_KEY))

    /** 当前正在监听的登录回调服务（同一时间只允许一个登录流程）。 */
    private var callbackServer: LoopbackCallbackServer? = null

    init {
        registerBuiltinProviders(this)
        reloadCustomProviders()
        installProxy()
        lastAutoPurgeDay = nowMillis() / DAY_MILLIS
        runCatching { purgeExpiredRecords() }
    }

    internal fun now(): Long = nowMillis()

    /** 上层（UI/CLI）复用同一个存储，避免重复打开加密容器。 */
    fun store(): KeyValueStore = store

    // ------------------------------------------------------------------ 代理

    fun proxySettings(): ProxySettings = proxy

    fun updateProxySettings(updated: ProxySettings) {
        proxy = updated
        store.write(ProxySettings.STORE_KEY, updated.toJson().toString())
        log(if (updated.usable) "代理已启用：${updated.host}:${updated.port}" else "代理未启用")
    }

    /** 各供应商的上游域名，供代理规则匹配（按供应商开关代理）。 */
    fun providerHosts(): Map<String, List<String>> =
        registry.all().associate { it.id to it.hosts() }

    /**
     * 安装全局代理选择器。
     *
     * 用全局 [java.net.ProxySelector] 而不是逐请求传 Proxy：各 provider 自己 new 连接，
     * 逐个改造成本高；而「按供应商」本质上就是「按上游域名」，选择器能直接表达。
     */
    private fun installProxy() {
        runCatching {
            java.net.ProxySelector.setDefault(
                GatewayProxySelector(settings = { proxy }, providerHosts = { providerHosts() }),
            )
            java.net.Authenticator.setDefault(GatewayProxyAuthenticator { proxy })
        }.onFailure { logWarn("代理设置安装失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 设置

    fun settings(): GatewaySettings = settings

    fun updateSettings(updated: GatewaySettings) {
        settings = updated
        settingsRepository.save(updated)
        reconfigureProviders()
    }

    fun providerSettings(id: String): ProviderSettings =
        providerSettingsCache.getOrPut(id) { settingsRepository.loadProvider(id) }

    fun updateProviderSettings(id: String, value: ProviderSettings) {
        providerSettingsCache[id] = value
        settingsRepository.saveProvider(id, value)
        reconfigureProviders()
    }

    /** 设置变更后让 provider 重建内部客户端（如 Trae 的版本号）。 */
    private fun reconfigureProviders() {
        for (provider in registry.all()) {
            (provider as? Reconfigurable)?.reconfigure()
        }
    }

    /** 供应商可选实现：设置变更时需要重建内部状态。 */
    interface Reconfigurable {
        fun reconfigure()
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动网关；端口被占用等错误通过异常抛出。 */
    @Throws(Exception::class)
    fun start() {
        synchronized(lifecycleLock) {
            if (server != null) return
            val host = if (settings.exposeLan) null else "127.0.0.1"
            val created = GatewayHttpServer(this, host, settings.port)
            created.start(0, false)
            server = created
            log("网关已启动，监听 ${created.listeningAddress()}")
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            server?.stop()
            server = null
            requestLog.flush()
            log("网关已停止")
        }
    }

    fun isRunning(): Boolean = server != null

    fun localUrl(): String = "http://127.0.0.1:${settings.port}/v1"

    fun lanUrls(): List<String> = lanAddressProvider.lanAddresses().map { "http://$it:${settings.port}/v1" }

    fun status(): GatewayStatus = GatewayStatus(
        running = isRunning(),
        port = settings.port,
        localUrl = localUrl(),
        lanUrls = lanUrls(),
        pool = pool.summary(),
        // 用注入的 nowMillis（而非系统时钟），“今天”才能在固定时钟的测试里可控
        today = callLogStore.stats(startOfDay(nowMillis())),
        missingApiKey = !settings.allowNoKey && settings.apiKey.isEmpty(),
        providerCount = registry.all().count { providerSettings(it.id).enabled },
    )

    // ------------------------------------------------------------------ 供应商

    fun providers(): List<ProviderInfo> = registry.all().map { provider ->
        val summary = pool.summary(provider.id)
        ProviderInfo(
            id = provider.id,
            displayName = provider.displayName,
            authKind = provider.authKind,
            capabilities = provider.capabilities,
            enabled = providerSettings(provider.id).enabled,
            accountCount = summary.total,
            usableCount = summary.usable,
            custom = provider.id.startsWith(CUSTOM_PREFIX),
        )
    }

    /** 重新读取自定义供应商配置并登记（增删改后调用）。 */
    fun reloadCustomProviders() {        // 先摘掉已登记的，避免删掉的供应商还留在列表里
        for (provider in registry.all()) {
            if (provider.id.startsWith(CUSTOM_PREFIX)) registry.unregister(provider.id)
        }
        for (config in settingsRepository.loadCustomProviders()) {
            registry.register(
                dev.aigw.core.provider.custom.CustomProvider(
                    config = config,
                    nowMillis = nowMillis,
                ),
            )
        }
    }

    /**
     * 把自定义供应商配置里的 API Key 同步成账号池里的账号（新增加入、移除的删号）。
     *
     * 两处存储容易脱节：详情页读的是账号池，而新建时填的 key 先落在供应商配置里，
     * 不同步的话保存完进详情页会看不到刚填的 key。
     */
    fun syncCustomAccounts(config: CustomProviderConfig) {
        val providerId = config.providerId
        val wanted = config.apiKeys.map { CustomProvider.uidOf(it) }
        val existing = accounts(providerId).map { it.uid }
        config.apiKeys.forEachIndexed { index, key ->
            val uid = wanted[index]
            if (uid !in existing) {
                pool.upsert(
                    ProviderAccount(providerId, uid, config.name, CustomProvider.secretOf(key)),
                )
            }
        }
        for (uid in existing - wanted.toSet()) {
            removeAccount(providerId, uid)
        }
    }

    // ------------------------------------------------------------------ 账号

    fun accounts(providerId: String? = null): List<AccountStatus> = pool.statuses(providerId)

    /** 取账号的原始凭证（供供应商专属 UI 组件做凭证级操作，如校正设备指纹）。 */
    fun account(providerId: String, uid: String): ProviderAccount? = pool.account(providerId, uid)

    /** 直接替换账号凭证（保留状态）。 */
    fun updateAccountSecret(providerId: String, uid: String, secret: String) {
        val account = pool.account(providerId, uid) ?: return
        pool.saveAccount(account.copy(secret = secret))
    }

    fun removeAccount(providerId: String, uid: String): Boolean {
        val removed = pool.remove(providerId, uid)
        if (removed) log("已删除账号：$providerId/$uid")
        return removed
    }

    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean) {
        pool.setEnabled(providerId, uid, enabled, reason = if (enabled) "" else "手动停用")
        log("${if (enabled) "启用" else "停用"}账号：$providerId/$uid")
    }

    /** 手动解除冷却；凭证失效是硬状态，不在此绕过。 */
    fun clearCooldown(providerId: String, uid: String): Boolean {
        val cleared = pool.clearCooldown(providerId, uid)
        if (cleared) log("已解除冷却：$providerId/$uid")
        return cleared
    }

    /** 刷新单个账号的额度；顺带把「额度为 0」的冷却账号恢复。 */
    /**
     * 对话成功后异步补刷一次该账号的额度。
     *
     * Trae 在流内回报余额（onBilling），其它供应商上游不回——对话扣了积分但池里
     * 还是上次手动刷新的旧值，看起来像「不消耗」。这里补一刷；同账号按时间窗节流。
     */
    fun refreshCreditsSoon(providerId: String, uid: String) {
        val key = "$providerId/$uid"
        val now = nowMillis()
        // 占坑式节流：首次或距上次超过间隔的线程 replace 成功才触发刷新，
        // 同账号并发对话只放行一个，其余直接放弃（下一轮对话还会再试）。
        val acquired = if (lastAutoCreditRefresh.containsKey(key)) {
            lastAutoCreditRefresh.replace(key, lastAutoCreditRefresh[key] ?: 0L, now)
        } else {
            lastAutoCreditRefresh.putIfAbsent(key, now) == null
        }
        if (acquired) creditRefresher.execute { runCatching { refreshCredits(providerId, uid) } }
    }

    fun refreshCredits(providerId: String, uid: String): CreditRefreshResult {
        val provider = registry.get(providerId)
            ?: return CreditRefreshResult(providerId, uid, "", 0, false, "未知供应商")
        val account = pool.account(providerId, uid)
            ?: return CreditRefreshResult(providerId, uid, "", 0, false, "账号不存在")
        val info: CreditInfo = try {
            provider.creditInfo(account)
                ?: return CreditRefreshResult(providerId, uid, account.nickname, 0, false, "该供应商不支持额度刷新")
        } catch (e: Exception) {
            logWarn("额度刷新异常（$providerId/${account.nickname.ifEmpty { uid }}）：${e.message}")
            return CreditRefreshResult(providerId, uid, account.nickname, 0, false, e.message ?: "刷新额度失败")
        }
        pool.updateCredits(providerId, uid, info.balance, info.known, info.detail)
        if (info.known) {
            log("额度刷新 $providerId/${account.nickname.ifEmpty { uid }}：${info.balance}（${info.detail}）")
        } else {
            logWarn("额度刷新失败（$providerId/${account.nickname.ifEmpty { uid }}）：${info.detail}")
        }
        return CreditRefreshResult(
            providerId, uid, account.nickname, info.balance, info.known,
            if (info.known) "" else info.detail,
        )
    }

    fun refreshAllCredits(providerId: String? = null): List<CreditRefreshResult> =
        pool.accounts(providerId).map { refreshCredits(it.providerId, it.uid) }

    /** 取某账号的额度包明细（会打上游，调用方自行控制频率）。 */
    fun creditPacks(providerId: String, uid: String): List<QuotaPack> {
        val provider = registry.get(providerId) ?: return emptyList()
        val account = pool.account(providerId, uid) ?: return emptyList()
        return try {
            provider.creditPacks(account)
        } catch (e: Exception) {
            logWarn("拉取额度包失败（$providerId/$uid）：${e.message}")
            emptyList()
        }
    }

    /** 执行供应商专属动作（签到、兑换码等）；成功后顺带刷新额度。 */
    fun performAction(
        providerId: String,
        uid: String,
        action: String,
        payload: JsonObject = JsonObject(),
    ): ProviderActionResult {
        val provider = registry.get(providerId) ?: return ProviderActionResult.failure("未知供应商")
        val account = pool.account(providerId, uid) ?: return ProviderActionResult.failure("账号不存在")
        val result = provider.performAction(account, action, payload)
        if (result.ok) refreshCredits(providerId, uid)
        return result
    }

    /** 无参数动作的便捷重载（调用方不必依赖 JsonObject）。 */
    fun performAction(providerId: String, uid: String, action: String): ProviderActionResult =
        performAction(providerId, uid, action, JsonObject())

    // ------------------------------------------------------------------ 登录

    /**
     * 打开系统浏览器完成登录，返回要打开的 URL。
     *
     * - **Trae**：临时监听 51120 接住 `/authorize` 回调；
     * - **Antigravity**：监听 51121（Google 客户端注册的 redirect_uri 固定为此端口）；
     * - **WorkBuddy**：没有本地回调，返回授权页 URL 并附上轮询用的 state。
     *
     * 用系统浏览器而不是内置 WebView，是为了绕开设备 WebView 的渲染异常；
     * 登录期间会临时占用一个本地端口，收到回调或超时后自动释放。
     */
    fun beginBrowserLogin(providerId: String): BrowserLoginTicket {
        stopCallbackServer()
        val provider = registry.get(providerId) ?: throw IllegalStateException("未知供应商：$providerId")
        return when {
            provider is WebLoginSupport && providerId == TraeProvider.ID -> {
                val callbackUrl = "http://127.0.0.1:$TRAE_CALLBACK_PORT$TRAE_CALLBACK_PATH"
                val ticket = provider.beginWebLogin(callbackUrl)
                startCallbackServer(TRAE_CALLBACK_PORT, TRAE_CALLBACK_PATH) { url ->
                    val outcome = completeWebLogin(providerId, url)
                    resultPage(outcome.ok, outcome.nickname.ifEmpty { outcome.uid }, outcome.error)
                }
                BrowserLoginTicket(ticket.loginUrl)
            }

            provider is LoopbackOAuthSupport -> {
                startCallbackServer(AntigravityProvider.CALLBACK_PORT, AntigravityProvider.CALLBACK_PATH) { url ->
                    val outcome = completeOAuth(providerId, url)
                    resultPage(outcome.ok, outcome.nickname.ifEmpty { outcome.uid }, outcome.error)
                }
                BrowserLoginTicket(provider.buildAuthUrl())
            }

            provider is DeviceCodeSupport -> {
                // 区域感知的登录端点（WorkBuddy：cn=copilot.tencent.com / global=workbuddy.ai）
                val region = providerSettings(providerId).option("region", "")
                val ticket = provider.startDeviceAuth(region)
                BrowserLoginTicket(ticket.loginUrl, ticket.state)
            }

            else -> throw IllegalStateException("该供应商不支持浏览器登录")
        }
    }

    private fun startCallbackServer(port: Int, path: String, handler: (String) -> String) {
        val server = try {
            LoopbackCallbackServer(port, path) { url ->
                val html = handler(url)
                // 先让响应写完再关端口，否则浏览器会看到连接被重置
                stopCallbackServerSoon()
                html
            }.also { it.startServer() }
        } catch (e: Exception) {
            throw IllegalStateException("无法监听本地回调端口 $port：${e.message}")
        }
        callbackServer = server
        log("已监听本地回调 http://127.0.0.1:$port$path")
        // 用户长时间不完成登录时释放端口，避免一直占着
        Thread {
            runCatching { Thread.sleep(CALLBACK_TIMEOUT_MS) }
            if (callbackServer === server) {
                callbackServer = null
                runCatching { server.stop() }
                log("浏览器登录超时，已释放回调端口 $port")
            }
        }.start()
    }

    private fun stopCallbackServerSoon() {
        val server = callbackServer ?: return
        callbackServer = null
        Thread {
            runCatching { Thread.sleep(1_500) }
            runCatching { server.stop() }
        }.start()
    }

    /** 释放登录回调端口（登录完成或用户取消时调用）。 */
    fun stopCallbackServer() {
        val server = callbackServer ?: return
        callbackServer = null
        runCatching { server.stop() }
    }

    fun beginWebLogin(providerId: String, callbackUrl: String): WebLoginTicket {
        val support = registry.get(providerId) as? WebLoginSupport
            ?: throw IllegalStateException("该供应商不支持网页登录")
        return support.beginWebLogin(callbackUrl)
    }

    fun completeWebLogin(providerId: String, callbackUrl: String): LoginOutcome {
        val support = registry.get(providerId) as? WebLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持网页登录")
        return try {
            val account = support.completeWebLogin(callbackUrl)
            adopt(providerId, account, "登录")
        } catch (e: Exception) {
            logWarn("登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    fun sendSmsCode(providerId: String, phone: String): String {
        val support = registry.get(providerId) as? SmsLoginSupport
            ?: throw IllegalStateException("该供应商不支持短信登录")
        return support.sendSmsCode(phone)
    }

    fun completeSmsLogin(providerId: String, phone: String, code: String, msgid: String): LoginOutcome {
        val support = registry.get(providerId) as? SmsLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持短信登录")
        return try {
            adopt(providerId, support.loginBySmsCode(phone, code, msgid), "登录")
        } catch (e: Exception) {
            logWarn("登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    fun startDeviceAuth(providerId: String, region: String = ""): DeviceAuthTicket {
        val support = registry.get(providerId) as? DeviceCodeSupport
            ?: throw IllegalStateException("该供应商不支持设备授权登录")
        return support.startDeviceAuth(region)
    }

    fun pollDeviceAuth(providerId: String, state: String, region: String = ""): DeviceAuthPoll {
        val support = registry.get(providerId) as? DeviceCodeSupport
            ?: return DeviceAuthPoll.Failed("该供应商不支持设备授权登录")
        val poll = support.pollDeviceAuth(state, region)
        if (poll is DeviceAuthPoll.Success) {
            adopt(providerId, poll.account, "登录")
        }
        return poll
    }

    fun buildOAuthUrl(providerId: String): String {
        val support = registry.get(providerId) as? LoopbackOAuthSupport
            ?: throw IllegalStateException("该供应商不支持 OAuth 登录")
        return support.buildAuthUrl()
    }

    fun completeOAuth(providerId: String, callbackUrl: String): LoginOutcome {
        val support = registry.get(providerId) as? LoopbackOAuthSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持 OAuth 登录")
        val code = dev.aigw.core.provider.queryParam(callbackUrl, "code")
            ?: return LoginOutcome(LoginState.FAILED, error = "回调里没有 code 参数")
        return try {
            adopt(providerId, support.exchangeCode(code), "登录")
        } catch (e: Exception) {
            logWarn("OAuth 登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    /** 粘贴 JSON 凭证导入（各供应商自行解析）。 */
    fun importAccountJson(providerId: String, raw: String): LoginOutcome {
        val provider = registry.get(providerId)
            ?: return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return try {
            val account = provider.importCredentials(raw)
                ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持粘贴导入")
            adopt(providerId, account, "导入")
        } catch (e: Exception) {
            logWarn("导入失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "导入失败")
        }
    }

    /** 直接把账号写入账号池（自定义供应商手动添加 key 时用）。 */
    fun addAccount(providerId: String, uid: String, nickname: String, secret: String): LoginOutcome {
        if (registry.get(providerId) == null) return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return adopt(providerId, ProviderAccount(providerId, uid, nickname, secret), "添加")
    }

    private fun adopt(providerId: String, account: ProviderAccount, verb: String): LoginOutcome {
        pool.upsert(account)
        refreshCredits(providerId, account.uid)
        log("$verb 成功：${account.nickname.ifEmpty { account.uid }}（$providerId）")
        onAccountsChanged?.invoke()
        return LoginOutcome(LoginState.SUCCESS, account.uid, account.nickname)
    }

    // ------------------------------------------------------------------ 模型

    /** 汇总所有启用供应商的模型（id 带 `provider/` 前缀）。 */
    fun models(): List<RoutedModel> {
        val result = ArrayList<RoutedModel>()
        for (provider in registry.all()) {
            if (!providerSettings(provider.id).enabled) continue
            val view = try {
                provider.listModels(pool.pick(provider.id))
            } catch (e: Exception) {
                logWarn("拉取模型目录失败（${provider.id}）：${e.message}")
                continue
            }
            for (model in view.models) {
                if (settings.onlyUsableModels && provider.isInternalModel(model.id)) continue
                result.add(RoutedModel(provider.id, provider.displayName, model))
            }
        }
        return result
    }

    /**
     * 把客户端传来的模型名解析成「哪个供应商 + 哪个模型」。
     *
     * `provider/model` 形式按前缀路由；不带前缀时落到 `defaultProvider`。
     */
    fun resolveRoute(requested: String): Route? {
        val model = requested.trim()
        if (model.isEmpty()) return null
        val slash = model.indexOf('/')
        if (slash > 0) {
            val prefix = model.substring(0, slash)
            if (registry.get(prefix) != null) return Route(prefix, model.substring(slash + 1))
        }
        val fallback = settings.defaultProvider.takeIf { registry.get(it) != null }
            ?: registry.all().firstOrNull()?.id
            ?: return null
        return Route(fallback, model)
    }

    // ------------------------------------------------------------------ 数据管理

    /** 存储占用明细。 */
    fun storageReport(): StorageReport = StorageAudit.audit(store)

    /** 清理早于「保留天数」的记录；返回删除条数。 */
    fun purgeExpiredRecords(): Int {
        val cutoff = nowMillis() - settings.logRetentionDays.toLong() * DAY_MILLIS
        val removed = callLogStore.purgeOlderThan(cutoff)
        if (removed > 0) log("按保留 ${settings.logRetentionDays} 天清理了 $removed 条过期调用记录")
        return removed
    }

    /** 截断超长记录内容；返回「截断条数 to 释放字符数」。 */
    fun truncateLongRecords(maxChars: Int = DEFAULT_TRUNCATE_CHARS): Pair<Int, Long> {
        val (count, saved) = callLogStore.truncateFields(maxChars)
        if (count > 0) log("截断了 $count 条记录的长内容，释放约 ${StorageAudit.formatSize(saved)}")
        return count to saved
    }

    /** 服务运行时调一次：跨天则按保留天数自动清理，保证长时间挂机不会无限增重。 */
    fun autoPurgeIfDue() {
        val today = nowMillis() / DAY_MILLIS
        if (lastAutoPurgeDay == today) return
        lastAutoPurgeDay = today
        purgeExpiredRecords()
    }

    // ------------------------------------------------------------------ 日志

    internal fun log(text: String) {
        requestLog.info(text)
        onLog(text)
    }

    internal fun logWarn(text: String) {
        requestLog.warn(text)
        onLog(text)
    }

    internal fun logError(text: String) {
        requestLog.error(text)
        onLog(text)
    }

    /** 按统一错误分类施加冷却/禁用策略。 */
    internal fun applyCooling(providerId: String, uid: String, kind: ErrorKind, reason: String) {
        val current = settings
        when (kind) {
            ErrorKind.QUOTA -> pool.cooldown(providerId, uid, CoolKind.QUOTA, current.quotaCooldownMillis, reason)
            ErrorKind.SOFT_RATE, ErrorKind.NOT_FOUND ->
                pool.cooldown(providerId, uid, CoolKind.SOFT, current.softCooldownMillis, reason)
            ErrorKind.SESSION_DEAD -> pool.disable(providerId, uid, reason)
            ErrorKind.SERVER, ErrorKind.CLIENT, ErrorKind.NETWORK ->
                pool.noteError(providerId, uid, current.errorThreshold, current.errorCooldownMillis)
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 3600 * 1000

        /** 「截断超长记录内容」默认截到 4K 字符：足够看清请求，又不至于让存储爆掉。 */
        const val DEFAULT_TRUNCATE_CHARS = 4_000

        const val CUSTOM_PREFIX = "custom:"

        /** 自动补刷额度的同账号最小间隔：对话连发时不打爆上游积分接口。 */
        private const val AUTO_CREDIT_REFRESH_MIN_INTERVAL_MS = 10_000L

        /** Trae 登录回调用的本地端口（避开 Antigravity 的 51121）。 */
        const val TRAE_CALLBACK_PORT = 51120
        const val TRAE_CALLBACK_PATH = "/authorize"

        /** 登录回调监听的存活上限。 */
        const val CALLBACK_TIMEOUT_MS = 5L * 60 * 1000
    }
}

/** 登录回调结果页（在系统浏览器里展示）。 */
private fun resultPage(ok: Boolean, who: String, error: String): String {
    val title = if (ok) "登录成功" else "登录失败"
    val message = if (ok) "账号 $who 已添加，可以关闭本页并返回应用。" else error.ifEmpty { "请回到应用重试" }
    return """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <style>
          body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
               font-family:system-ui,-apple-system,"PingFang SC",sans-serif;background:#f6f8fb;color:#1b1c1e}
          .card{max-width:22rem;padding:2rem;border-radius:1.75rem;background:#fff;text-align:center;
                box-shadow:0 8px 30px rgba(0,0,0,.06)}
          h1{font-size:1.25rem;margin:0 0 .75rem}
          p{font-size:.95rem;line-height:1.6;color:#5a5f66;margin:0}
        </style></head>
        <body><div class="card"><h1>$title</h1><p>$message</p></div></body></html>
    """.trimIndent()
}

/** 默认用网络接口枚举局域网地址；Android 侧注入系统实现更准。 */
object DefaultLanAddressProvider : LanAddressProvider {
    override fun lanAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}
