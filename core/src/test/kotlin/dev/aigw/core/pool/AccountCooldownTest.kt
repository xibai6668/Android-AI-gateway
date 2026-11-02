package dev.aigw.core.pool

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 账号本地冷却。
 *
 * 解决的问题：上游刚报错，下一个请求又选中同一个号，连续撞墙。冷却只影响选号，
 * 不改变 enabled/disabled（界面上仍显示可用），到期自动恢复。
 */
class AccountCooldownTest {

    private var now = 1_000_000L

    private fun pool() = AccountPool(InMemoryKeyValueStore(), nowMillis = { now })

    private fun account(uid: String) = ProviderAccount(
        providerId = "codebuddy",
        uid = uid,
        nickname = uid,
        secret = """{"accessToken":"tok"}""",
    )

    @Test
    fun `冷却中的账号不会被选中`() {
        val pool = pool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))
        pool.coolDown("codebuddy", "a", 15_000)
        assertEquals("b", pool.pick("codebuddy")?.uid)
    }

    @Test
    fun `全部冷却时选不到号（调用方按无可用账号处理）`() {
        val pool = pool()
        pool.upsert(account("a"))
        pool.coolDown("codebuddy", "a", 15_000)
        assertNull(pool.pick("codebuddy"))
    }

    @Test
    fun `冷却到期自动恢复`() {
        val pool = pool()
        pool.upsert(account("a"))
        pool.coolDown("codebuddy", "a", 15_000)
        now += 15_001
        assertEquals("a", pool.pick("codebuddy")?.uid)
        assertFalse(pool.isCooling("codebuddy", "a"))
    }

    @Test
    fun `冷却不改变可用状态`() {
        val pool = pool()
        pool.upsert(account("a"))
        pool.coolDown("codebuddy", "a", 15_000)
        val status = assertNotNull(pool.status("codebuddy", "a"))
        assertTrue(status.enabled, "冷却不该把账号标成不可用")
        assertFalse(status.disabled)
    }

    @Test
    fun `成功一次就解除冷却`() {
        val pool = pool()
        pool.upsert(account("a"))
        pool.coolDown("codebuddy", "a", 15_000)
        pool.clearCooldown("codebuddy", "a")
        assertFalse(pool.isCooling("codebuddy", "a"))
        assertEquals("a", pool.pick("codebuddy")?.uid)
    }

    @Test
    fun `重新登录换新凭证会清掉旧冷却`() {
        val pool = pool()
        pool.upsert(account("a"))
        pool.coolDown("codebuddy", "a", 15_000)
        // 同 uid 重新 upsert（凭证轮换）
        pool.upsert(account("a"))
        assertFalse(pool.isCooling("codebuddy", "a"), "换了新凭证就该立刻可用")
    }

    @Test
    fun `删除账号同时清掉冷却`() {
        val pool = pool()
        pool.upsert(account("a"))
        pool.coolDown("codebuddy", "a", 15_000)
        pool.remove("codebuddy", "a")
        assertFalse(pool.isCooling("codebuddy", "a"))
    }

    @Test
    fun `冷却时长 0 等于不冷却`() {
        val pool = pool()
        pool.upsert(account("a"))
        pool.coolDown("codebuddy", "a", 0)
        assertEquals("a", pool.pick("codebuddy")?.uid)
    }

    @Test
    fun `快照只列出仍在冷却的账号`() {
        val pool = pool()
        pool.coolDown("codebuddy", "a", 15_000)
        pool.coolDown("codebuddy", "b", 1)
        now += 2
        val snapshot = pool.cooldownSnapshot()
        assertTrue("codebuddy/a" in snapshot, snapshot.toString())
        assertFalse("codebuddy/b" in snapshot, snapshot.toString())
    }
}
