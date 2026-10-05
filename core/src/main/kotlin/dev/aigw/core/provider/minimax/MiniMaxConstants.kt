package dev.aigw.core.provider.minimax

/**
 * MiniMax 供应商的常量配置。
 *
 * 覆盖：
 * 1. 官方 OAuth 2.0 Device Authorization Grant（RFC 8628 + PKCE）：
 *    国内 `https://account.minimaxi.com`，国际 `https://account.minimax.io`
 * 2. 官方 API Base：
 *    国内 `https://api.minimax.cn`，国际 `https://api.minimax.io`
 * 3. 官方 Coding Plan / Token Plan 额度接口 `/v1/token_plan/remains`
 * 4. 网页端私有接口备用端点
 */
object MiniMaxConstants {
    const val CLIENT_ID = "659cf4c1-615c-45f6-a5f6-4bf15eb476e5"
    const val CLIENT_NAME = "MiniMax CLI"
    val SCOPES = listOf("openid", "profile", "coding_plan")

    const val REGION_CN = "cn"
    const val REGION_GLOBAL = "global"

    // OAuth 授权服务域名
    const val OAUTH_BASE_CN = "https://account.minimaxi.com"
    const val OAUTH_BASE_GLOBAL = "https://account.minimax.io"

    // API 网关服务域名
    const val API_BASE_CN = "https://api.minimax.cn"
    const val API_BASE_GLOBAL = "https://api.minimax.io"

    // 网页端 Agent 基地址（备用）
    const val AGENT_BASE_CN = "https://agent.minimaxi.com"
    const val AGENT_BASE_GLOBAL = "https://agent.minimax.io"
    const val API_BASE = AGENT_BASE_CN

    // OAuth 2.0 端点
    const val PATH_DEVICE_CODE = "/oauth2/device/code"
    const val PATH_OAUTH_TOKEN = "/oauth2/token"

    // 官方对话与额度端点
    const val PATH_CHAT_COMPLETIONS = "/v1/chat/completions"
    const val PATH_TOKEN_PLAN_REMAINS = "/v1/token_plan/remains"

    // 网页端私有端点（备用）
    const val PATH_DEVICE_REGISTER = "/v1/api/user/device/register"
    const val PATH_USER_INFO = "/v1/api/user/info"
    const val PATH_CHAT_SEND = "/matrix/api/v1/chat/send_msg"
    const val PATH_MEMBERSHIP = "/matrix/api/v1/commerce/get_membership_info"
    const val PATH_CHAT_DELETE = "/v1/api/chat/history/"

    // 网页端伪装参数（备用）
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

    const val SIGN_SALT = "ooui"
    const val SECRET_KEY = "I*7Cf%WZ#S&%1RlZJ&C2"
    const val DEVICE_INFO_TTL_SECONDS = 3 * 3600L

    const val CHAT_TYPE_LIGHTNING = 1L
    const val CHAT_TYPE_PRO = 0L

    fun oauthBase(region: String): String =
        if (region.equals(REGION_GLOBAL, ignoreCase = true)) OAUTH_BASE_GLOBAL else OAUTH_BASE_CN

    fun apiBase(region: String): String =
        if (region.equals(REGION_GLOBAL, ignoreCase = true)) API_BASE_GLOBAL else API_BASE_CN
}
