package dev.aigw.core.provider

/**
 * 网关注入给 provider 的回调。
 *
 * provider 在解析上游流时用它上报计费回报与账号级错误，避免 provider 直接依赖账号池。
 */
class ProviderHooks(
    /** 流内计费回报（如 Trae 的 `ide_credits`）。 */
    val onBilling: (providerId: String, uid: String, credits: Long) -> Unit = { _, _, _ -> },
    /** 流内业务错误，网关据此禁用凭证失效的账号。 */
    val onAccountError: (providerId: String, uid: String, error: UpstreamError) -> Unit = { _, _, _ -> },
    /** provider 内部刷新了账号凭证时（如 Antigravity 轮换 Token），通知账号池持久化。 */
    val onAccountUpdated: (account: ProviderAccount) -> Unit = {},
    val onLog: (String) -> Unit = {},
    /** 详细日志：provider 上报真实发给上游的内容（如 Antigravity 的 envelope），仅开启详细日志时生效。 */
    val onVerbose: (tag: String, text: String) -> Unit = { _, _ -> },
)
