package dev.aigw.core.provider.loomy

/**
 * Loomy 上游接口常量。
 *
 * 来源：Loomy 桌面端 0.9.38（Electron 打包产物）的 `resources/.env.prod` 与
 * `electron/` 主进程源码。桌面端把该配置加密成 `LOOMYENC1:` 密文分发，
 * 解密口令随客户端一起下发——本质是混淆而非密钥保密，这里如实照搬即可。
 */
object LoomyConstants {

    /** 讯飞账号服务（登录/用户信息）。请求需要 HMAC-SHA1 签名。 */
    const val ACCOUNT_BASE = "https://account.xfinfr.com"

    /** 积分与 iModel（OpenAI 兼容对话）服务。请求带 `token` 头即可。 */
    const val API_BASE = "https://loomyad.xunfei.cn"

    /** 客户端标识，随每个请求的 base 字段上报。 */
    const val APP_ID = "GM3LOOMY"

    /** 签名用的 AccessKey，随客户端分发。 */
    const val ACCESS_KEY_ID = "2thryby66wxi53sk"

    /** 签名用的 AccessKeySecret，随客户端分发。 */
    const val ACCESS_KEY_SECRET = "zsak6eadrbawz683wf5r3m2snrwj868r"

    /** 上报的客户端版本；上游对未知版本较宽容，但仍按官方客户端的形态发。 */
    const val CLIENT_VERSION = "0.9.38"

    // ------------------------------------------------------------------ 账号

    const val PATH_SEND_SMS_CODE = "/login/phone/sendMsgCode"
    const val PATH_LOGIN_BY_SMS = "/login/phone/checkCode"
    const val PATH_USER_INFO = "/userinfo/query/baseInfo"

    // ------------------------------------------------------------------ 积分与模型

    const val PATH_MODELS = "/api/v1/models"
    const val PATH_CHAT_COMPLETIONS = "/api/v1/chat/completions"
    const val PATH_POINTS_RECORDS = "/api/v1/points/records"
    const val PATH_POINTS_ACTIVATION = "/api/v1/points/activation"
    const val PATH_REDEMPTION_REDEEM = "/api/v1/points/redemption-codes/redeem"

    /** 团队积分余额（账号加入团队时，消耗走的是这里）。 */
    const val PATH_TEAM_POINTS_BALANCE = "/api/v1/team-points/balance"

    /** 新手任务：列表与完成。 */
    const val PATH_ONBOARDING_TASKS = "/api/v1/onboarding/tasks"
    const val PATH_ONBOARDING_COMPLETE = "/api/v1/onboarding/tasks/complete"

    // ------------------------------------------------------------------ 协议常量

    /** 上游业务成功码。 */
    const val CODE_OK = "000000"

    /** session 失效（账号服务）。 */
    const val CODE_SESSION_INVALID = "020002"

    /** token 缺失或失效（积分/模型服务）。 */
    const val CODE_TOKEN_INVALID = "100002"

    /**
     * 默认国家码（中国大陆）。
     *
     * 讯飞按国家码路由短信通道：只发 `phone` 不带对的国家码，非大陆号码收不到验证码。
     */
    const val DEFAULT_CCODE = "86"

    /** 短信验证码有效期（秒），与官方客户端一致。 */
    const val SMS_CODE_EXPIRE_SECONDS = 300

    /** 登录 session 有效期（秒）：14 天。 */
    const val SESSION_EXPIRE_SECONDS = 14 * 24 * 3600

    /** 模型服务对缺 `traceparent` 的请求会挂到超时，所以每个请求都要带。 */
    const val TRACEPARENT_HEADER = "traceparent"

    /** 版本头，官方客户端用 `loomy-version`。 */
    const val VERSION_HEADER = "loomy-version"

    /** session 认证头（非标准，官方客户端用裸 `token`）。
     *  注意：只有积分/模型类接口用它；对话接口要 `Authorization: Bearer`（已实测）。 */
    const val TOKEN_HEADER = "token"
}
