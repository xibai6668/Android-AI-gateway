package dev.aigw.core.failover

/** 账号选择策略。 */
enum class AccountStrategy {
    /** 默认：priority 优先，同级按余额。 */
    SMART,

    /** 严格按 priority，再按 uid 字典序，稳定不随机。 */
    ORDER,

    /** 轮询：请求级游标依次推进，均摊负载。 */
    ROTATE,

    /** 余额最多的先用。 */
    USAGE,

    /** 到期前用完：剩余额度 ÷ 距重置小时数，最大者优先（少让额度作废）。 */
    PACE,
}

/** 参与排序的账号快照（由调用方从账号池取）。 */
data class AccountCandidate(
    val uid: String,
    val priority: Int = 0,
    val credits: Long = 0L,
    val creditsKnown: Boolean = false,
    /** 额度重置时刻（毫秒，0 未知）。 */
    val resetAtMillis: Long = 0L,
)

/**
 * 按策略排序候选账号，返回 uid 有序列表（首位最优先）。
 *
 * 纯函数：不改入参、不做 IO，便于单测。任何策略下 uid 都必须稳定，
 * 否则同一秒内两次调用会得到不同顺序，缓存亲和与排障都失去依据。
 *
 * [rotationCursor] 由调用方维护（只有 ROTATE 用，内部取模）。
 */
fun orderAccounts(
    candidates: List<AccountCandidate>,
    strategy: AccountStrategy,
    rotationCursor: Long = 0L,
    nowMillis: Long = System.currentTimeMillis(),
): List<String> {
    if (candidates.isEmpty()) return emptyList()
    val ordered = when (strategy) {
        // priority 是用户手动排的意愿，同级才轮到余额；余额未知按 0 参与比较（不臆造额度）。
        AccountStrategy.SMART -> candidates.sortedWith(
            compareByDescending<AccountCandidate> { it.priority }
                .thenByDescending { effectiveCredits(it) }
                .thenBy { it.uid },
        )

        AccountStrategy.ORDER -> candidates.sortedWith(
            compareByDescending<AccountCandidate> { it.priority }.thenBy { it.uid },
        )

        AccountStrategy.ROTATE -> {
            val ring = candidates.sortedBy { it.uid }
            val start = Math.floorMod(rotationCursor, ring.size.toLong()).toInt()
            ring.drop(start) + ring.take(start)
        }

        AccountStrategy.USAGE -> candidates.sortedWith(
            compareByDescending<AccountCandidate> { effectiveCredits(it) }
                .thenByDescending { it.priority }
                .thenBy { it.uid },
        )

        AccountStrategy.PACE -> candidates.sortedWith(
            compareByDescending<AccountCandidate> { paceTier(it, nowMillis) }
                .thenByDescending { paceScore(it, nowMillis) }
                .thenByDescending { effectiveCredits(it) }
                .thenBy { it.uid },
        )
    }
    return ordered.map { it.uid }
}

private fun effectiveCredits(candidate: AccountCandidate): Long =
    if (candidate.creditsKnown) candidate.credits else 0L

/**
 * PACE 的分档：2 = 可参与紧迫度排序；1 = 额度未知（无从判断）；0 = 本窗口已失效。
 *
 * 已失效必须比未知更靠后：未知至少还有可能有额度，已过期则是确定用不了。
 */
private fun paceTier(candidate: AccountCandidate, nowMillis: Long): Int = when {
    !candidate.creditsKnown -> 1
    candidate.resetAtMillis <= nowMillis -> 0
    else -> 2
}

/**
 * 「到期前用完」的紧迫度：剩余额度 ÷ 距重置的小时数。
 *
 * 距重置不足 1 小时按 1 小时算，避免分母趋零把某个账号的分数抬到无穷大——
 * 那种账号只剩一点点额度时就该被最后使用，而不是霸占全部流量。
 * 只有 [paceTier] 为 2 的候选才有真实分数，其余档一律 0（排序交给档位与余额）。
 */
private fun paceScore(candidate: AccountCandidate, nowMillis: Long): Double {
    if (paceTier(candidate, nowMillis) != 2) return 0.0
    val hoursLeft = (candidate.resetAtMillis - nowMillis).toDouble() / 3_600_000.0
    return candidate.credits.toDouble() / hoursLeft.coerceAtLeast(1.0)
}
