package dev.aigw.core.provider.minimax

/**
 * MiniMax Agent（国内网页端 agent.minimaxi.com）的上游常量。
 *
 * 协议要点来自公开逆向实现（xiaoY233/MiniMax-Free-API 与 JMJAJ/minimax-wrapper）：
 * `/v1/api/` 前缀是用户/设备/文件等老接口（响应包裹 `statusInfo`），
 * `/matrix/api/v1/` 前缀是 Agent 聊天/商务接口（响应包裹 `base_resp`）。
 */
object MiniMaxConstants {
    const val API_BASE = "https://agent.minimaxi.com"

    const val PATH_DEVICE_REGISTER = "/v1/api/user/device/register"
    const val PATH_USER_INFO = "/v1/api/user/info"
    const val PATH_CHAT_SEND = "/matrix/api/v1/chat/send_msg"
    const val PATH_MEMBERSHIP = "/matrix/api/v1/commerce/get_membership_info"
    const val PATH_CHAT_DELETE = "/v1/api/chat/history/"

    // ---- 伪装 query（对齐国内版网页端；顺序参与签名，不要调整） ----
    const val DEVICE_PLATFORM = "web"
    const val BIZ_ID = "3"
    const val APP_ID = "3001"
    const val VERSION_CODE = "22201"
    const val OS_NAME = "Mac"
    const val BROWSER_NAME = "chrome"
    const val DEVICE_MEMORY = "8"
    const val CPU_CORE_NUM = "11"
    const val BROWSER_LANGUAGE = "zh-CN"
    const val BROWSER_PLATFORM = "MacIntel"
    const val SCREEN_WIDTH = "1920"
    const val SCREEN_HEIGHT = "1080"
    const val LANG = "zh"

    // ---- 请求签名 ----
    /** yy 的固定尾盐。 */
    const val SIGN_SALT = "ooui"

    /**
     * x-signature 的静态密钥（JMJAJ/minimax-wrapper 声明逆向自网页 minified JS）。
     * xiaoY233 的国内版实现用的是 `md5(ts + token + body)`；两实现冲突，先按 JS 逆向版，
     * 真机 403/业务签名错误时把 [MiniMaxSign.xSignature] 换成 token 版再试。
     */
    const val SECRET_KEY = "I*7Cf%WZ#S&%1RlZJ&C2"

    /** 设备信息有效期（秒），过期后重新注册。 */
    const val DEVICE_INFO_TTL_SECONDS = 3 * 3600L

    // ---- 对话 ----
    /** Lightning（快速模式）。 */
    const val CHAT_TYPE_LIGHTNING = 1L
    /** Pro（Agent 模式，消耗积分更多）。 */
    const val CHAT_TYPE_PRO = 0L
}
