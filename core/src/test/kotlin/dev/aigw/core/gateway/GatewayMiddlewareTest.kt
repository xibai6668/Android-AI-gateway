package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 请求中间件单测。
 *
 * 重点锁两条铁律：
 * 1. 钩子出错必须视为「没改过」继续放行，绝不能因为改写逻辑写错而让请求失败；
 * 2. 链是有序的，前一个的产物是后一个的输入，结果必须逐级传递。
 */
class GatewayMiddlewareTest {

    private fun json(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    private fun context(model: String = "fast") = MiddlewareContext(
        requestedModel = model,
        providerId = "trae",
        upstreamModel = model,
    )

    /** 往自定义字段 `trace` 上追加标签，用来观察中间件的执行顺序与结果传递。 */
    private class TraceMiddleware(override val id: String, private val tag: String) : GatewayMiddleware {
        override fun onRequest(context: MiddlewareContext, body: JsonObject): JsonObject =
            body.deepCopy().apply { addProperty("trace", body.stringOrNull("trace").orEmpty() + tag) }
    }

    // ---------------------------------------------------------------- 模型别名

    @Test
    fun `别名命中时替换 model 且不改动入参`() {
        val middleware = ModelAliasMiddleware(mapOf("fast" to "deepseek/flash"))
        val original = json("""{"model":"fast","messages":[],"temperature":0.5}""")

        val result = middleware.onRequest(context(), original)

        assertEquals("deepseek/flash", result.stringOrNull("model"))
        assertEquals("fast", original.stringOrNull("model"), "入参 body 必须保持原样")
        assertNotNull(result.get("temperature"), "只改 model，其它字段原样带走")
        assertEquals("model-alias", middleware.id)
    }

    @Test
    fun `别名未命中或别名表为空时原样返回`() {
        val original = json("""{"model":"gpt-4o","messages":[]}""")

        assertSame(original, ModelAliasMiddleware(emptyMap()).onRequest(context(), original))
        assertSame(original, ModelAliasMiddleware(mapOf("fast" to "x")).onRequest(context(), original))
    }

    // ---------------------------------------------------------------- system 追加

    @Test
    fun `system 追加写进第一条 system 消息末尾`() {
        val middleware = SystemPromptAppendMiddleware("\n\nEXTRA")
        val original = json("""{"messages":[{"role":"system","content":"BASE"},{"role":"user","content":"hi"}]}""")

        val result = middleware.onRequest(context(), original)
        val messages = assertNotNull(result.arrayOrNull("messages"))
        assertEquals(2, messages.size())
        assertEquals("BASE\n\nEXTRA", messages[0].asJsonObject.stringOrNull("content"))
        assertEquals("hi", messages[1].asJsonObject.stringOrNull("content"), "后续消息不受影响")
        assertEquals("BASE", original.arrayOrNull("messages")!![0].asJsonObject.stringOrNull("content"), "入参不应被就地改写")
        assertEquals("system-prompt", middleware.id)
    }

    @Test
    fun `没有 system 消息时插到 messages 最前面`() {
        val middleware = SystemPromptAppendMiddleware("EXTRA")
        val original = json("""{"messages":[{"role":"user","content":"hi"}]}""")

        val result = middleware.onRequest(context(), original)
        val messages = assertNotNull(result.arrayOrNull("messages"))
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject.stringOrNull("role"))
        assertEquals("EXTRA", messages[0].asJsonObject.stringOrNull("content"))
        assertEquals("hi", messages[1].asJsonObject.stringOrNull("content"))
    }

    // ---------------------------------------------------------------- think 剥离

    @Test
    fun `think 标签按闭合与未闭合两种情况剥离`() {
        val middleware = ThinkTagsStripMiddleware()
        val original = json(
            """{"messages":[
                {"role":"assistant","content":"Hello<think>internal reasoning</think>World"},
                {"role":"assistant","content":"Answer<think>leaked reasoning to the end"},
                {"role":"assistant","content":"no tags here"}
            ]}""",
        )

        val result = middleware.onRequest(context(), original)
        val messages = assertNotNull(result.arrayOrNull("messages"))
        assertEquals("HelloWorld", messages[0].asJsonObject.stringOrNull("content"))
        assertEquals("Answer", messages[1].asJsonObject.stringOrNull("content"), "未闭合的 think 到结尾都应剥掉")
        assertEquals("no tags here", messages[2].asJsonObject.stringOrNull("content"))
        assertEquals("think-tags", middleware.id)
    }

    @Test
    fun `think 剥离只作用于 assistant 消息`() {
        val middleware = ThinkTagsStripMiddleware()
        val original = json("""{"messages":[{"role":"user","content":"keep <think>x</think> me"}]}""")

        val result = middleware.onRequest(context(), original)

        assertSame(original, result, "user 消息是用户原文，任何角色判断之外的改写都属越界")
    }

    // ---------------------------------------------------------------- 链

    @Test
    fun `链按注册顺序依次执行且结果逐级传递`() {
        val chain = MiddlewareChain(listOf(TraceMiddleware("m1", "1"), TraceMiddleware("m2", "2")))

        assertFalse(chain.isEmpty)
        val result = chain.apply(context(), json("""{"model":"fast"}"""))

        assertEquals("12", result.stringOrNull("trace"))
        assertTrue(chain.failures().isEmpty())
    }

    @Test
    fun `单个中间件抛异常时跳过它后续仍执行并保留上一步结果`() {
        val boom = object : GatewayMiddleware {
            override val id: String get() = "boom"
            override fun onRequest(context: MiddlewareContext, body: JsonObject): JsonObject =
                throw IllegalStateException("boom-msg")
        }
        val chain = MiddlewareChain(listOf(TraceMiddleware("m1", "1"), boom, TraceMiddleware("m3", "3")))

        val result = chain.apply(context(), json("""{"model":"fast"}"""))

        // 抛异常的中间件被跳过：m1 的结果直接交给 m3，链条不中断
        assertEquals("13", result.stringOrNull("trace"))
        assertEquals("boom-msg", chain.failures()["boom"])
        assertEquals(1, chain.failures().size)
    }

    @Test
    fun `空链直接返回原对象且记录为空`() {
        val chain = MiddlewareChain()
        val original = json("""{"model":"fast"}""")

        assertTrue(chain.isEmpty)
        assertSame(original, chain.apply(context(), original))
        assertTrue(chain.failures().isEmpty())
    }

    @Test
    fun `with 返回追加后的新链且不影响原链`() {
        val base = MiddlewareChain(listOf(TraceMiddleware("m1", "1")))

        val extended = base.with(TraceMiddleware("m2", "2"))

        assertEquals("1", base.apply(context(), json("{}")).stringOrNull("trace"))
        assertEquals("12", extended.apply(context(), json("{}")).stringOrNull("trace"))
        assertFalse(extended.isEmpty)
        assertNull(base.failures()["m2"])
    }

    @Test
    fun `别名与 system 追加可串在一条链上`() {
        val chain = MiddlewareChain(
            listOf(
                ModelAliasMiddleware(mapOf("fast" to "deepseek/flash")),
                SystemPromptAppendMiddleware("\n\nEXTRA"),
            ),
        )

        val result = chain.apply(context(), json("""{"model":"fast","messages":[{"role":"system","content":"BASE"}]}"""))

        assertEquals("deepseek/flash", result.stringOrNull("model"))
        val messages: JsonArray = result.arrayOrNull("messages")!!
        assertEquals("BASE\n\nEXTRA", messages[0].asJsonObject.stringOrNull("content"))
    }
}
