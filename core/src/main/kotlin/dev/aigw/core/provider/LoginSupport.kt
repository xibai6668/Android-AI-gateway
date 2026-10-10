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
}
