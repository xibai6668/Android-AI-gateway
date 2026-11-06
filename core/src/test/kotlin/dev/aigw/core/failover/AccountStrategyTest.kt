package dev.aigw.core.failover

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 账号选择策略单测。
 *
 * [orderAccounts] 是纯函数，这里逐个锁死每种策略的顺序口径：
 * 谁先谁后直接决定「先用谁的额度」，错一个比较器就会把余额耗在错误账号上。
 */
class AccountStrategyTest {

    private fun hour(now: Long, hours: Long): Long = now + hours * 3_600_000L

    @Test
    fun `SMART 先按 priority 降序再按余额降序且忽略未知余额`() {
        val candidates = listOf(
            AccountCandidate("a", priority = 10, credits = 100, creditsKnown = true),
            AccountCandidate("b", priority = 10, credits = 500, creditsKnown = true),
            AccountCandidate("c", priority = 5, credits = 9999, creditsKnown = true),
            AccountCandidate("d", priority = 10, credits = 800, creditsKnown = false),
        )

        // priority=10 组内：b(500) > a(100) > d(未知按 0)；然后才轮到 priority=5 的 c
        assertEquals(
            listOf("b", "a", "d", "c"),
            orderAccounts(candidates, AccountStrategy.SMART),
        )
    }

    @Test
    fun `ORDER 按 priority 降序同级按 uid 升序且与入参顺序无关`() {
        val candidates = listOf(
            AccountCandidate("c", priority = 20),
            AccountCandidate("b", priority = 10),
            AccountCandidate("a", priority = 10),
        )

        assertEquals(listOf("c", "a", "b"), orderAccounts(candidates, AccountStrategy.ORDER))
        // 入参顺序反过来必须得到同一结果，否则「稳定」无从谈起
        assertEquals(listOf("c", "a", "b"), orderAccounts(candidates.reversed(), AccountStrategy.ORDER))
    }

    @Test
    fun `ROTATE 按 uid 环形推进并支持游标回绕与负值`() {
        val candidates = listOf(
            AccountCandidate("d"),
            AccountCandidate("b"),
            AccountCandidate("a"),
            AccountCandidate("c"),
        )

        assertEquals(listOf("a", "b", "c", "d"), orderAccounts(candidates, AccountStrategy.ROTATE, rotationCursor = 0))
        assertEquals(listOf("b", "c", "d", "a"), orderAccounts(candidates, AccountStrategy.ROTATE, rotationCursor = 1))
        // 6 % 4 = 2
        assertEquals(listOf("c", "d", "a", "b"), orderAccounts(candidates, AccountStrategy.ROTATE, rotationCursor = 6))
        // floorMod(-1, 4) = 3
        assertEquals(listOf("d", "a", "b", "c"), orderAccounts(candidates, AccountStrategy.ROTATE, rotationCursor = -1))
    }

    @Test
    fun `USAGE 按余额降序再按 priority 降序兜底`() {
        val candidates = listOf(
            AccountCandidate("a", priority = 100, credits = 10, creditsKnown = true),
            AccountCandidate("b", priority = 1, credits = 50, creditsKnown = true),
            AccountCandidate("c", priority = 9, credits = 50, creditsKnown = true),
            AccountCandidate("d", priority = 999, credits = 5000, creditsKnown = false),
        )

        // b/c 余额同为 50 → 按 priority 降序，c(9) 在 b(1) 之前；
        // 之后是 a(10)，最后是余额未知按 0 计的 d
        assertEquals(listOf("c", "b", "a", "d"), orderAccounts(candidates, AccountStrategy.USAGE))
    }

    @Test
    fun `PACE 让快重置的额度先用且额度未知或已重置的排最后`() {
        val now = 1_000_000_000L
        val candidates = listOf(
            // 600 额度 / 10 小时 = 60 每小时
            AccountCandidate("a", credits = 600, creditsKnown = true, resetAtMillis = hour(now, 10)),
            // 100 额度 / 1 小时 = 100 每小时：额度少但快作废，应先花
            AccountCandidate("b", credits = 100, creditsKnown = true, resetAtMillis = hour(now, 1)),
            // 1000 额度 / 100 小时 = 10 每小时
            AccountCandidate("c", credits = 1000, creditsKnown = true, resetAtMillis = hour(now, 100)),
            // 距重置不足 1 小时按 1 小时算：5 / 1 = 5 每小时
            AccountCandidate("f", credits = 5, creditsKnown = true, resetAtMillis = now + 20 * 60_000L),
            // 额度未知：无法判断紧迫度，排最后
            AccountCandidate("d", credits = 5000, creditsKnown = false, resetAtMillis = hour(now, 1)),
            // 重置时刻已过：本窗口的额度已失效，同样排最后
            AccountCandidate("e", credits = 3000, creditsKnown = true, resetAtMillis = now - 3_600_000L),
        )

        assertEquals(
            listOf("b", "a", "c", "f", "d", "e"),
            orderAccounts(candidates, AccountStrategy.PACE, nowMillis = now),
        )
    }

    @Test
    fun `空候选列表在任何策略下都返回空`() {
        for (strategy in AccountStrategy.values()) {
            assertEquals(emptyList<String>(), orderAccounts(emptyList(), strategy))
        }
    }
}
