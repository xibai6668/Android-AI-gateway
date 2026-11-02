package dev.aigw.core.failover

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 候选权重排序。
 *
 * 铁律：**权重只在同一 priority 内生效**。若跨优先级生效，低优先级的高权重候选会插到
 * 高优先级前面，「priority 先试完这一组」的语义就没了。这条被写成用例，防止以后「优化」时改错。
 */
class WeightedOrderTest {

    private fun candidate(provider: String, priority: Int, weight: Int = 1) =
        FailoverCandidate(providerId = provider, upstreamModel = "m", priority = priority, weight = weight)

    @Test
    fun `优先级永远压过权重`() {
        val list = listOf(
            candidate("low", priority = 10, weight = 100),
            candidate("high", priority = 100, weight = 1),
        )
        assertEquals(listOf("high", "low"), weightedOrder(list).map { it.providerId })
    }

    @Test
    fun `同优先级内按权重抽：权重 0 的排到最后`() {
        val list = listOf(
            candidate("zero", priority = 50, weight = 0),
            candidate("heavy", priority = 50, weight = 9),
            candidate("light", priority = 50, weight = 1),
        )
        val ordered = weightedOrder(list, Random(42)).map { it.providerId }
        assertEquals("zero", ordered.last(), "权重 0 不该被优先选中：$ordered")
        assertTrue("heavy" in ordered.take(2))
    }

    @Test
    fun `同权重时顺序随机而不是永远第一个在前`() {
        val list = listOf(
            candidate("a", priority = 50),
            candidate("b", priority = 50),
            candidate("c", priority = 50),
        )
        val firsts = (1..200).map { weightedOrder(list, Random(it)).first().providerId }.toSet()
        assertEquals(setOf("a", "b", "c"), firsts, "同权重应能抽到每一个：$firsts")
    }

    @Test
    fun `不丢候选`() {
        val list = (1..5).map { candidate("p$it", priority = it % 2 * 10 + 10) }
        assertEquals(list.map { it.providerId }.toSet(), weightedOrder(list).map { it.providerId }.toSet())
    }
}
