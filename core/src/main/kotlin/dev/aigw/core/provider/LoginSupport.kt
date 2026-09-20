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
    fun beginWebLogin(callbackUrl: String): WebLoginTicket

    /** 回调链接可能来自 WebView 拦截，也可能由用户粘贴。 */
    fun completeWebLogin(callbackUrl: String): ProviderAccount
}

interface DeviceCodeSupport {
    /** [region] 为供应商自定义的区域标识（如 WorkBuddy 的 cn/global），无区域概念时可忽略。 */
    fun startDeviceAuth(region: String = ""): DeviceAuthTicket

    fun pollDeviceAuth(state: String, region: String = ""): DeviceAuthPoll
}

interface SmsLoginSupport {
    /** 发验证码，返回上游 msgid（登录时回传）。 */
    fun sendSmsCode(phone: String): String

    fun loginBySmsCode(phone: String, code: String, msgid: String): ProviderAccount
}

interface LoopbackOAuthSupport {
    fun buildAuthUrl(): String

    /** 用回调 URL 里的 authorization code 换凭证。 */
    fun exchangeCode(code: String): ProviderAccount
}
