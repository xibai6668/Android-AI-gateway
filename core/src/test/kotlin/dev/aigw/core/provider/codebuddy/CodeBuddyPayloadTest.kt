package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CodeBuddy 请求体改写。
 *
 * 两条硬约束来自上游实测：非流式请求会被拒（必须强制 `stream:true`）；
 * `tool_choice` 在上游 Go struct 里是 string，传对象会 400（code=11101）。
 */
class CodeBuddyPayloadTest {

    private fun prepare(body: String) =
        JsonParser.parseString(prepareCodeBuddyBody(body)).asJsonObject

    @Test
    fun `强制开启流式`() {
        assertTrue(prepare("""{"model":"m","messages":[]}""").get("stream").asBoolean)
        assertTrue(prepare("""{"model":"m","messages":[],"stream":false}""").get("stream").asBoolean)
    }

    @Test
    fun `tool_choice 对象形式归一化成字符串`() {
        val auto = prepare("""{"tool_choice":{"type":"auto"},"tools":[{"type":"function"}]}""")
        assertEquals("auto", auto.get("tool_choice").asString)

        val required = prepare("""{"tool_choice":{"type":"required"}}""")
        assertEquals("required", required.get("tool_choice").asString)

        val named = prepare("""{"tool_choice":{"type":"function","function":{"name":"get_weather"}}}""")
        assertEquals("get_weather", named.get("tool_choice").asString)
    }

    @Test
    fun `none 会连同 tools 一起删掉`() {
        val none = prepare("""{"tool_choice":"none","tools":[{"type":"function"}],"functions":[{}]}""")
        assertFalse(none.has("tool_choice"))
        assertFalse(none.has("tools"))
        assertFalse(none.has("functions"))

        val noneObject = prepare("""{"tool_choice":{"type":"none"},"tools":[{"type":"function"}]}""")
        assertFalse(noneObject.has("tool_choice"))
        assertFalse(noneObject.has("tools"))
    }

    @Test
    fun `字符串形式的 auto 原样保留`() {
        val obj = prepare("""{"tool_choice":"auto","tools":[{"type":"function"}]}""")
        assertEquals("auto", obj.get("tool_choice").asString)
        assertTrue(obj.has("tools"))
    }

    @Test
    fun `无法识别的 tool_choice 直接删掉`() {
        assertFalse(prepare("""{"tool_choice":{"type":"weird"}}""").has("tool_choice"))
        assertFalse(prepare("""{"tool_choice":[1,2]}""").has("tool_choice"))
    }

    @Test
    fun `非法 JSON 原样返回不抛异常`() {
        assertEquals("not-json", prepareCodeBuddyBody("not-json"))
    }
}
