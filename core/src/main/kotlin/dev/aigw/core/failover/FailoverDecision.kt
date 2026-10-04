package dev.aigw.core.failover

/** 错误决策动作：指导调度器下一步行为 */
enum class FailoverDecision {
    /** 可重试瞬态故障：在当前供应商内退避重试（网络中断、超时、429、5xx） */
    RETRY_CURRENT_PROVIDER,

    /** 当前供应商不可用：立即切换下一家候选供应商（401/403鉴权失效、余额耗尽、账号硬禁用） */
    SWITCH_NEXT_PROVIDER,

    /** 客户端自身不可重试错误：立即向客户端报错终止，绝不重试或污染后续候选（400参数非法、内容审核违规、上下文超长） */
    ABORT_IMMEDIATELY,
}

data class ClassifiedError(
    val statusCode: Int,
    val errorCode: String,
    val message: String,
    val isTimeout: Boolean = false,
    val isNetworkFailure: Boolean = false,
) {
    fun decide(): FailoverDecision = when {
        // 客户端参数错、超长、内容审核被拒：绝不重试也不换家，直接报错
        statusCode == 400 && (errorCode in listOf("invalid_request_error", "context_length_exceeded", "invalid_argument")) ->
            FailoverDecision.ABORT_IMMEDIATELY
        statusCode == 422 || errorCode == "content_policy_violation" ->
            FailoverDecision.ABORT_IMMEDIATELY

        // 鉴权失败、欠费封禁、凭证失效：当前供应商彻底无解，立即换下家
        statusCode in listOf(401, 403) || errorCode in listOf("account_deactivated", "insufficient_quota", "SESSION_DEAD") ->
            FailoverDecision.SWITCH_NEXT_PROVIDER

        // 超时、网络层断开、429限流、5xx服务崩溃：在当前供应商内退避重试
        isTimeout || isNetworkFailure || statusCode == 429 || statusCode in 500..599 ->
            FailoverDecision.RETRY_CURRENT_PROVIDER

        else -> FailoverDecision.SWITCH_NEXT_PROVIDER
    }
}
