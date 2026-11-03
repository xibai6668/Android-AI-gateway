package dev.aigw.core.provider

/**
 * 登录能力的可选接口族。
 *
 * 五种登录流程差异过大（WebView 回调 / 设备码轮询 / 短信 / OAuth loopback / 无），
 * 不强行塞进单一接口：provider 实现自己支持的那一个，网关按类型分派。
 * 无论哪种流程，产出都是统一的 [ProviderAccount]。
 */

/** WebView 回调型（Trae）：打开 [loginUrl]，授权后回调到 [callbackUrl]。 */
data class WebLoginTicket(val id: String, val loginUrl: String, val callbackUrl: String)

/** 设备授权型（WorkBuddy / CodeBuddy）：打开 [loginUrl]，按 [state] 轮询换取凭证。 */
data class DeviceAuthTicket(val loginUrl: String, val state: String)

/** 设备授权轮询结果。 */
sealed interface DeviceAuthPoll {
    data object Pending : DeviceAuthPoll
    data class Success(val account: ProviderAccount) : DeviceAuthPoll
    data class Failed(val error: String) : DeviceAuthPoll
}

interface WebLoginSupport {
    /** [region] 为供应商自定义的区域标识（如 Trae 的 trae.cn / trae.ai），无区域概念时可忽略。 */
    fun beginWebLogin(callbackUrl: String, region: String = ""): WebLoginTicket

    /** 回调链接可能来自 WebView 拦截，也可能由用户粘贴。 */
    fun completeWebLogin(callbackUrl: String): ProviderAccount
}

interface DeviceCodeSupport {
    /** [region] 为供应商自定义的区域标识（如 WorkBuddy 的 cn/global），无区域概念时可忽略。 */
    fun startDeviceAuth(region: String = ""): DeviceAuthTicket

    fun pollDeviceAuth(state: String, region: String = ""): DeviceAuthPoll
}

interface SmsLoginSupport {
    /**
     * 发验证码，返回上游 msgid（登录时回传）。
     *
     * [ccode] 是国家码（不带 `+`，如中国大陆 `86`、香港 `852`）。
     * 上游按国家码路由短信通道，写死 86 会让非大陆号码收不到验证码。
     */
    fun sendSmsCode(phone: String, ccode: String = DEFAULT_CCODE): String

    fun loginBySmsCode(phone: String, code: String, msgid: String, ccode: String = DEFAULT_CCODE): ProviderAccount

    companion object {
        /** 默认国家码：中国大陆。 */
        const val DEFAULT_CCODE = "86"
    }
}

interface LoopbackOAuthSupport {
    fun buildAuthUrl(): String

    /** 用回调 URL 里的 authorization code 换凭证。 */
    fun exchangeCode(code: String): ProviderAccount

    /**
     * 本地回调监听的端口。
     *
     * 默认沿用 Antigravity 的注册值（历史行为）；redirect_uri 是上游注册的常量，
     * 需要自己的回调地址的供应商（如 ChatGPT 的 localhost:1455/auth/callback）覆盖这里，
     * 否则网关监听的端口对不上，回调永远收不到。
     */
    val callbackPort: Int get() = 51121

    /** 本地回调监听的路径；同 [callbackPort]。 */
    val callbackPath: String get() = "/oauth-callback"

    /**
     * 用完整回调 URL 完成登录；默认实现只取 code。
     * 需要 state 防重放/一次性消费的供应商（如 ChatGPT）覆盖此方法拿完整 URL。
     */
    fun completeCallback(callbackUrl: String): ProviderAccount =
        exchangeCode(queryParam(callbackUrl, "code") ?: throw IllegalStateException("回调里没有 code 参数"))
}
