package dev.aigw.core.failover

import kotlin.random.Random

/**
 * 候选排序：**先按 priority 降序，再在同 priority 内按 weight 加权随机**。
 *
 * 为什么不是「全局按 weight 加权随机」：priority 的语义是「这组必须先试完才轮到下一组」，
 * 若让权重跨优先级生效，低优先级的高权重候选会插到高优先级前面，priority 就形同虚设。
 * new-api 的「同优先级下的随机权重」也是这个口径。
 *
 * 权重 <= 0 视为「不参与随机加权」，按原相对顺序排在同优先级末尾——
 * 这样既尊重配置（权重 0 表示不想被优先选中），又不会把候选静默丢掉。
 *
 * [random] 可注入以便测试固定序列。
 */
fun weightedOrder(
    candidates: List<FailoverCandidate>,
    random: Random = Random.Default,
): List<FailoverCandidate> {
    val groups = candidates.groupBy { it.priority }.toSortedMap(compareByDescending { it })
    val out = ArrayList<FailoverCandidate>(candidates.size)
    for ((_, group) in groups) {
        val enabled = group.filter { it.weight > 0 }
        val disabled = group.filter { it.weight <= 0 }
        out.addAll(weightedShuffle(enabled, random))
        out.addAll(disabled)
    }
    return out
}

/**
 * 加权洗牌（不放回抽样）：每轮按剩余项的权重抽一个。
 *
 * 与「按权重比例展开再洗牌」的差别：同权重项的顺序是随机的（不会总把第一个排前面），
 * 且 n 个候选只做 n 次抽样，候选多时不会展开成大数组。
 */
internal fun weightedShuffle(
    items: List<FailoverCandidate>,
    random: Random,
): List<FailoverCandidate> {
    val remaining = items.toMutableList()
    val out = ArrayList<FailoverCandidate>(items.size)
    while (remaining.isNotEmpty()) {
        val total = remaining.sumOf { it.weight.toLong() }
        var target = if (total > 0) random.nextLong(total) else 0L
        var index = 0
        for ((i, item) in remaining.withIndex()) {
            target -= item.weight
            if (target < 0) {
                index = i
                break
            }
        }
        out.add(remaining.removeAt(index))
    }
    return out
}
