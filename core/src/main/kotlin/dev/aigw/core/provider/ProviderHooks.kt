package dev.aigw.core.provider

/**
 * 网关注入给 provider 的回调。
 *
 * provider 在解析上游流时用它上报计费回报与账号级错误，避免 provider 直接依赖账号池。
 */
class ProviderHooks(
    /** 流内计费回报（如 Trae 的 `ide_credits`）。 */
    val onBilling: (providerId: String, uid: String, credits: Long) -> Unit = { _, _, _ -> },
    /** 流内业务错误，网关据此冷却账号。 */
    val onAccountError: (providerId: String, uid: String, error: UpstreamError) -> Unit = { _, _, _ -> },
    val onLog: (String) -> Unit = {},
)
