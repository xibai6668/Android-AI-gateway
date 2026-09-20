package dev.aigw.core.pool

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 冷却的文案与手动解除。
 *
 * 背景：界面曾只显示「冷却中」三个字，用户既看不到原因（额度不足 12 小时 vs 限流 1 分钟差别很大），
 * 也没有立即重试的出口。
 */
class CoolingTest {

    private val now = 1_800_000_000_000L

    private fun pool(nowMillis: Long = now) = AccountPool(InMemoryKeyValueStore()) { nowMillis }

    private fun account(uid: String = "u1") = ProviderAccount(
        providerId = TRAE,
        uid = uid,
        nickname = "昵称",
        secret = """{"uid":"$uid"}""",
    )

    private fun status(
        cooling: Boolean = true,
        untilMillis: Long = now + 600_000,
        reason: String = "",
        kind: CoolKind? = null,
        disabled: Boolean = false,
        enabled: Boolean = true,
    ) = AccountStatus(
        providerId = TRAE,
        uid = "u1",
        nickname = "昵称",
        credits = 0,
        creditsKnown = false,
        detail = "",
        cooling = cooling,
        untilMillis = untilMillis,
        reason = reason,
        disabled = disabled,
        enabled = enabled,
        errorCount = 0,
        coolKind = kind,
    )

    // ------------------------------------------------------------------ 文案

    @Test
    fun `冷却文案包含原因与剩余时间`() {
        val text = status(kind = CoolKind.QUOTA, untilMillis = now + 12 * 3600_000L).coolingText(now)
        assertTrue(text.contains("冷却中"), text)
        assertTrue(text.contains("额度或权益不足"), "必须说明原因：$text")
        assertTrue(text.contains("12 小时"), "必须说明还要等多久：$text")
    }

    @Test
    fun `三种冷却类型文案不同`() {
        val soft = status(kind = CoolKind.SOFT, untilMillis = now + 60_000L).coolingText(now)
        val error = status(kind = CoolKind.ERROR, untilMillis = now + 600_000L).coolingText(now)
        val quota = status(kind = CoolKind.QUOTA, untilMillis = now + 3600_000L).coolingText(now)

        assertTrue(soft.contains("被上游限流"), soft)
        assertTrue(error.contains("连续上游错误"), error)
        assertTrue(quota.contains("额度或权益不足"), quota)
        assertEquals(soft != error && error != quota, true)
    }

    @Test
    fun `历史数据没有 coolKind 时不编造原因`() {
        val text = status(kind = null).coolingText(now)
        assertTrue(text.contains("上次请求失败"), text)
        assertFalse(text.contains("额度"), "不能凭空说是额度问题：$text")
    }

    @Test
    fun `非冷却状态给出对应文案`() {
        assertEquals("可用", status(cooling = false).coolingText(now))
        assertEquals("已停用", status(enabled = false).coolingText(now))
        assertEquals("凭证失效，需重新登录", status(disabled = true).coolingText(now))
    }

    @Test
    fun `凭证失效优先于冷却展示`() {
        // 硬禁用是更严重、且需要用户动作的状态，不能被冷却文案掩盖
        val text = status(disabled = true, kind = CoolKind.QUOTA).coolingText(now)
        assertEquals("凭证失效，需重新登录", text)
    }

    // ------------------------------------------------------------------ 时长换算

    @Test
    fun `剩余时长按时长量级切换单位`() {
        assertEquals("已到期", formatRemaining(0))
        assertEquals("已到期", formatRemaining(-1))
        assertEquals("不到 1 分钟", formatRemaining(59_000))
        assertEquals("1 分钟", formatRemaining(60_000))
        assertEquals("10 分钟", formatRemaining(600_000))
        assertEquals("1 小时", formatRemaining(3600_000))
        assertEquals("1 小时 30 分钟", formatRemaining(5400_000))
        assertEquals("12 小时", formatRemaining(12 * 3600_000L))
        assertEquals("2 天", formatRemaining(2 * 24 * 3600_000L))
        assertEquals("2 天 3 小时", formatRemaining((2 * 24 + 3) * 3600_000L))
    }

    // ------------------------------------------------------------------ 手动解除

    @Test
    fun `手动解除冷却后账号恢复可用`() {
        val pool = pool()
        pool.upsert(account())
        pool.cooldown(TRAE, "u1", CoolKind.QUOTA, 12 * 3600_000L, "额度不足")

        val before = pool.status(TRAE, "u1")!!
        assertTrue(before.cooling)
        assertEquals(CoolKind.QUOTA, before.coolKind)

        assertTrue(pool.clearCooldown(TRAE, "u1"), "冷却中应返回 true")
        val after = pool.status(TRAE, "u1")!!
        assertFalse(after.cooling)
        assertTrue(after.usable, "解除后应立即可用")
        assertNull(after.coolKind)
        assertEquals("", after.reason)
    }

    @Test
    fun `未冷却或不存在时解除返回 false`() {
        val pool = pool()
        pool.upsert(account())
        assertFalse(pool.clearCooldown(TRAE, "u1"), "本来就没冷却")
        assertFalse(pool.clearCooldown(TRAE, "nope"), "账号不存在")
    }

    @Test
    fun `解除冷却不会绕过凭证失效`() {
        val pool = pool()
        pool.upsert(account())
        pool.disable(TRAE, "u1", "凭证失效")
        pool.clearCooldown(TRAE, "u1")
        val status = pool.status(TRAE, "u1")!!
        assertTrue(status.disabled, "凭证失效必须重新登录，不能靠清冷却恢复")
        assertFalse(status.usable)
    }

    @Test
    fun `冷却类型会持久化并在重启后读回`() {
        val store = InMemoryKeyValueStore()
        val first = AccountPool(store) { now }
        first.upsert(account())
        first.cooldown(TRAE, "u1", CoolKind.SOFT, 60_000L, "限流")

        val second = AccountPool(store) { now }
        val status = second.status(TRAE, "u1")!!
        assertTrue(status.cooling)
        assertEquals(CoolKind.SOFT, status.coolKind, "重启后仍应知道冷却原因")
    }

    @Test
    fun `旧版状态文件没有 coolKind 字段也能读`() {
        val store = InMemoryKeyValueStore()
        val first = AccountPool(store) { now }
        first.upsert(account())
        first.cooldown(TRAE, "u1", CoolKind.QUOTA, 12 * 3600_000L, "额度不足")

        // 模拟历史数据：删掉 coolKind 字段，其余不变
        val key = AccountPool.stateKey(TRAE)
        val raw = store.read(key)!!
        store.write(key, raw.replace(Regex(",\"coolKind\":\"[A-Z]+\""), ""))

        val second = AccountPool(store) { now }
        val status = second.status(TRAE, "u1")!!
        assertTrue(status.cooling, "冷却状态本身仍要保留")
        assertNull(status.coolKind, "缺字段按未知处理，而不是崩掉或猜一个")
        assertTrue(status.coolingText(now).contains("上次请求失败"))
    }

    @Test
    fun `额度恢复会顺带清掉冷却类型`() {
        val pool = pool()
        pool.upsert(account())
        pool.cooldown(TRAE, "u1", CoolKind.QUOTA, 12 * 3600_000L, "额度不足")
        pool.updateCredits(TRAE, "u1", 500)

        val status = pool.status(TRAE, "u1")!!
        assertFalse(status.cooling, "有额度后应自动恢复（额度恢复即自动启用）")
        assertNull(status.coolKind)
    }

    private companion object {
        const val TRAE = "trae"
    }
}
