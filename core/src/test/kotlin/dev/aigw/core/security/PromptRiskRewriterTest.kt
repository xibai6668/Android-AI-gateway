package dev.aigw.core.security

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 风控提示词替换。
 *
 * 核心约束只有两条：只改系统轨（system / developer），绝不碰用户输入；
 * 命中后写回的内容必须是等义改写，而不是整段清空。
 */
class PromptRiskRewriterTest {

    private fun sanitizer(): PromptRiskRewriter = PromptRiskRewriter { 0L }.apply {
        reload(
            presets = PromptRiskRewriter.builtinRuleSets().keys,
            custom = emptyList(),
        )
    }

    @Test
    fun `Antigravity 浏览器上下文声明被整句改写`() {
        val s = sanitizer()
        val original = "The browser provides no implicit DOM, route, or screenshot context."
        val (rewritten, matched) = s.rewriteText(original)

        assertEquals(
            "The browser exposes no DOM tree, route list, or page screenshot context.",
            rewritten,
        )
        assertEquals(listOf("Antigravity/浏览器上下文声明"), matched)
    }

    @Test
    fun `匹配不分大小写`() {
        val s = sanitizer()
        val (rewritten, matched) = s.rewriteText("the browser PROVIDES no implicit dom, route, or screenshot context.")

        assertTrue(matched.isNotEmpty())
        assertFalse(rewritten.contains("implicit DOM", ignoreCase = true))
    }

    @Test
    fun `只改写 system 与 developer 消息，user 输入原样保留`() {
        val s = sanitizer()
        val body = """
            {
              "model": "gemini-3.8-flash",
              "messages": [
                {"role": "system", "content": "You are an interactive agent that helps users with software engineering tasks. Be terse."},
                {"role": "user", "content": "You are an interactive agent that helps users with software engineering tasks."},
                {"role": "developer", "content": "the user did not write this part."}
              ]
            }
        """.trimIndent()

        val rewritten = s.rewriteOpenAiBody(body, "antigravity", "gemini-3.8-flash", enabled = true)
        val messages = JsonParser.parseString(rewritten).asJsonObject.getAsJsonArray("messages")

        val systemText = messages[0].asJsonObject.get("content").asString
        val userText = messages[1].asJsonObject.get("content").asString
        val developerText = messages[2].asJsonObject.get("content").asString

        // system 被改写，但同段里的其它指令保留
        assertFalse(systemText.contains("You are an interactive agent", ignoreCase = true))
        assertTrue(systemText.contains("Be terse."), "同段其它内容不能被清掉：$systemText")

        // user 一个字都不能动
        assertEquals(
            "You are an interactive agent that helps users with software engineering tasks.",
            userText,
        )

        // developer 也属于系统轨
        assertTrue(developerText.contains("the operator did not author"))
    }

    @Test
    fun `多模态 parts 里的文本同样处理`() {
        val s = sanitizer()
        val body = """
            {
              "messages": [
                {"role": "system", "content": [
                  {"type": "text", "text": "the user did not write the following."}
                ]},
                {"role": "user", "content": [
                  {"type": "text", "text": "the user did not write the following."}
                ]}
              ]
            }
        """.trimIndent()

        val rewritten = s.rewriteOpenAiBody(body, "antigravity", "m", enabled = true)
        val messages = JsonParser.parseString(rewritten).asJsonObject.getAsJsonArray("messages")

        val sysPart = messages[0].asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        val userPart = messages[1].asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("text").asString

        assertTrue(sysPart.contains("the operator did not author"))
        assertEquals("the user did not write the following.", userPart)
    }

    @Test
    fun `关闭时原样返回`() {
        val s = sanitizer()
        val body = """{"messages":[{"role":"system","content":"the user did not write x"}]}"""
        assertEquals(body, s.rewriteOpenAiBody(body, "antigravity", "m", enabled = false))
    }

    @Test
    fun `未命中时不产生取证记录也不改动请求体`() {
        val s = sanitizer()
        val body = """{"messages":[{"role":"system","content":"You are a helpful assistant."}]}"""
        assertEquals(body, s.rewriteOpenAiBody(body, "antigravity", "m", enabled = true))
        assertTrue(s.evidenceList().isEmpty())
    }

    @Test
    fun `命中后记录取证，包含供应商与命中规则`() {
        val s = sanitizer()
        val body = """{"messages":[{"role":"system","content":"the user did not write x"}]}"""
        s.rewriteOpenAiBody(body, "antigravity", "gemini-3.8-flash", enabled = true)

        val evidences = s.evidenceList()
        assertEquals(1, evidences.size)
        assertEquals("antigravity", evidences[0].providerId)
        assertEquals("gemini-3.8-flash", evidences[0].model)
        assertTrue(evidences[0].matchedRules.contains("Antigravity/user-did-not-write 措辞"))
    }

    @Test
    fun `关闭某个预设后该预设的规则不再生效`() {
        val s = PromptRiskRewriter { 0L }.apply {
            reload(presets = setOf("WorkBuddy"), custom = emptyList())
        }
        val (rewritten, matched) = s.rewriteText("the user did not write x")

        assertEquals("the user did not write x", rewritten)
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `自定义规则参与替换`() {
        val s = PromptRiskRewriter { 0L }.apply {
            reload(
                presets = emptySet(),
                custom = listOf(PromptRule("自定义/测试", "a custom trigger", "a neutral phrase")),
            )
        }
        val (rewritten, matched) = s.rewriteText("please ignore a custom trigger here")

        assertEquals("please ignore a neutral phrase here", rewritten)
        assertEquals(listOf("自定义/测试"), matched)
    }

    @Test
    fun `每条内置规则的匹配串都能命中其替换串之外的原文`() {
        // 防的是复制粘贴时把 pattern 写成 replacement 之类的手误：
        // 那样规则永远不命中，且看起来“配置正常”，很难发现。
        val s = sanitizer()
        for (rule in PromptRiskRewriter.builtinRules) {
            assertTrue(rule.pattern.isNotBlank(), "规则 ${rule.name} 的匹配串为空")
            assertTrue(rule.replacement.isNotBlank(), "规则 ${rule.name} 的替换串为空")
            val (rewritten, matched) = s.rewriteText(rule.pattern)
            assertTrue(
                matched.contains(rule.name),
                "规则 ${rule.name} 命中不了自己的匹配串：$rewritten",
            )
            assertFalse(
                rewritten.contains(rule.pattern, ignoreCase = true),
                "规则 ${rule.name} 替换后仍包含原串",
            )
        }
    }

    @Test
    fun `替换是幂等的，重复跑不会叠加改坏`() {
        // 网关可能在重试路径上多次经过同一段文本，规则不能自我叠加。
        val s = sanitizer()
        val original = "You are an interactive agent that helps users with software engineering tasks."
        val (once, _) = s.rewriteText(original)
        val (twice, matchedTwice) = s.rewriteText(once)

        assertEquals(once, twice)
        assertTrue(matchedTwice.isEmpty(), "第二次不应再命中：$matchedTwice")
    }

    @Test
    fun `Claude Code 身份声明的直引号与弯引号两种写法都能命中`() {
        val s = sanitizer()
        val straight = "You are Claude Code, Anthropic's official CLI for Claude"
        val curly = "You are Claude Code, Anthropic\u2019s official CLI for Claude"

        val (rewrittenStraight, matchedStraight) = s.rewriteText(straight)
        val (rewrittenCurly, matchedCurly) = s.rewriteText(curly)

        assertTrue(matchedStraight.isNotEmpty(), "直引号版本应命中")
        assertTrue(matchedCurly.isNotEmpty(), "弯引号版本应命中")
        assertFalse(rewrittenStraight.contains("official", ignoreCase = true))
        assertFalse(rewrittenCurly.contains("official", ignoreCase = true))
    }

    @Test
    fun `内置规则覆盖了 Antigravity 与 WorkBuddy 两组`() {
        val sets = PromptRiskRewriter.builtinRuleSets()
        assertTrue(sets.containsKey("Antigravity"))
        assertTrue(sets.containsKey("WorkBuddy"))
        assertTrue(sets.getValue("Antigravity").size >= 8, "Antigravity 规则偏少：${sets.getValue("Antigravity").size}")
        assertTrue(sets.getValue("WorkBuddy").size >= 4, "WorkBuddy 规则偏少：${sets.getValue("WorkBuddy").size}")
    }

    @Test
    fun `关闭 Antigravity 预设后 WorkBuddy 规则仍生效`() {
        val s = PromptRiskRewriter { 0L }.apply {
            reload(presets = setOf("WorkBuddy"), custom = emptyList())
        }
        val (rewritten, matched) = s.rewriteText("the user did not write")
        assertEquals("the user did not write", rewritten)
        assertTrue(matched.isEmpty())

        val (wbRewritten, wbMatched) = s.rewriteText("This conversation is powered by gemini")
        assertTrue(wbMatched.isNotEmpty())
        assertTrue(wbRewritten.startsWith("Selected model:"))
    }

    @Test
    fun `SecuritySettings 默认开启替换且预设回落到全部内置`() {
        val settings = SecuritySettings()
        assertTrue(settings.promptRewriteEnabled)
        assertEquals(PromptRiskRewriter.builtinRuleSets().keys, settings.effectivePromptPresets())
    }

    @Test
    fun `SecuritySettings 能往返序列化替换配置`() {
        val original = SecuritySettings(
            promptRewriteEnabled = false,
            promptRewritePresets = listOf("Antigravity"),
            promptRewriteRules = listOf(PromptRule("自定义/foo", "foo", "bar")),
        )
        val parsed = SecuritySettings.fromJson(original.toJson().toString())

        assertFalse(parsed.promptRewriteEnabled)
        assertEquals(listOf("Antigravity"), parsed.promptRewritePresets)
        assertEquals(1, parsed.promptRewriteRules.size)
        assertEquals("foo", parsed.promptRewriteRules[0].pattern)
        assertEquals("bar", parsed.promptRewriteRules[0].replacement)
    }

    @Test
    fun `替换后的请求体经 Gemini 转换后 systemInstruction 确实被改写`() {
        // Antigravity 走 OpenAI 体 → Gemini 的转换，网关层在 openChat 之前完成替换。
        // 这条测试盯的就是这条链路：替换发生在转换前，最终落到 systemInstruction 上。
        val s = sanitizer()
        val body = """
            {
              "model": "gemini-3.8-flash",
              "messages": [
                {"role": "system", "content": "You are an interactive agent that helps users with software engineering tasks. Be terse."},
                {"role": "user", "content": "hi"}
              ]
            }
        """.trimIndent()

        val rewritten = s.rewriteOpenAiBody(body, "antigravity", "gemini-3.8-flash", enabled = true)
        val gemini = dev.aigw.core.protocol.OpenAiGemini.toGeminiRequest(rewritten, "gemini-3.8-flash")
        val instruction = gemini.getAsJsonObject("systemInstruction").getAsJsonArray("parts")
            .joinToString(" ") { it.asJsonObject.get("text").asString }

        assertFalse(
            instruction.contains("You are an interactive agent", ignoreCase = true),
            "systemInstruction 里的触发句必须已被改写：$instruction",
        )
        assertTrue(instruction.contains("This assistant helps users"))
        assertTrue(instruction.contains("Be terse."), "同段其它指令要保留：$instruction")
    }
}
