package dev.aigw.core.provider.trae

/** Trae 国内版 SOLO 通道的上游常量。取值来自实测，改动需重新验证。 */
object TraeConstants {
    const val AGENT_HOST = "https://trae-api-cn.mchost.guru"
    const val UG_HOST = "https://api.trae.cn"
    const val OAUTH_HOST = "https://api.trae.com.cn"
    const val CONSOLE_HOST = "https://www.trae.cn"

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
     * 额度查询的候选接口。不同入口（网页版 / IDE）暴露的路径不一样，按顺序试：
     *  - web_user_ent_usage：网页版积分接口，响应最完整（含 usage_summary 与额度包明细）
     *  - ide_user_ent_usage：IDE 侧同名接口
     *  - user_current_entitlement_list：需 require_usage 才会带 usage
     */
    const val EP_ENT_USAGE_WEB = "/trae/api/v2/pay/web_user_ent_usage"
    const val EP_ENT_USAGE = "/trae/api/v2/pay/ide_user_ent_usage"
    const val EP_ENTITLEMENT_LIST = "/trae/api/v2/pay/user_current_entitlement_list"

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
