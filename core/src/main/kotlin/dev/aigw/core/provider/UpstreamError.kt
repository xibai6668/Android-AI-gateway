package dev.aigw.core.provider

/** 统一的上游错误分类，决定账号是长冷却、短冷却还是硬禁用。 */
enum class ErrorKind {
    /** 额度/积分耗尽：长冷却。 */
    QUOTA,

    /** 限流（429 等）：短冷却，不累计错误次数。 */
    SOFT_RATE,

    /** 凭证失效：硬禁用，必须重新登录。 */
    SESSION_DEAD,

    /** 路由不存在：短冷却。 */
    NOT_FOUND,

    /** 客户端问题（模型名/参数不对）：不该连累账号。 */
    CLIENT,

    /** 上游 5xx。 */
    SERVER,

    /** 网络层失败（连不上、超时）。 */
    NETWORK,
}

/** 一次上游错误的分类结果；[message] 必须是能直接给用户看的人话。 */
data class UpstreamError(
    val kind: ErrorKind,
    val message: String,
    val code: Long = 0,
)
