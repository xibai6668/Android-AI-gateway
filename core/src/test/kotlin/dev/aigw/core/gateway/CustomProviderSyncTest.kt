package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.custom.CustomProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自定义供应商配置 → 账号池的同步。
 *
 * 回归背景：新建供应商时填的 API Key 只落在供应商配置里，详情页读的是账号池，
 * 不同步就会「保存完进详情页看不到刚填的 key」。
 */
class CustomProviderSyncTest {

    private fun engine() = GatewayEngine(InMemoryKeyValueStore())

    private fun config(keys: List<String>) = CustomProviderConfig(
        key = "site",
        name = "站点",
        baseUrl = "https://api.example.com/v1",
        apiKeys = keys,
        models = listOf("gpt-4o"),
    )

    @Test
    fun `保存配置时把 key 同步成账号`() {
        val engine = engine()
        engine.syncCustomAccounts(config(listOf("sk-1", "sk-2")))

        val accounts = engine.accounts("custom:site")
        assertEquals(2, accounts.size)
        assertEquals(
            setOf(CustomProvider.uidOf("sk-1"), CustomProvider.uidOf("sk-2")),
            accounts.map { it.uid }.toSet(),
        )
        assertTrue(accounts.all { it.providerId == "custom:site" })
        assertTrue(accounts.all { it.nickname == "站点" })
    }

    @Test
    fun `移除的 key 会从账号池删掉`() {
        val engine = engine()
        engine.syncCustomAccounts(config(listOf("sk-1", "sk-2")))
        engine.syncCustomAccounts(config(listOf("sk-2")))

        val accounts = engine.accounts("custom:site")
        assertEquals(listOf(CustomProvider.uidOf("sk-2")), accounts.map { it.uid })
    }

    @Test
    fun `重复同步不会产生重复账号`() {
        val engine = engine()
        repeat(3) { engine.syncCustomAccounts(config(listOf("sk-1"))) }
        assertEquals(1, engine.accounts("custom:site").size)
    }

    @Test
    fun `账号 secret 里存的是完整 key`() {
        val engine = engine()
        engine.syncCustomAccounts(config(listOf("sk-abc")))
        val account = engine.account("custom:site", CustomProvider.uidOf("sk-abc"))
        assertTrue(account != null)
        assertTrue(account.secret.contains("sk-abc"))
    }
}
