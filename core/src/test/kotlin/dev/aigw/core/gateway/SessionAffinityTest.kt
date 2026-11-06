package dev.aigw.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 会话亲和表单测。
 *
 * 用可变时钟代替真实时间：TTL 与「最久未用」淘汰都依赖时间，不能靠 sleep 制造（慢且不稳）。
 */
class SessionAffinityTest {

    /** 测试用时钟：手动推进，保证时间相关断言完全确定。 */
    private class Clock(var now: Long = 1_000_000L)

    private fun affinity(clock: Clock, capacity: Int = 2048, ttlMillis: Long = 30 * 60 * 1000L) =
        SessionAffinity(capacity = capacity, ttlMillis = ttlMillis, nowMillis = { clock.now })

    @Test
    fun `bind 后可查回归属信息`() {
        val clock = Clock()
        val table = affinity(clock)

        table.bind("s1", "trae", "u-1")

        val binding = assertNotNull(table.lookup("s1"))
        assertEquals("trae", binding.providerId)
        assertEquals("u-1", binding.uid)
        assertEquals(clock.now, binding.boundAtMillis)
        assertEquals(1, table.size)

        // 未登记的会话不该凭空给出归属
        assertNull(table.lookup("s2"))
    }

    @Test
    fun `超过 TTL 的绑定查不到并被顺手清理`() {
        val clock = Clock()
        val table = affinity(clock, ttlMillis = 1000L)
        table.bind("s1", "trae", "u-1")

        clock.now += 999
        assertNotNull(table.lookup("s1"), "TTL 内应仍可命中")

        clock.now += 1 // 正好到期
        assertNull(table.lookup("s1"))
        assertEquals(0, table.size, "过期条目应被顺手摘掉，而不是留在表里占容量")
    }

    @Test
    fun `invalidateAccount 摘掉所有指向该账号的绑定`() {
        val clock = Clock()
        val table = affinity(clock)
        table.bind("s1", "trae", "u-1")
        table.bind("s2", "trae", "u-1")
        table.bind("s3", "workbuddy", "u-1") // 同 uid 不同供应商：不受影响
        table.bind("s4", "trae", "u-2") // 同供应商不同账号：不受影响

        table.invalidateAccount("trae", "u-1")

        assertNull(table.lookup("s1"))
        assertNull(table.lookup("s2"))
        assertNotNull(table.lookup("s3"))
        assertNotNull(table.lookup("s4"))
        assertEquals(2, table.size)
    }

    @Test
    fun `容量超限时淘汰最久未用的绑定且只清理到容量的九成`() {
        val clock = Clock()
        // capacity=4 → 淘汰目标 = 4*9/10 = 3
        val table = affinity(clock, capacity = 4)

        repeat(5) { index ->
            clock.now += 1 // 让每次 bind 的时间严格递增，才能判定「最久未用」
            table.bind("s${index + 1}", "trae", "u-1")
        }

        // 第 5 次插入时超限，删掉最旧的 2 条（s1/s2），留下最新的 3 条
        assertNull(table.lookup("s1"))
        assertNull(table.lookup("s2"))
        assertNotNull(table.lookup("s3"))
        assertNotNull(table.lookup("s4"))
        assertNotNull(table.lookup("s5"))
        assertEquals(3, table.size)

        // 再次插入仍在上限内，不该继续触发淘汰
        clock.now += 1
        table.bind("s6", "trae", "u-1")
        assertEquals(4, table.size)
        assertNotNull(table.lookup("s3"))
    }

    @Test
    fun `空 sessionKey 既不记录也不查询`() {
        val clock = Clock()
        val table = affinity(clock)

        table.bind("", "trae", "u-1")

        assertEquals(0, table.size)
        assertNull(table.lookup(""))
    }

    @Test
    fun `重复 bind 同一会话会刷新归属与时间`() {
        val clock = Clock()
        val table = affinity(clock)

        table.bind("s1", "trae", "u-1")
        clock.now += 5000
        table.bind("s1", "workbuddy", "u-2")

        val binding = assertNotNull(table.lookup("s1"))
        assertEquals("workbuddy", binding.providerId)
        assertEquals("u-2", binding.uid)
        assertEquals(clock.now, binding.boundAtMillis, "重新绑定应重置 TTL 计时")
        assertEquals(1, table.size)
    }
}
