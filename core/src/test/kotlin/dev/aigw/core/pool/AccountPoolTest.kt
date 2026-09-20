package dev.aigw.core.pool

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountPoolTest {

    private var now = 1_000_000L

    private fun account(uid: String, providerId: String = TRAE) = ProviderAccount(
        providerId = providerId,
        uid = uid,
        nickname = "昵称$uid",
        secret = """{"uid":"$uid","accessToken":"at-$uid"}""",
    )

    private fun newPool(store: InMemoryKeyValueStore = InMemoryKeyValueStore()) =
        AccountPool(store, nowMillis = { now })

    @Test
    fun `选号取可用账号中余额最高者`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))
        pool.updateCredits(TRAE, "a", 10)
        pool.updateCredits(TRAE, "b", 900)
        assertEquals("b", pool.pick(TRAE)?.uid)
    }

    @Test
    fun `可以排除已试过的账号`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))
        pool.updateCredits(TRAE, "a", 10)
        pool.updateCredits(TRAE, "b", 20)
        assertEquals("b", pool.pick(TRAE, setOf("a"))?.uid)
        assertEquals("a", pool.pick(TRAE, setOf("b"))?.uid)
        assertNull(pool.pick(TRAE, setOf("a", "b")))
    }

    @Test
    fun `不同 provider 的账号池互相隔离`() {
        val pool = newPool()
        pool.upsert(account("a", TRAE))
        pool.upsert(account("b", LOOMY))
        pool.cooldown(TRAE, "a", CoolKind.QUOTA, 60_000, "额度不足")

        assertNull(pool.pick(TRAE), "Trae 的账号在冷却，不该被选中")
        assertEquals("b", pool.pick(LOOMY)?.uid, "Loomy 的账号不应受 Trae 冷却影响")
        assertEquals(1, pool.summary(TRAE).total)
        assertEquals(1, pool.summary(LOOMY).total)
        assertEquals(2, pool.summary().total)
    }

    @Test
    fun `冷却期内不被选中，到期后恢复`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.cooldown(TRAE, "a", CoolKind.QUOTA, 60_000, "权益不足")
        assertNull(pool.pick(TRAE))
        assertTrue(pool.status(TRAE, "a")!!.cooling)
        now += 60_001
        assertEquals("a", pool.pick(TRAE)?.uid)
        assertFalse(pool.status(TRAE, "a")!!.cooling)
    }

    @Test
    fun `硬禁用与用户软开关语义不同`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))

        pool.setEnabled(TRAE, "a", false)
        assertNull(pool.pick(TRAE, setOf("b")))
        assertEquals(1, pool.summary(TRAE).disabledByUser)

        pool.setEnabled(TRAE, "a", true)
        pool.disable(TRAE, "b", "凭证失效")
        assertEquals("a", pool.pick(TRAE)?.uid)
        assertTrue(pool.status(TRAE, "b")!!.disabled)
        assertEquals(1, pool.summary(TRAE).disabled)
    }

    @Test
    fun `连续错误达到阈值才冷却`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.noteError(TRAE, "a", threshold = 3, durationMillis = 10_000)
        pool.noteError(TRAE, "a", threshold = 3, durationMillis = 10_000)
        assertFalse(pool.status(TRAE, "a")!!.cooling)
        pool.noteError(TRAE, "a", threshold = 3, durationMillis = 10_000)
        assertTrue(pool.status(TRAE, "a")!!.cooling)
        assertEquals(0, pool.status(TRAE, "a")!!.errorCount, "触发后错误计数清零")
    }

    @Test
    fun `成功请求清零错误计数`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.noteError(TRAE, "a", threshold = 3, durationMillis = 10_000)
        pool.noteSuccess(TRAE, "a")
        assertEquals(0, pool.status(TRAE, "a")!!.errorCount)
    }

    @Test
    fun `额度恢复后解除冷却，但不禁用恢复`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))
        pool.cooldown(TRAE, "a", CoolKind.QUOTA, 60_000, "额度耗尽")
        pool.disable(TRAE, "b", "凭证失效")

        pool.updateCredits(TRAE, "a", 200)
        assertEquals("a", pool.pick(TRAE, setOf("b"))?.uid)

        pool.updateCredits(TRAE, "b", 200)
        assertTrue(pool.status(TRAE, "b")!!.disabled, "硬禁用不应被额度恢复")
    }

    @Test
    fun `零额度不解除冷却`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.cooldown(TRAE, "a", CoolKind.QUOTA, 60_000, "额度耗尽")
        pool.updateCredits(TRAE, "a", 0)
        assertTrue(pool.status(TRAE, "a")!!.cooling)
    }

    @Test
    fun `未知额度不计入总余额`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))
        pool.updateCredits(TRAE, "a", 300, known = true)
        pool.updateCredits(TRAE, "b", 0, known = false, detail = "上游未返回")

        assertEquals(300L, pool.summary(TRAE).totalCredits, "未知额度不能按 0 混入总和")
        assertEquals(1, pool.summary(TRAE).creditsKnown)
        assertFalse(pool.status(TRAE, "b")!!.creditsKnown)
    }

    @Test
    fun `状态与凭证能落盘并恢复`() {
        val store = InMemoryKeyValueStore()
        val first = newPool(store)
        first.upsert(account("a"))
        first.updateCredits(TRAE, "a", 777)
        first.disable(TRAE, "a", "凭证失效")

        val second = newPool(store)
        val status = second.status(TRAE, "a")
        assertNotNull(status)
        assertEquals(777L, status.credits)
        assertTrue(status.disabled)
        assertEquals("昵称a", status.nickname)
        assertEquals(account("a").secret, second.account(TRAE, "a")!!.secret)
    }

    @Test
    fun `删除账号会清掉凭证键`() {
        val store = InMemoryKeyValueStore()
        val pool = newPool(store)
        pool.upsert(account("a"))
        assertTrue(pool.remove(TRAE, "a"))
        assertFalse(pool.remove(TRAE, "a"))
        assertTrue(store.keys(AccountPool.ACCOUNT_PREFIX).isEmpty())
        assertNull(pool.account(TRAE, "a"))
    }

    @Test
    fun `刷新后写回不会丢掉状态`() {
        val store = InMemoryKeyValueStore()
        val pool = newPool(store)
        pool.upsert(account("a"))
        pool.updateCredits(TRAE, "a", 500)
        pool.cooldown(TRAE, "a", CoolKind.SOFT, 1_000, "限流")

        val updated = account("a").copy(secret = """{"accessToken":"new-at"}""")
        pool.saveAccount(updated)
        assertEquals("""{"accessToken":"new-at"}""", pool.account(TRAE, "a")!!.secret)
        assertEquals(500L, pool.status(TRAE, "a")!!.credits)
        assertTrue(pool.status(TRAE, "a")!!.cooling, "写回凭证不应清掉冷却")
    }

    @Test
    fun `未知账号上的操作是空操作`() {
        val pool = newPool()
        pool.cooldown(TRAE, "nope", CoolKind.SOFT, 1_000, "x")
        pool.disable(TRAE, "nope", "x")
        pool.setEnabled(TRAE, "nope", false)
        pool.noteError(TRAE, "nope", 1, 1)
        pool.updateCredits(TRAE, "nope", 1)
        assertNull(pool.status(TRAE, "nope"))
        assertEquals(0, pool.size())
    }

    private companion object {
        const val TRAE = "trae"
        const val LOOMY = "loomy"
    }
}
