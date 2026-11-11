package dev.aigw.core.gateway

import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.RoutedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiApiTest {

    private fun routed(providerId: String, id: String, context: Long = 0) =
        RoutedModel(providerId, providerId, ProviderModel(id = id, name = id, contextWindow = context))

    @Test
    fun `模型 id 带 provider 前缀且标注归属`() {
        val list = listOf(
            routed("trae", "glm-5.2", context = 131_072),
            routed("loomy", "MiniMax-M2.5"),
        )
        val body = JsonParser.parseString(OpenAiApi.modelList(list)).asJsonObject
        assertEquals("list", body.get("object").asString)

        val data = body.getAsJsonArray("data")
        assertEquals(2, data.size())

        val first = data[0].asJsonObject
        assertEquals("trae/glm-5.2", first.get("id").asString)
        assertEquals("trae", first.get("owned_by").asString)
        assertEquals(131_072L, first.get("context_length").asLong)

        val second = data[1].asJsonObject
        assertEquals("loomy/MiniMax-M2.5", second.get("id").asString)
        assertEquals("loomy", second.get("owned_by").asString)
        assertTrue(!second.has("context_length"), "未知上下文长度不应编造")
    }

    @Test
    fun `能力标记兼容 1 与 true 两种写法`() {
        // 目录写入方口径不一：Trae/Loomy 写 "1"，WorkBuddy 写 "true"。
        // 只认一种时另一家的模型能力全为 false，客户端因此根本不发 tools。
        val list = listOf(
            RoutedModel(
                "trae", "trae",
                ProviderModel(id = "m1", name = "m1", extra = mapOf("tools" to "1", "reasoning" to "1")),
            ),
            RoutedModel(
                "codebuddy", "codebuddy",
                ProviderModel(id = "m2", name = "m2", extra = mapOf("tools" to "true", "vision" to "true")),
            ),
            RoutedModel("loomy", "loomy", ProviderModel(id = "m3", name = "m3")),
        )
        val data = JsonParser.parseString(OpenAiApi.modelList(list)).asJsonObject.getAsJsonArray("data")

        val trae = data[0].asJsonObject
        assertTrue(trae.get("supports_tools").asBoolean)
        assertTrue(trae.get("supports_reasoning").asBoolean)

        val codebuddy = data[1].asJsonObject
        assertTrue(codebuddy.get("supports_tools").asBoolean, "true 写法也必须被认")
        assertTrue(codebuddy.get("supports_vision").asBoolean)

        val loomy = data[2].asJsonObject
        assertTrue(!loomy.get("supports_tools").asBoolean, "未声明就是 false，不能编造能力")
    }

    @Test
    fun `错误体是 OpenAI 结构`() {
        val body = JsonParser.parseString(OpenAiApi.errorBody("invalid_api_key", "缺少 Key")).asJsonObject
        val error = body.getAsJsonObject("error")
        assertEquals("缺少 Key", error.get("message").asString)
        assertEquals("invalid_api_key", error.get("code").asString)
        assertTrue(error.get("type").isJsonPrimitive)
    }
}
