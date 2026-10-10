package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 对话后额度补刷的节流回归。
 *
 * 背景：原实现用 `replace(key, lastAutoCreditRefresh[key] ?: 0L, now)` 做占坑，
 * 期望值就是当前值，CAS 恒成功，`AUTO_CREDIT_REFRESH_MIN_INTERVAL_MS`（10s）从未生效——
 * 连发对话会把上游积分接口打爆。
 */
class CreditRefreshThrottleTest {

    /** 记录 creditInfo 被调用的次数，并允许测试等待第一次刷新完成。 */
    private class CountingProvider : Provider {
        override val id = "counting"
        override val displayName = "计数供应商"
        override val authKind = AuthKind.NONE
        val creditCalls = AtomicInteger(0)
        val firstCall = CountDownLatch(1)

        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = throw UnsupportedOperationException()

        override fun creditInfo(account: ProviderAccount): CreditInfo {
            creditCalls.incrementAndGet()
            firstCall.countDown()
            return CreditInfo(123, true, "测试")
        }
    }

    @Test
    fun `间隔内第二次调用不触发刷新，超过间隔后才再刷`() {
        var now = 1_000_000L
        val engine = GatewayEngine(InMemoryKeyValueStore(), nowMillis = { now })
        val provider = CountingProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount(provider.id, "u1", "账号", "{}"))

        // 第一次：无历史记录，直接触发
        engine.refreshCreditsSoon(provider.id, "u1")
        assertEquals(true, provider.firstCall.await(5, TimeUnit.SECONDS), "第一次补刷应触发")
        assertEquals(1, provider.creditCalls.get())

        // 间隔内第二次：必须被节流（原 bug 下 CAS 恒成功会再触发一次）
        engine.refreshCreditsSoon(provider.id, "u1")
        Thread.sleep(300)
        assertEquals(1, provider.creditCalls.get(), "距上次不足 10s 不应重复补刷")

        // 时间推进超过最小间隔：应重新放行
        now += 20_000L
        engine.refreshCreditsSoon(provider.id, "u1")
        val deadline = System.currentTimeMillis() + 5_000
        while (provider.creditCalls.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(2, provider.creditCalls.get(), "超过间隔后应再次补刷")
    }
}
