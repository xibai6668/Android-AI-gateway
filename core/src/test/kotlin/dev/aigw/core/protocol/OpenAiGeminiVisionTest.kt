package dev.aigw.core.protocol

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OpenAI image_url → Gemini parts 的转换测试。
 */
class OpenAiGeminiVisionTest {

    private fun translate(body: String): com.google.gson.JsonObject =
        OpenAiGemini.toGeminiRequest(body)

    @Test
    fun `data URL 图片转成 inlineData part`() {
        val request = translate(
            """
            {
              "messages": [
                {"role": "user", "content": [
                  {"type": "text", "text": "这是什么"},
                  {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="}}
                ]}
              ]
            }
            """.trimIndent(),
        )
        val parts = request.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals(2, parts.size())
        assertEquals("这是什么", parts[0].asJsonObject.get("text").asString)
        val inline = parts[1].asJsonObject.getAsJsonObject("inlineData")
        assertEquals("image/png", inline.get("mimeType").asString)
        assertEquals("iVBORw0KGgo=", inline.get("data").asString)
    }

    @Test
    fun `https 图片链接转成 fileData part`() {
        val request = translate(
            """
            {
              "messages": [
                {"role": "user", "content": [
                  {"type": "image_url", "image_url": {"url": "https://example.com/a.png"}}
                ]}
              ]
            }
            """.trimIndent(),
        )
        val parts = request.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals(1, parts.size())
        assertEquals(
            "https://example.com/a.png",
            parts[0].asJsonObject.getAsJsonObject("fileData").get("fileUri").asString,
        )
    }

    @Test
    fun `纯文本消息仍走原路径`() {
        val request = translate(
            """
            {"messages": [{"role": "user", "content": "你好"}]}
            """.trimIndent(),
        )
        val content = request.getAsJsonArray("contents")[0].asJsonObject
        assertEquals("user", content.get("role").asString)
        assertEquals("你好", content.getAsJsonArray("parts")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `空 parts 时回退纯文本提取`() {
        val request = translate(
            """
            {"messages": [{"role": "user", "content": [{"type": "text", "text": "只有文字"}]}]}
            """.trimIndent(),
        )
        val content = request.getAsJsonArray("contents")[0].asJsonObject
        assertTrue(content.getAsJsonArray("parts").size() > 0)
        assertEquals("只有文字", content.getAsJsonArray("parts")[0].asJsonObject.get("text").asString)
    }
}
