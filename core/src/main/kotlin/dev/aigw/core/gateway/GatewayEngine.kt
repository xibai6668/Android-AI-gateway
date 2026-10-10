package dev.aigw.core.gateway

import com.google.gson.JsonObject
import dev.aigw.core.pool.AccountPool
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.LoopbackOAuthSupport
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderTaskListView
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
import dev.aigw.core.provider.raccoon.RaccoonProvider
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.usage.CallLogStore
import dev.aigw.core.usage.RequestLog
import dev.aigw.core.usage.StorageAudit
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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

/** 流式拉取模型目录时，单个供应商的就绪事件（成功带 models，失败带 error）。 */
data class ProviderModelsChunk(
    val providerId: String,
    val models: List<RoutedModel>,
    /** 非空表示该供应商拉取失败（异常消息或供应商自报的目录错误），此时 models 为空。 */
    val error: String? = null,
)

/** 全部供应商模型目录的最终聚合结果。 */
data class ModelsCatalog(
    val models: List<RoutedModel>,
    /** 拉取失败的供应商与原因，键为 providerId。 */
    val failures: Map<String, String> = emptyMap(),
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

/**
 * 模型路由结果：哪个供应商的哪个模型。
 *
 * [region] 非空时（区域型前缀，如 `codebuddy-cn/xxx`）选号只在该区域的账号中进行。
 */
data class Route(val providerId: String, val model: String, val region: String? = null)

/**
 * 浏览器登录入口的返回值。
 *
 * [loginUrl] 交给系统浏览器打开；[pollState] 非空表示该供应商靠轮询换取 token
 * （WorkBuddy），调用方需要在打开浏览器后启动轮询。
 */
data class BrowserLoginTicket(val loginUrl: String, val pollState: String = "")

/** 模型探测结果：延迟（毫秒）与失败原因。 */
data class ProbeResult(val latencyMs: Long, val error: String = "")

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
    /**
     * 调用记录是否在构造时同步载入。一次性 CLI 工具需要同步（命令立即读数据）；
     * App 传 false，预载与过期清理改在后台线程执行，冷启动不再阻塞主线程。
     */
    preloadCallLogs: Boolean = true,
) {
    val settingsRepository = SettingsRepository(store)
    val pool = AccountPool(store, nowMillis)
    val callLogStore = CallLogStore(store, preloadOnConstruction = preloadCallLogs)
    val requestLog = RequestLog(store, nowMillis = nowMillis)
    val registry = ProviderRegistry()

    val sanitizer = dev.aigw.core.security.RequestSanitizer(nowMillis)
    val rateLimiter = dev.aigw.core.security.AccountRateLimiter(nowMillis)

    @Volatile
    private var failoverSettings: dev.aigw.core.failover.ModelFailoverSettings =
        dev.aigw.core.failover.ModelFailoverSettings.fromJson(store.read(dev.aigw.core.failover.ModelFailoverSettings.STORE_KEY))

    private val circuitBreakers = ConcurrentHashMap<String, dev.aigw.core.failover.ProviderCircuitBreaker>()
    private val providerMetrics = ConcurrentHashMap<String, dev.aigw.core.failover.ProviderMetricsTracker>()

    @Volatile
    private var securitySettings: dev.aigw.core.security.SecuritySettings =
        dev.aigw.core.security.SecuritySettings.fromJson(store.read(dev.aigw.core.security.SecuritySettings.STORE_KEY))

    @Volatile
    private var settings: GatewaySettings = settingsRepository.load()

    private val providerSettingsCache = ConcurrentHashMap<String, ProviderSettings>()

    /**
     * 共享 I/O 线程池：模型目录、额度查询等会阻塞在网络上的并行任务都走这里。
     * 之前每次请求都 new 一个线程池再 shutdown，短连接 + 线程反复创建销毁纯属浪费；
     * 这里常驻复用，空闲线程 60 秒自行回收，不常占资源。
     */
    private val ioPool = ThreadPoolExecutor(
        0, 8, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        { runnable -> Thread(runnable, "aigw-io").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    /** 共享定时器：SSE 心跳等周期性小任务复用，避免每条流各建一个调度线程。 */
    private val scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "aigw-sched").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** 供 HTTP 层复用同一套后台线程池与定时器。 */
    internal fun ioExecutor(): ThreadPoolExecutor = ioPool

    internal fun schedulerExecutor(): ScheduledExecutorService = scheduler

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
            // 只有凭证失效才硬禁用；其它错误不再冷却账号，每次如实上报
            if (error.kind == ErrorKind.SESSION_DEAD) {
                pool.disable(providerId, uid, error.message)
            }
            log("供应商 [$providerId] 账号 ${uid} 流内错误：${error.message}")
        },
        onAccountUpdated = { account ->
            pool.saveAccount(account)
            log("账号凭证已自动持久化更新（${account.providerId}/${account.nickname.ifEmpty { account.uid }}）")
            onAccountsChanged?.invoke()
        },
        onLog = { log(it) },
        onVerbose = { tag, text -> logVerbose(tag, text) },
    )

    /** 账号池发生变化（登录成功、导入等）时回调，供 UI 刷新。 */
    var onAccountsChanged: (() -> Unit)? = null

    /** 代理设置（境外供应商需要）。 */
    @Volatile
    private var proxy: ProxySettings = ProxySettings.fromJson(store.read(ProxySettings.STORE_KEY))

    /** 当前正在监听的登录回调服务（同一时间只允许一个登录流程）。 */
    private var callbackServer: LoopbackCallbackServer? = null

    /** 首屏数据（调用记录与统计、过期清理）是否已在后台就绪。 */
    @Volatile
    private var startupDataReady = false

    @Volatile
    private var startupDataListener: (() -> Unit)? = null

    init {
        registerBuiltinProviders(this)
        reloadCustomProviders()
        installProxy()
        sanitizer.reloadPipeline(securitySettings.extraWords)
        lastAutoPurgeDay = nowMillis() / DAY_MILLIS
        if (preloadCallLogs) {
            runCatching { purgeExpiredRecords() }
        } else {
            // 调用记录预载与过期清理要扫全部 logs/calls/ 键并逐个解密，
            // App 冷启动时丢到后台执行，不让主线程等它；完成后通知 UI 补一次刷新。
            ioExecutor().execute {
                runCatching {
                    callLogStore.load()
                    purgeExpiredRecords()
                }
                startupDataReady = true
                startupDataListener?.invoke()
            }
        }
    }

    /**
     * 注册首屏数据（调用记录/统计）就绪回调。
     *
     * 这些数据在后台线程预载，冷启动时可能晚于 UI 首帧；注册时若已就绪会立即触发一次。
     */
    fun setStartupDataReadyListener(listener: (() -> Unit)?) {
        startupDataListener = listener
        if (startupDataReady) listener?.invoke()
    }

    internal fun now(): Long = nowMillis()

    /** 上层（UI/CLI）复用同一个存储，避免重复打开加密容器。 */
    fun store(): KeyValueStore = store

    // ------------------------------------------------------------------ 安全防护与风控

    fun securitySettings(): dev.aigw.core.security.SecuritySettings = securitySettings

    fun updateSecuritySettings(updated: dev.aigw.core.security.SecuritySettings) {
        securitySettings = updated
        store.write(dev.aigw.core.security.SecuritySettings.STORE_KEY, updated.toJson().toString())
        sanitizer.reloadPipeline(updated.extraWords)
        log("安全防护设置已更新（脱敏=${if (updated.sanitizeEnabled) "开启" else "关闭"}，限速=${updated.minIntervalMillis}ms，抖动=${updated.jitterMillis}ms）")
    }

    /** 重新加载脱敏处理链与规则词表。 */
    fun reloadSanitizerPipeline(): Int {
        sanitizer.reloadPipeline(securitySettings.extraWords)
        val count = sanitizer.currentRulesCount()
        log("反审核脱敏处理链已重新加载，当前生效规则词汇数：$count")
        return count
    }

    // ------------------------------------------------------------------ 故障转移与容灾调度

    fun failoverSettings(): dev.aigw.core.failover.ModelFailoverSettings = failoverSettings

    fun updateFailoverSettings(updated: dev.aigw.core.failover.ModelFailoverSettings) {
        failoverSettings = updated
        store.write(dev.aigw.core.failover.ModelFailoverSettings.STORE_KEY, updated.toJson().toString())
        log("故障转移配置已更新（启用=${updated.enabled}，单供应商最大重试=${updated.retry.maxAttempts}，熔断阈值=${updated.circuitBreaker.failureThreshold}）")
    }

    fun circuitBreakerOf(providerId: String): dev.aigw.core.failover.ProviderCircuitBreaker =
        circuitBreakers.computeIfAbsent(providerId) {
            dev.aigw.core.failover.ProviderCircuitBreaker(providerId, config = { failoverSettings.circuitBreaker }, nowMillis = nowMillis)
        }

    fun metricsOf(providerId: String): dev.aigw.core.failover.ProviderMetricsTracker =
        providerMetrics.computeIfAbsent(providerId) { dev.aigw.core.failover.ProviderMetricsTracker() }

    /** 导出全部供应商的健康观测指标快照 */
    fun providerMetricsSnapshot(): Map<String, Map<String, Any>> =
        providerMetrics.mapValues { it.value.snapshot() }

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
        // 值未变时短路：避免每次保存都重建所有 provider 内部客户端
        if (updated == settings) return
        settings = updated
        settingsRepository.save(updated)
        reconfigureProviders()
    }

    fun providerSettings(id: String): ProviderSettings =
        providerSettingsCache.getOrPut(id) { settingsRepository.loadProvider(id) }

    fun updateProviderSettings(id: String, value: ProviderSettings) {
        if (providerSettingsCache[id] == value) return
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
        today = callLogStore.todayStats(nowMillis()),
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

    /**
     * 取某账号的成长任务明细（会打上游，调用方自行控制频率）。
     *
     * 只读快照，供任务中心展示「哪些任务做了、哪些没做」；不支持任务中心的供应商返回 null。
     */
    fun taskList(providerId: String, uid: String): ProviderTaskListView? {
        val provider = registry.get(providerId) ?: return null
        val account = pool.account(providerId, uid) ?: return null
        return try {
            provider.taskList(account)
        } catch (e: Exception) {
            logWarn("拉取任务列表失败（$providerId/$uid）：${e.message}")
            ProviderTaskListView(emptyList(), error = e.message ?: "拉取任务列表失败")
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
            provider is WebLoginSupport && (providerId == TraeProvider.ID || providerId == RaccoonProvider.ID) -> {
                val (port, path) = if (providerId == RaccoonProvider.ID) {
                    RACCOON_CALLBACK_PORT to RACCOON_CALLBACK_PATH
                } else {
                    TRAE_CALLBACK_PORT to TRAE_CALLBACK_PATH
                }
                val callbackUrl = "http://127.0.0.1:$port$path"
                val ticket = provider.beginWebLogin(callbackUrl)
                startCallbackServer(port, path) { url ->
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

    /**
     * 流式拉取全部启用供应商的模型目录：每个供应商一就绪立即回调 [onChunk]，
     * 不等最慢的供应商（整体耗时从「最慢者」降到「最快者」即可见）。
     *
     * 回调在 ioPool 后台线程发出（多个供应商并发、顺序不保证），回调里不要碰 UI，
     * 调用方自行保证线程安全。返回值在全部完成后给出聚合目录与每个失败供应商的原因。
     *
     * [models] 等价于本函数丢弃回调、只取最终聚合的阻塞版本。
     */
    fun modelsStreaming(onChunk: (ProviderModelsChunk) -> Unit): ModelsCatalog {
        val enabledProviders = registry.all().filter { providerSettings(it.id).enabled }
        if (enabledProviders.isEmpty()) return ModelsCatalog(emptyList())

        val futures = enabledProviders.map { provider ->
            ioPool.submit<ProviderModelsChunk> {
                val chunk = fetchProviderModels(provider)
                onChunk(chunk)
                chunk
            }
        }

        val aggregated = ArrayList<RoutedModel>()
        val failures = LinkedHashMap<String, String>()
        for (future in futures) {
            val chunk = try {
                future.get()
            } catch (_: Exception) {
                continue
            }
            aggregated.addAll(chunk.models)
            chunk.error?.let { failures[chunk.providerId] = it }
        }
        return ModelsCatalog(aggregated, failures)
    }

    /** 阻塞聚合版：HTTP `/v1/models`、CLI 等需要一次性完整列表的场景使用。 */
    fun models(): List<RoutedModel> = modelsStreaming { }.models

    /** 拉取单个供应商的模型目录（id 带 `provider/` 前缀；区域型供应商按区域拆成多组）。 */
    private fun fetchProviderModels(provider: Provider): ProviderModelsChunk {
        return try {
            val picked = pool.pick(provider.id)
            var account = picked
            // 若选中的账号凭证临期或已过期，在拉取前执行一次安全的 refreshAccount 保鲜
            if (account != null) {
                try {
                    val refreshed = provider.refreshAccount(account, settings.refreshSkewSeconds)
                    if (refreshed != null) {
                        pool.saveAccount(refreshed)
                        account = refreshed
                    }
                } catch (_: Exception) {}
            }
            val view = provider.listModels(account)
            val models = ArrayList<RoutedModel>()
            val regionAware = provider as? dev.aigw.core.provider.RegionAwareSupport
            for (model in view.models) {
                if (settings.onlyUsableModels && provider.isInternalModel(model.id)) continue
                if (regionAware != null) {
                    for (region in regionAware.regions()) {
                        val label = when (region) {
                            "cn" -> "国内"
                            "global" -> "国外"
                            else -> region
                        }
                        models.add(
                            RoutedModel(
                                provider.id,
                                "${provider.displayName}（$label）",
                                model,
                                routePrefix = "${provider.id}-$region",
                                region = region,
                            ),
                        )
                    }
                } else {
                    models.add(RoutedModel(provider.id, provider.displayName, model))
                }
            }
            // 供应商自报错误只在拿不到任何模型时才算失败；带内置快照的回退
            // （如 CodeBuddy 无账号时显示 FALLBACK_MODELS）不是故障，报红反而吓人
            ProviderModelsChunk(provider.id, models, if (models.isEmpty()) view.error.ifEmpty { null } else null)
        } catch (e: Exception) {
            logWarn("拉取模型目录失败（${provider.id}）：${e.message}")
            ProviderModelsChunk(provider.id, emptyList(), e.message ?: "拉取失败")
        }
    }

    /**
     * 把客户端传来的模型名解析成「哪个供应商 + 哪个模型」。
     *
     * 智能路由逻辑：
     * 1. `provider/model` 形式支持大小写不敏感与别名前缀映射（如 google/gemini-... -> antigravity/gemini-...）；
     * 2. 无前缀时，按模型名特征智能识别供应商（如 gemini/claude -> antigravity，doubao/seed -> trae）；
     * 3. 若只配置了一个供应商的账号，自动由该供应商接管所有请求；
     * 4. 其它情况落到有账号的供应商或 `defaultProvider`。
     */
    fun resolveRoute(requested: String): Route? {
        var model = requested.trim()
        if (model.isEmpty()) return null

        // 剥离可能存在的 "models/" 前缀（Google/Gemini 客户端与 SDK 的常见标准命名）
        if (model.startsWith("models/", ignoreCase = true)) {
            model = model.substring(7).trim()
        }

        val slash = model.indexOf('/')
        if (slash > 0) {
            val rawPrefix = model.substring(0, slash).trim()
            val subModel = model.substring(slash + 1).trim()
            val normalized = normalizeProviderPrefix(rawPrefix)
            if (normalized != null && registry.get(normalized) != null) {
                // 区域型前缀（如 codebuddy-cn/glm-5.2）：路由到该供应商并把区域约束带上，
                // 选号只在该区域的账号中进行
                val region = regionPrefixOf(rawPrefix.lowercase(), normalized)
                return Route(normalized, subModel, region)
            }
        }

        // 无前缀或前缀未识别：按模型名特征推导
        val inferred = inferProviderByModel(model)
        if (inferred != null && registry.get(inferred) != null) {
            if (providerSettings(inferred).enabled && pool.size(inferred) > 0) {
                return Route(inferred, model)
            }
        }

        // 检查当前所有已启用且配置了可用账号的供应商
        val activeProviders = registry.all()
            .map { it.id }
            .filter { providerSettings(it).enabled && pool.size(it) > 0 }

        // 如果用户只配置了一个供应商的账号，所有请求由该供应商接管
        if (activeProviders.size == 1) {
            return Route(activeProviders.first(), model)
        }

        // 若推导出了供应商，即使暂未检测到账号也按推导走（报错时能清晰报出该供应商）
        if (inferred != null && registry.get(inferred) != null) {
            return Route(inferred, model)
        }

        // 最终兜底：优先选第一个有账号的供应商，再看 defaultProvider
        val fallback = activeProviders.firstOrNull()
            ?: settings.defaultProvider.takeIf { registry.get(it) != null && pool.size(it) > 0 }
            ?: settings.defaultProvider.takeIf { registry.get(it) != null }
            ?: registry.all().firstOrNull()?.id
            ?: return null

        return Route(fallback, model)
    }

    /**
     * 把路由前缀解析成区域约束。
     *
     * 两种形式都认：
     * - `codebuddy-cn` / `codebuddy-global`（模型页展示的区域后缀前缀）；
     * - `workbuddy-cn` / `workbuddy-global`。
     * 非区域型前缀（如 `trae`、`google`）返回 null。
     */
    private fun regionPrefixOf(rawPrefixLower: String, providerId: String): String? {
        val provider = registry.get(providerId) as? dev.aigw.core.provider.RegionAwareSupport ?: return null
        for (region in provider.regions()) {
            if (rawPrefixLower == "${providerId.lowercase()}-$region" ||
                rawPrefixLower == "workbuddy-$region" ||
                rawPrefixLower == "codebuddy-$region"
            ) {
                return region
            }
        }
        return null
    }

    /**
     * 在指定供应商中选号；[region] 非空时只选属于该区域且处于可用状态的账号中余额最高者。
     */
    fun pickAccount(providerId: String, exclude: Set<String> = emptySet(), region: String? = null): ProviderAccount? {
        if (region == null) return pool.pick(providerId, exclude)
        val regionAware = registry.get(providerId) as? dev.aigw.core.provider.RegionAwareSupport ?: return pool.pick(providerId, exclude)
        // 一次取状态快照（单次加锁），避免对每个账号各加一次读锁
        val statusByUid = pool.statuses(providerId).associateBy { it.uid }
        return pool.accounts(providerId)
            .filter { account -> account.uid !in exclude }
            .filter { account -> statusByUid[account.uid]?.usable == true }
            .filter { account -> regionAware.regionOf(account) == region }
            .maxByOrNull { account -> statusByUid[account.uid]?.credits ?: 0L }
    }

    /**
     * 解析请求模型的所有候选路由（支持同款模型跨供应商故障转移 Failover）。
     *
     * 1. 若配置了显式多供应商优先级映射表，按优先级（priority 降序）依次尝试；
     * 2. 否则自动回退到启发式同名/同款探测逻辑；
     * 3. 熔断中或无可用账号的供应商会被合理标记或降级。
     */
    fun resolveCandidateRoutes(requested: String): List<Route> {
        val primary = resolveRoute(requested) ?: return emptyList()
        val requestedModelKey = requested.trim().removePrefix("models/").trim()
        val configuredCandidates = failoverSettings.routes[requestedModelKey]
            ?: failoverSettings.routes[primary.model]

        if (!configuredCandidates.isNullOrEmpty() && failoverSettings.enabled) {
            val list = ArrayList<Route>()
            // 按 priority 降序
            val sorted = configuredCandidates.sortedByDescending { it.priority }
            for (c in sorted) {
                if (registry.get(c.providerId) != null && providerSettings(c.providerId).enabled) {
                    list.add(Route(c.providerId, c.upstreamModel))
                }
            }
            if (list.isNotEmpty()) return list
        }

        // 回退逻辑：启发式寻找支持同款模型的供应商
        val candidates = ArrayList<Route>()
        candidates.add(primary)
        val seen = HashSet<String>()
        seen.add(primary.providerId)

        val baseModel = primary.model.substringAfterLast('/').trim()
        val normBase = normalizeModelKey(baseModel)

        for (provider in registry.all()) {
            if (provider.id in seen) continue
            if (!providerSettings(provider.id).enabled) continue
            // 备用供应商必须有可用账号
            if (pool.pick(provider.id) == null) continue

            val matchedModel = findMatchingModel(provider, baseModel, normBase)
            if (matchedModel != null) {
                candidates.add(Route(provider.id, matchedModel))
                seen.add(provider.id)
            }
        }
        return candidates
    }

    private fun normalizeModelKey(raw: String): String {
        return raw.lowercase()
            .replace("_", "-")
            .replace(" ", "-")
            .trim()
    }

    private fun findMatchingModel(provider: Provider, baseModel: String, normBase: String): String? {
        if (provider is dev.aigw.core.provider.custom.CustomProvider) {
            for (m in provider.savedModels()) {
                if (normalizeModelKey(m) == normBase || m.equals(baseModel, ignoreCase = true) || isEquivalentModel(normalizeModelKey(m), normBase)) {
                    return m
                }
            }
            return null
        }

        val catalog = runCatching { provider.listModels(pool.pick(provider.id)) }.getOrNull()
        if (catalog != null) {
            for (m in catalog.models) {
                val norm = normalizeModelKey(m.id)
                if (norm == normBase || m.id.equals(baseModel, ignoreCase = true) || isEquivalentModel(norm, normBase)) {
                    return m.id
                }
            }
        }
        return null
    }

    private fun isEquivalentModel(a: String, b: String): Boolean {
        if (a == b) return true
        // 剥离版本或性能修饰符对比：如 deepseek-v4-pro vs deepseek-v4-flash, gemini-3.8-flash vs gemini-3.8-flash-high
        val cleanA = a.removeSuffix("-high").removeSuffix("-low").removeSuffix("-preview")
        val cleanB = b.removeSuffix("-high").removeSuffix("-low").removeSuffix("-preview")
        return cleanA == cleanB
    }

    /**
     * 探测指定供应商模型的连通性与网络延迟（毫秒）。
     *
     * 发送极轻量的探针请求，耗时低于 5000ms 返回正整数，超时或连接失败返回 -1L。
     */
    fun probeModelLatency(providerId: String, modelId: String, region: String? = null): ProbeResult {
        val provider = registry.get(providerId)
            ?: return ProbeResult(-1L, "未知供应商 $providerId")
        val account = pickAccount(providerId, region = region)
            ?: return ProbeResult(-1L, "没有可用账号")
        val resolvedModel = provider.resolveModel(modelId)
        val probeBody = JsonObject().apply {
            addProperty("model", resolvedModel)
            addProperty("max_tokens", 1)
            addProperty("stream", false)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "ping")
                })
            })
        }.toString()

        val start = System.currentTimeMillis()
        val call = try {
            provider.openChat(account, probeBody)
        } catch (e: Exception) {
            val msg = e.message ?: "连接失败"
            logWarn("探测 $providerId/$resolvedModel 失败：$msg")
            return ProbeResult(-1L, msg)
        }

        try {
            if (call.status in 200..299 && call.failure == null) {
                return ProbeResult((System.currentTimeMillis() - start).coerceAtLeast(1L), "")
            }
            val reason = call.failure?.message ?: call.errorBody.ifEmpty { "HTTP ${call.status}" }
            logWarn("探测 $providerId/$resolvedModel 失败（HTTP ${call.status}）：${reason.take(300)}")
            return ProbeResult(-1L, "HTTP ${call.status}：${reason.take(300)}")
        } finally {
            call.close()
        }
    }

    private fun normalizeProviderPrefix(prefix: String): String? {
        val existing = registry.get(prefix)
        if (existing != null) return existing.id
        val lower = prefix.lowercase()
        return when {
            lower in listOf("antigravity", "google", "gemini", "agy", "alphabet") -> "antigravity"
            lower in listOf("codebuddy", "workbuddy", "tencent", "wb") -> "codebuddy"
            lower.startsWith("codebuddy-") || lower.startsWith("workbuddy-") -> "codebuddy"
            lower in listOf("trae", "bytedance", "doubao", "solo") -> "trae"
            lower in listOf("loomy", "iflytek", "spark", "xf") -> "loomy"
            else -> if (lower.startsWith("custom:")) lower else null
        }
    }

    private fun inferProviderByModel(model: String): String? {
        val lower = model.lowercase()
        return when {
            lower.startsWith("gemini") || lower.startsWith("antigravity") -> "antigravity"
            lower.startsWith("claude") && (lower.contains("thinking") || lower.contains("4-6") || lower.contains("sonnet") || lower.contains("opus")) -> "antigravity"
            lower.startsWith("doubao") || lower.startsWith("seed-") -> "trae"
            lower.startsWith("spark") || lower.contains("星火") -> "loomy"
            lower.startsWith("deepseek") || lower.startsWith("kimi") || lower.startsWith("minimax") || lower.startsWith("glm") || lower.startsWith("qwen") -> {
                listOf("codebuddy", "trae").firstOrNull { pool.size(it) > 0 }
            }
            else -> null
        }
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

    /** 详细日志开关（全链路请求/转发/发送/返回原文）。 */
    fun verboseLogging(): Boolean = settings.verboseLogging

    /** 详细日志：分多段写入请求日志，避免单条过长被环形缓冲截断。 */
    internal fun logVerbose(tag: String, text: String) {
        if (!settings.verboseLogging) return
        val limit = VERBOSE_LINE_LIMIT
        var start = 0
        var part = 0
        while (start < text.length) {
            val end = minOf(start + limit, text.length)
            requestLog.info("[${tag}] ${if (part == 0) "" else "(续${part}) "}" + text.substring(start, end))
            start = end
            part++
        }
        if (text.isEmpty()) requestLog.info("[$tag] （空）")
    }

    private companion object {
        const val DAY_MILLIS = 24L * 3600 * 1000

        /** 详细日志单条上限：环形日志每条都全量展示，太长会淹没其它日志。 */
        const val VERBOSE_LINE_LIMIT = 1500

        /** 「截断超长记录内容」默认截到 4K 字符：足够看清请求，又不至于让存储爆掉。 */
        const val DEFAULT_TRUNCATE_CHARS = 4_000

        const val CUSTOM_PREFIX = "custom:"

        /** 自动补刷额度的同账号最小间隔：对话连发时不打爆上游积分接口。 */
        private const val AUTO_CREDIT_REFRESH_MIN_INTERVAL_MS = 10_000L

        /** Trae 登录回调用的本地端口（避开 Antigravity 的 51121）。 */
        const val TRAE_CALLBACK_PORT = 51120
        const val TRAE_CALLBACK_PATH = "/authorize"

        /** 小浣熊（商汤）登录回调用的本地端口。 */
        const val RACCOON_CALLBACK_PORT = 51122
        const val RACCOON_CALLBACK_PATH = "/callback"

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
