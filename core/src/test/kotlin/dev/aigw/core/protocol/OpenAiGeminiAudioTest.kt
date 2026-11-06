package dev.aigw.core.provider

import com.google.gson.JsonParser
import dev.aigw.core.protocol.OpenAiGemini
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 音频 part 的协议转换回归。
 *
 * 这条曾经是真实缺口：`multimodalPartsOf` 只认 text / image_url，音频 part 被**静默丢弃**，
 * 模型收到空内容后照样回一段文本——看起来成功，其实是凭空编造。所以这里锁住
 * 「音频必须变成 inlineData」与「format → MIME」两件事。
 */
class OpenAiGeminiAudioTest {

    private fun geminiParts(contentJson: String): List<com.google.gson.JsonObject> {
        val body = """{"model":"gemini-audio","messages":[{"role":"user","content":$contentJson}]}"""
        val request = OpenAiGemini.toGeminiRequest(body, "gemini-audio")
        val parts = request.arrayOrNull("contents")!!.first().asObjectOrNull()!!.arrayOrNull("parts")!!
        return parts.mapNotNull { it.asObjectOrNull() }
    }

    @Test
    fun `input_audio 转成 inlineData 且带上正确 MIME`() {
        val parts = geminiParts(
            """[{"type":"input_audio","input_audio":{"data":"QUJD","format":"wav"}},
                {"type":"text","text":"转写这段"}]""",
        )
        val inline = parts.firstNotNullOfOrNull { it.objOrNull("inlineData") }
        assertTrue(inline != null, "音频必须变成 inlineData，不能被丢掉：$parts")
        assertEquals("audio/wav", inline!!.stringOrNull("mimeType"))
        assertEquals("QUJD", inline.stringOrNull("data"))
        // 指令文本要在，否则模型不知道要做什么
        assertTrue(parts.any { it.stringOrNull("text") == "转写这段" })
    }

    @Test
    fun `format 是 mp3 时映射成 audio_mpeg`() {
        val parts = geminiParts("""[{"type":"input_audio","input_audio":{"data":"QQ==","format":"mp3"}}]""")
        val inline = parts.firstNotNullOfOrNull { it.objOrNull("inlineData") }!!
        assertEquals("audio/mpeg", inline.stringOrNull("mimeType"))
    }

    @Test
    fun `format 缺失时按 wav 处理而不是报错`() {
        val parts = geminiParts("""[{"type":"input_audio","input_audio":{"data":"QQ=="}}]""")
        assertEquals("audio/wav", parts.firstNotNullOfOrNull { it.objOrNull("inlineData") }!!.stringOrNull("mimeType"))
    }

    @Test
    fun `空音频数据不产出 part`() {
        // 空数据不会产 contents（没有可发的 part），这正是想要的结果：不能把空音频当成合法输入发出去
        val body = """{"model":"gemini-audio","messages":[{"role":"user","content":
            [{"type":"input_audio","input_audio":{"data":"","format":"wav"}}]}]}"""
        val request = OpenAiGemini.toGeminiRequest(body, "gemini-audio")
        val contents = request.arrayOrNull("contents") ?: com.google.gson.JsonArray()
        val inlineCount = contents.sumOf { content ->
            val parts = content.asObjectOrNull()?.arrayOrNull("parts") ?: com.google.gson.JsonArray()
            parts.count { part -> part.asObjectOrNull()?.objOrNull("inlineData") != null }
        }
        assertEquals(0, inlineCount, "空音频不该生产 inlineData part")
    }

    @Test
    fun `图片与音频可以同在一个请求里`() {
        val parts = geminiParts(
            """[{"type":"image_url","image_url":{"url":"data:image/png;base64,SU1H"}},
                {"type":"input_audio","input_audio":{"data":"QVVESU8=","format":"wav"}}]""",
        )
        val mimes = parts.mapNotNull { it.objOrNull("inlineData")?.stringOrNull("mimeType") }
        assertEquals(listOf("image/png", "audio/wav"), mimes)
    }

    @Test
    fun `audioMimeOf 兼容裸格式与已是 MIME 两种输入`() {
        assertEquals("audio/mpeg", OpenAiGemini.audioMimeOf("mp3"))
        assertEquals("audio/mp4", OpenAiGemini.audioMimeOf("m4a"))
        assertEquals("audio/wav", OpenAiGemini.audioMimeOf("WAV"))
        assertEquals("audio/webm", OpenAiGemini.audioMimeOf("audio/webm"))
        assertEquals("audio/wav", OpenAiGemini.audioMimeOf(""))
    }
}
