package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.provider.UpstreamError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 模型目录流式拉取的回归：
 * - 每个供应商一就绪即回调，快供应商不必等最慢的供应商（流式可见性）；
 * - 最终聚合完整、失败供应商（异常与自报 error）进 failures；
 * - 阻塞聚合版 models() 保持原语义。
 */
class ModelsStreamingTest {

    private abstract class StubProvider(
        override val id: String,
        override val displayName: String = id,
    ) : Provider {
        override val authKind = AuthKind.NONE
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = FailedChatCall(500, "{}")
    }

    private class FixedProvider(id: String, private val view: ProviderModelCatalogView) : StubProvider(id) {
        override fun listModels(account: ProviderAccount?) = view
    }

    private class GatedProvider(id: String, private val gate: CountDownLatch) : StubProvider(id) {
        override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
            gate.await(5, TimeUnit.SECONDS)
            return ProviderModelCatalogView(listOf(ProviderModel("slow-1", "slow-1")), false, "")
        }
    }

    private class ThrowingProvider(id: String, private val message: String) : StubProvider(id) {
        override fun listModels(account: ProviderAccount?): ProviderModelCatalogView = throw RuntimeException(message)
    }

    /** 统计 invalidateModelCache 被调次数，用于验证 forceRefresh 语义。 */
    private class CountingProvider(id: String) : StubProvider(id) {
        @Volatile
        var invalidations: Int = 0

        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(listOf(ProviderModel("m1", "m1")), false, "")

        override fun invalidateModelCache() {
            invalidations++
        }
    }

    private fun engineWith(vararg providers: Provider): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        for (provider in providers) engine.registry.register(provider)
        return engine
    }

    @Test
    fun `快供应商的目录先于慢供应商交付`() {
        val slowGate = CountDownLatch(1)
        val fastArrived = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        val engine = engineWith(
            GatedProvider("slow", slowGate),
            FixedProvider("fast", ProviderModelCatalogView(listOf(ProviderModel("f1", "f1"), ProviderModel("f2", "f2")), false, "")),
        )

        var catalog: ModelsCatalog? = null
        val worker = Thread {
            catalog = engine.modelsStreaming { chunk ->
                order.add(chunk.providerId)
                if (chunk.providerId == "fast") fastArrived.countDown()
            }
        }
        worker.start()

        // 慢供应商还挂起时，快供应商的目录必须已经送出
        assertTrue(fastArrived.await(5, TimeUnit.SECONDS), "快供应商的 chunk 未在慢供应商完成前回调")

        slowGate.countDown()
        worker.join(5_000)

        // 引擎构造时会注册内置供应商，它们也参与拉取；这里只看本测试关注的两家
        val relevant = order.filter { it == "fast" || it == "slow" }
        assertEquals(listOf("fast", "slow"), relevant)
        val result = catalog ?: error("modelsStreaming 未返回")
        // aggregated 按供应商注册序排列（与旧行为一致），流式只改变交付时机、不改变顺序
        assertEquals(
            listOf("fast/f1", "fast/f2", "slow/slow-1"),
            result.models.filter { it.providerId == "fast" || it.providerId == "slow" }.map { it.fullId }.sorted(),
        )
        // 内置供应商在无账号时可能自报失败，与本次关注的两家无关
        assertTrue(result.failures.keys.none { it == "fast" || it == "slow" }, result.failures.toString())
    }

    @Test
    fun `无账号时供应商不该自报目录错误`() {
        // 回归：ChatGPT 的 listModels(null) 曾返回「未登录 ChatGPT 账号」，模型页因此出现红字
        // 「拉取模型目录时有报错：chatgpt: 未登录 ChatGPT 账号」。没有账号只是尚未登录，不是失败。
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var nullAccountError = ""
        engine.registry.register(
            object : StubProvider("noaccount") {
                override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
                    if (account == null) nullAccountError = "未登录测试账号"
                    return ProviderModelCatalogView(emptyList(), false, if (account == null) "未登录测试账号" else "")
                }
            },
        )
        val catalog = engine.modelsStreaming {}
        assertEquals(null, catalog.failures["noaccount"], "无账号不应被当成拉取失败：${catalog.failures}")
        assertEquals("未登录测试账号", nullAccountError, "测试前提：确实是以 null 账号调用")
    }

    @Test
    fun `内置供应商在无账号时都不报目录错误`() {
        // 所有内置供应商共同遵守的约定：未登录只是没有模型，不能报红（用户看到的错误条就来自这里）。
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val offenders = engine.registry.all()
            .filter { it.listModels(null).error.isNotEmpty() }
            .map { "${it.id}：${it.listModels(null).error}" }
        assertTrue(offenders.isEmpty(), "这些供应商把「没有账号」报成了错误：$offenders")
    }

    @Test
    fun `异常与供应商自报错误都进 failures`() {
        val engine = engineWith(
            ThrowingProvider("bad", "boom"),
            FixedProvider("errv", ProviderModelCatalogView(emptyList(), false, "上游开小差")),
            FixedProvider("ok", ProviderModelCatalogView(listOf(ProviderModel("m1", "m1")), false, "")),
        )
        // 有账号的供应商报错才算真失败；没有账号时引擎会把错误吞掉（见上一个用例）
        for (id in listOf("bad", "errv", "ok")) {
            engine.pool.upsert(ProviderAccount(id, "u1", "账号1", "{}"))
        }

        val chunks = CopyOnWriteArrayList<ProviderModelsChunk>()
        val catalog = engine.modelsStreaming { chunks.add(it) }

        assertEquals(setOf("bad", "errv", "ok"), chunks.map { it.providerId }.filter { it in setOf("bad", "errv", "ok") }.toSet())
        assertEquals("boom", catalog.failures["bad"])
        assertEquals("上游开小差", catalog.failures["errv"])
        assertEquals(null, catalog.failures["ok"])
        assertEquals(listOf("ok/m1"), catalog.models.filter { it.providerId == "ok" }.map { it.fullId })
    }

    @Test
    fun `阻塞聚合版 models 保持原语义`() {
        val engine = engineWith(
            ThrowingProvider("bad", "boom"),
            FixedProvider("ok", ProviderModelCatalogView(listOf(ProviderModel("m1", "m1"), ProviderModel("m2", "m2")), false, "")),
        )

        val models = engine.models()
        assertEquals(listOf("ok/m1", "ok/m2"), models.filter { it.providerId == "ok" }.map { it.fullId })
    }

    @Test
    fun `forceRefresh 才失效模型缓存`() {
        val provider = CountingProvider("cnt")
        val engine = engineWith(provider)

        // 默认不强制：不碰缓存（既有的 modelsStreaming { } 尾随 lambda 调用语义不变）
        engine.modelsStreaming { }
        assertEquals(0, provider.invalidations)

        // 显式强制：失效一次，下次 listModels 重打上游
        engine.modelsStreaming(forceRefresh = true) { }
        assertEquals(1, provider.invalidations)
    }

    @Test
    fun `models 阻塞版不失效缓存`() {
        val provider = CountingProvider("cnt")
        val engine = engineWith(provider)

        engine.models()
        assertEquals(0, provider.invalidations)
    }

    @Test
    fun `区域型供应商按区域各拉一次目录`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(RegionStub())
        // 两个区域各有自己的账号：目录必须各拉各的，不能把一次结果复制给所有区域
        engine.pool.upsert(ProviderAccount("codebuddy", "u-cn", "国内号", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-global", "国际号", "{}"))

        val chunk = engine.modelsStreaming { }.models.filter { it.providerId == "codebuddy" }
        assertEquals(
            listOf("codebuddy-cn/glm-5.2", "codebuddy-global/gpt-5.6-sol"),
            chunk.map { it.fullId },
        )
    }

    @Test
    fun `没有账号的区域不生成模型分组`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(RegionStub())
        engine.pool.upsert(ProviderAccount("codebuddy", "u-cn", "国内号", "{}"))

        val chunk = engine.modelsStreaming { }.models.filter { it.providerId == "codebuddy" }
        assertEquals(listOf("codebuddy-cn/glm-5.2"), chunk.map { it.fullId })
    }

    /**
     * 仅用于验证区域拆分：regions 返回 cn/global，各区域的账号返回各自的目录。
     */
    private class RegionStub : StubProvider("codebuddy"), dev.aigw.core.provider.RegionAwareSupport {
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            listOf(ProviderModel(if (account?.uid == "u-global") "gpt-5.6-sol" else "glm-5.2", "m")),
            false,
            "",
        )

        override fun regionOf(account: ProviderAccount) = if (account.uid == "u-global") "global" else "cn"

        override fun regions() = listOf("cn", "global")
    }
}
