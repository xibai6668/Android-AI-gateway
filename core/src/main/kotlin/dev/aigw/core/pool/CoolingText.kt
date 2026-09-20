package dev.aigw.core.pool

/**
 * 冷却状态的可读文案。
 *
 * 放在 core 而不是 UI：时长换算与原因文案都是纯逻辑，且必须与 [CoolKind] 的语义保持一致；
 * 集中在此便于单测，也不会随界面改动而漂移。
 */
fun AccountStatus.coolingText(nowMillis: Long = System.currentTimeMillis()): String = when {
    disabled -> "凭证失效，需重新登录"
    !enabled -> "已停用"
    !cooling -> "可用"
    else -> buildString {
        append("冷却中（")
        append(
            when (coolKind) {
                CoolKind.QUOTA -> "额度或权益不足"
                CoolKind.SOFT -> "被上游限流"
                CoolKind.ERROR -> "连续上游错误"
                // 历史数据没有 coolKind 字段，不编造具体原因
                null -> "上次请求失败"
            },
        )
        append("）")
        val remain = untilMillis - nowMillis
        if (remain > 0) append(" · 剩 ").append(formatRemaining(remain))
    }
}

/** 把剩余毫秒折成「N 分钟」/「N 小时 M 分钟」/「N 天」；不足 1 分钟按「不到 1 分钟」。 */
fun formatRemaining(millis: Long): String {
    if (millis <= 0) return "已到期"
    val minutes = millis / 60_000
    if (minutes < 1) return "不到 1 分钟"
    if (minutes < 60) return "${minutes} 分钟"
    val hours = minutes / 60
    if (hours < 24) {
        val rest = minutes % 60
        return if (rest == 0L) "${hours} 小时" else "${hours} 小时 ${rest} 分钟"
    }
    val days = hours / 24
    val restHours = hours % 24
    return if (restHours == 0L) "${days} 天" else "${days} 天 ${restHours} 小时"
}
