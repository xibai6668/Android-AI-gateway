package dev.aigw.core.provider.trae

/**
 * Trae 两种发行版的上游地址。
 *
 *  - **国内版**（`trae.cn`）：字节 Trae SOLO 通道。
 *  - **国际版**（`trae.ai`）：海外发行版，额度接口在 **v1**（国内是 v2），且**没有签到**。
 *
 * 实测（2026-10-10）：
 *  - `POST api.trae.ai/cloudide/api/v3/trae/oauth/ExchangeToken` 对国内 client_id 返回
 *    401「refresh token is invalid」——端点真实，且 client_id 国内国际通用；
 *  - `POST ug-normal.trae.ai/trae/api/v1/pay/user_current_entitlement_list` 对假 JWT 返回
 *    401 `{"code":1001,...,"user_entitlement_pack_list":[]}` ——额度接口在 v1；
 *  - `www.trae.ai/trae/api/v2/pay/...` 返回 200 但那是 SPA 兜底页（所有路径都 200），**不是接口**。
 */
enum class TraeRegion(
    val id: String,
    val label: String,
    val agentHost: String,
    val ugHost: String,
    val oauthHost: String,
    val consoleHost: String,
) {
    CN(
        id = "cn",
        label = "国内版",
        agentHost = "https://trae-api-cn.mchost.guru",
        ugHost = "https://api.trae.cn",
        oauthHost = "https://api.trae.com.cn",
        consoleHost = "https://www.trae.cn",
    ),
    INTL(
        id = "global",
        label = "国际版",
        agentHost = "https://api.trae.ai",
        ugHost = "https://ug-normal.trae.ai",
        oauthHost = "https://api.trae.ai",
        consoleHost = "https://www.trae.ai",
    ),
    ;

    /** 额度/支付接口的版本段：国内 v2，国际 v1。 */
    val payVersion: String get() = if (this == INTL) "v1" else "v2"

    /** 国际版没有签到制度（前端 JS 里 checkin 零命中）。 */
    val supportsCheckin: Boolean get() = this == CN

    companion object {
        fun of(id: String?): TraeRegion =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: CN

        /** 按域名推断区域（含 `trae.ai` → 国际）。 */
        fun infer(explicit: String?, domain: String?): TraeRegion {
            if (!explicit.isNullOrBlank()) return of(explicit)
            val host = domain.orEmpty().lowercase()
            return if (host.contains("trae.ai")) INTL else CN
        }
    }
}

/** Trae 上游常量。取值来自实测，改动需重新验证。 */
object TraeConstants {
    // 注意：Kotlin 的 const val 只能用编译期常量初始化，不能引用 enum 成员。
    val AGENT_HOST = TraeRegion.CN.agentHost
    val UG_HOST = TraeRegion.CN.ugHost
    val OAUTH_HOST = TraeRegion.CN.oauthHost
    val CONSOLE_HOST = TraeRegion.CN.consoleHost

    const val CLIENT_ID = "en1oxy7wnw8j9n"
    const val APP_ID = "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8"

    /** 上游只放行该 function 取值，work / solo / work_lite 均无效。 */
    const val FUNCTION = "solo_work_lite"

    const val EP_CHAT = "/api/agent/v3/llm_utils_chat"
    const val EP_MODELS = "/api/ide/v1/get_detail_param"
    const val EP_EXCHANGE = "/cloudide/api/v3/trae/oauth/ExchangeToken"
    const val EP_USER_INFO = "/cloudide/api/v3/trae/GetUserInfo"
    const val EP_CHECKIN_STATUS = "/trae/api/v2/ug/checkin_credits/status"
    const val EP_CHECKIN_CLAIM = "/trae/api/v2/ug/checkin_credits/claim"

    /**
     * 额度查询的候选接口（国内版 v2）。不同入口（网页版 / IDE）暴露的路径不一样，按顺序试：
     *  - web_user_ent_usage：网页版积分接口，响应最完整（含 usage_summary 与额度包明细）
     *  - ide_user_ent_usage：IDE 侧同名接口
     *  - user_current_entitlement_list：需 require_usage 才会带 usage
     *
     * 国际版只有 [EP_ENTITLEMENT_INTL] / [EP_ENT_USAGE_INTL] 两个 v1 接口，**没有 web 版**。
     */
    const val EP_ENT_USAGE_WEB = "/trae/api/v2/pay/web_user_ent_usage"
    const val EP_ENT_USAGE = "/trae/api/v2/pay/ide_user_ent_usage"
    const val EP_ENTITLEMENT_LIST = "/trae/api/v2/pay/user_current_entitlement_list"

    /** 国际版（v1）额度接口。 */
    const val EP_ENTITLEMENT_INTL = "/trae/api/v1/pay/user_current_entitlement_list"
    const val EP_ENT_USAGE_INTL = "/trae/api/v1/pay/ide_user_ent_usage"
    const val EP_USER_PAY_STATUS_INTL = "/trae/api/v1/pay/ide_user_pay_status"

    /** 请求未指定模型时使用的 config_name。 */
    const val DEFAULT_CONFIG_NAME = "glm-5.2"

    /** SOLO 通道计费落在 ide_credits 上（work_credits 不可反代）。 */
    const val REGION_CN = "trae.cn"
}

/**
 * 客户端版本号。上游按 X-Ide-Version 决定模型可用性，版本过旧会返回 4001，
 * 因此做成可配置项而不是硬编码常量。
 */
data class TraeVersion(
    val ideVersion: String = "0.1.52",
    val ideVersionCode: String = "20260811",
    val pluginVersion: String = "2.3.62834",
    val deviceBrand: String = "83DG",
    val osVersion: String = "Windows 11 Pro",
)
