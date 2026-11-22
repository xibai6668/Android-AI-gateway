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
    fun `内置规则分为 Antigravity、WorkBuddy 与通用三组`() {
        val sets = PromptRiskRewriter.builtinRuleSets()
        assertEquals(setOf("Antigravity", "WorkBuddy", "通用"), sets.keys)
        // 用精确值而非 >=：规则被误移到别的组时，宽松断言查不出来。
        assertEquals(4, sets.getValue("Antigravity").size)
        assertEquals(4, sets.getValue("WorkBuddy").size)
        assertEquals(7, sets.getValue("通用").size)
    }

    @Test
    fun `WorkBuddy 的 DSH 宿主指纹归在 WorkBuddy 组`() {
        // 这两条来自 CodeBuddyProvider 的 HOST_FINGERPRINTS，是 WorkBuddy 上游的检测点。
        val sets = PromptRiskRewriter.builtinRuleSets()
        val workbuddyPatterns = sets.getValue("WorkBuddy").map { it.pattern }

        assertTrue("DeepSeek Runtime" in workbuddyPatterns)
        assertTrue("DeepSeek Harness" in workbuddyPatterns)
    }

    @Test
    fun `两家共有的指纹放在通用组，关掉任一供应商预设都不会失效`() {
        // Claude Code / Codex / 计费头这些同时在 WorkBuddy 的 FLAGGED_TEXT 与反重力检测里，
        // 若归到单个供应商组，关掉那组会把另一家的保护一起带走。
        val s = PromptRiskRewriter { 0L }.apply {
            reload(presets = setOf("通用"), custom = emptyList())
        }
        val (rewritten, matched) = s.rewriteText("You are Claude Code, Anthropic's official CLI for Claude")

        assertTrue(matched.isNotEmpty(), "只开通用组时共有指纹仍应生效")
        assertFalse(rewritten.contains("official", ignoreCase = true))
    }

    @Test
    fun `关掉 WorkBuddy 预设后 DSH 规则不再生效`() {
        val s = PromptRiskRewriter { 0L }.apply {
            reload(presets = setOf("Antigravity"), custom = emptyList())
        }
        val (rewritten, matched) = s.rewriteText("powered by DeepSeek Harness")

        assertEquals("powered by DeepSeek Harness", rewritten)
        assertTrue(matched.isEmpty(), "WorkBuddy 预设关闭后 DSH 规则不应再命中：$matched")
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

    @Test
    fun `被禁用的内置规则不再生效，其它规则不受影响`() {
        val s = PromptRiskRewriter { 0L }.apply {
            reload(
                presets = PromptRiskRewriter.builtinRuleSets().keys,
                custom = emptyList(),
                disabledBuiltin = setOf("通用/project root 词组"),
            )
        }

        val (kept, matchedKept) = s.rewriteText("the user did not write this")
        assertTrue(matchedKept.isNotEmpty(), "同组其它规则应继续生效")
        assertTrue(kept.contains("the operator did not author"))

        val (still, matched) = s.rewriteText("put it in the project root")
        assertEquals("put it in the project root", still)
        assertTrue(matched.isEmpty(), "被禁用的规则不应再命中：$matched")
    }

    @Test
    fun `自定义规则不受内置禁用列表影响`() {
        val custom = PromptRule("自定义/测试", "a custom trigger", "a neutral phrase")
        val s = PromptRiskRewriter { 0L }.apply {
            reload(
                presets = PromptRiskRewriter.builtinRuleSets().keys,
                custom = listOf(custom),
                disabledBuiltin = PromptRiskRewriter.builtinRules.map { it.name }.toSet(),
            )
        }
        val (rewritten, matched) = s.rewriteText("a custom trigger")
        assertEquals("a neutral phrase", rewritten)
        assertEquals(listOf("自定义/测试"), matched)
    }

    @Test
    fun `导出再导入能还原同一套自定义规则`() {
        val original = listOf(
            PromptRule("自定义/甲", "alpha", "beta"),
            PromptRule("自定义/乙", "gamma", "delta"),
        )
        val json = PromptRiskRewriter.encodeRules(original)
        val parsed = PromptRiskRewriter.decodeRules(json)

        assertEquals(original, parsed)
    }

    @Test
    fun `导入接受裸数组形式`() {
        // 用户手写或从别处粘过来的文件未必带 version/rules 包装。
        val raw = """[{"name":"自定义/甲","pattern":"alpha","replacement":"beta"}]"""
        val parsed = PromptRiskRewriter.decodeRules(raw)

        assertEquals(1, parsed?.size)
        assertEquals("alpha", parsed?.get(0)?.pattern)
        assertEquals("beta", parsed?.get(0)?.replacement)
    }

    @Test
    fun `导入时缺名字的规则自动补一个可用名字`() {
        val parsed = PromptRiskRewriter.decodeRules("""[{"pattern":"alpha","replacement":"beta"}]""")
        assertEquals(1, parsed?.size)
        assertTrue(parsed!![0].name.isNotEmpty())
    }

    @Test
    fun `导入时忽略无匹配串的条目`() {
        val parsed = PromptRiskRewriter.decodeRules(
            """[{"name":"空","pattern":"","replacement":"x"},{"name":"正常","pattern":"a","replacement":"b"}]""",
        )
        assertEquals(1, parsed?.size)
        assertEquals("正常", parsed?.get(0)?.name)
    }

    @Test
    fun `无法解析的内容返回 null`() {
        assertEquals(null, PromptRiskRewriter.decodeRules("not json at all"))
        assertEquals(null, PromptRiskRewriter.decodeRules(""))
        assertEquals(null, PromptRiskRewriter.decodeRules("{\"version\":1}"))
    }

    @Test
    fun `禁用列表能往返序列化`() {
        val original = SecuritySettings(
            promptRewriteDisabledRules = listOf("通用/project root 词组", "通用/主分支括注"),
        )
        val parsed = SecuritySettings.fromJson(original.toJson().toString())

        assertEquals(original.promptRewriteDisabledRules, parsed.promptRewriteDisabledRules)
        assertTrue(parsed.isBuiltinRuleDisabled("通用/project root 词组"))
        assertFalse(parsed.isBuiltinRuleDisabled("Antigravity/交互式 Agent 开场白"))
    }
}
