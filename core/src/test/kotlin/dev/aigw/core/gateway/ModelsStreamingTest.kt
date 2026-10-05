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
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `异常与供应商自报错误都进 failures`() {
        val engine = engineWith(
            ThrowingProvider("bad", "boom"),
            FixedProvider("errv", ProviderModelCatalogView(emptyList(), false, "上游开小差")),
            FixedProvider("ok", ProviderModelCatalogView(listOf(ProviderModel("m1", "m1")), false, "")),
        )

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
    fun `区域型供应商按区域拆分进 chunk`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(RegionStub())
        val chunks = CopyOnWriteArrayList<ProviderModelsChunk>()
        val catalog = engine.modelsStreaming { chunks.add(it) }

        val chunk = chunks.single { it.providerId == "codebuddy" }
        assertEquals(catalog.models.filter { it.providerId == "codebuddy" }, chunk.models)
        assertEquals(listOf("codebuddy-cn/glm-5.2", "codebuddy-global/glm-5.2"), chunk.models.map { it.fullId })
    }

    /** 仅用于验证区域拆分：regions 返回 cn/global。 */
    private class RegionStub : StubProvider("codebuddy"), dev.aigw.core.provider.RegionAwareSupport {
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(listOf(ProviderModel("glm-5.2", "GLM")), false, "")

        override fun regionOf(account: ProviderAccount) = "cn"

        override fun regions() = listOf("cn", "global")
    }
}
