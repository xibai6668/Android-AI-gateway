package dev.aigw.core.provider

import com.google.gson.JsonObject

/** 登录形态，UI 据此渲染不同的登录入口。 */
enum class AuthKind {
    /** WebView 打开授权页，回调到网关端口（Trae）。 */
    WEBVIEW_CALLBACK,

    /** 设备授权：WebView 打开授权页 + 轮询换取 token（WorkBuddy / CodeBuddy）。 */
    DEVICE_CODE,

    /** 手机号 + 短信验证码（Loomy）。 */
    SMS_CODE,

    /** 标准 OAuth loopback：WebView 拦截回调 URL 拿 code（Antigravity）。 */
    OAUTH_LOOPBACK,

    /** 无需登录，凭证即 API Key（自定义供应商）。 */
    NONE,
}

/** provider 支持的额外动作，UI 据此决定展示哪些按钮。 */
enum class ProviderCapability {
    /** 可刷新账号额度/积分。 */
    CREDIT_REFRESH,

    /** 支持每日签到。 */
    CHECKIN,

    /** 有可批量执行的「任务」（如 Loomy 的新手任务）。 */
    TASKS,
}

/** 供应商专属动作（provider 实现 [Provider.performAction]，UI 组件按名字调用）。 */

/** 每日签到；支持的 provider 与 UI 都按这个名字调用。 */
const val ACTION_CHECKIN = "checkin"

/**
 * 账号自带的任务/成长中心一键领取。
 *
 * 各家含义不同，由 provider 自己解释：Loomy = 新手任务；WorkBuddy = 成长中心（任务+旅行+盲盒）。
 */
const val ACTION_TASKS = "tasks"

/**
 * 一个额度包/账本条目。
 *
 * 字段缺失一律用 0（金额类）或空串（文本类），不要编造。
 */
data class QuotaPack(
    val name: String,
    /** 来源分组（如「每日签到」「团队」）。 */
    val group: String = "",
    val limit: Long = 0,
    val used: Long = 0,
    val remain: Long = 0,
    /** 过期时间（秒，0 表示不过期/未知）。 */
    val expireAt: Long = 0,
)

/**
 * 一个上游供应商。
 *
 * 抽象边界刻意定在「OpenAI 请求体 → OpenAI 格式响应」：[openChat] 接收标准 OpenAI 请求体，
 * 内部完成与上游私有协议的**双向**转换，返回的 [ChatCall] 已是 OpenAI 语义
 * （流式 SSE 或聚合后的 completion）。因此网关层（选号、冷却、透传）对 provider 差异零感知，
 * 新增供应商只需实现本接口并在 [ProviderRegistry] 登记一行。
 */
interface Provider {
    /** 稳定标识，也是模型名的前缀（如 `trae/Seed-2.1-Code`）。 */
    val id: String

    /** 界面展示名。 */
    val displayName: String

    val authKind: AuthKind

    val capabilities: Set<ProviderCapability> get() = emptySet()

    /** 模型目录；[account] 为空时用内置快照回退。 */
    fun listModels(account: ProviderAccount?): ProviderModelCatalogView

    /** 把客户端传来的模型名归一化为上游真实名；查不到原样放行，交给上游判定。 */
    fun resolveModel(requested: String): String

    /** 是否是「非对话用途」的内部条目（开启「只看可用模型」时隐藏）。 */
    fun isInternalModel(id: String): Boolean = false

    /** 发起一次对话。 */
    fun openChat(account: ProviderAccount, openAiBody: String): ChatCall

    /** 预刷新凭证；返回新账号表示 token 发生轮换（调用方需原子写回）。 */
    fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? = null

    /** 统一错误分类，供账号池决定冷却策略。 */
    fun classify(status: Int, body: String): UpstreamError

    /** 账号额度/积分；无此能力返回 null。 */
    fun creditInfo(account: ProviderAccount): CreditInfo? = null

    /** 额度包明细（可选）；没有额度包概念的 provider 返回空列表。 */
    fun creditPacks(account: ProviderAccount): List<QuotaPack> = emptyList()

    /**
     * 成长任务明细（可选）；不支持任务中心的 provider 返回 null。
     *
     * 与 [performAction] 的 `ACTION_TASKS` 不同：这里只**读取**任务列表与完成度，
     * 不发任何写操作，供 UI 展示「有哪些任务、哪些做了、哪些没做」。
     */
    fun taskList(account: ProviderAccount): ProviderTaskListView? = null

    /**
     * 该供应商用到的上游域名后缀（不带子域前缀）。
     *
     * 代理设置按「供应商」开关，实际判定就是拿目标域名与这里的后缀匹配，
     * 所以新增供应商时要把它的上游域名补上，否则代理开关对它无效。
     */
    fun hosts(): List<String> = emptyList()

    /** provider 专属动作（签到、兑换码等）。默认不支持。 */
    fun performAction(account: ProviderAccount, action: String, payload: JsonObject): ProviderActionResult =
        ProviderActionResult.unsupported(action)

    /** 粘贴 JSON 凭证导入；不支持则返回 null。 */
    fun importCredentials(raw: String): ProviderAccount? = null
}

/**
 * 账号有区域之分的供应商（如 WorkBuddy 的国内 cn / 国外 global）。
 *
 * 区域型供应商在模型页拆成多个独立前缀（`codebuddy-cn`、`codebuddy-global`），
 * 网关选号时只取指定区域的账号；实现方负责判断单个账号属于哪个区域。
 */
interface RegionAwareSupport {
    /** 判断一个账号属于哪个区域（如 cn/global）；无法判定时返回 null。 */
    fun regionOf(account: ProviderAccount): String?

    /** 该供应商支持的区域列表（如 [cn, global]）。 */
    fun regions(): List<String>
}
