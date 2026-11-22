package dev.aigw.core.security

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 单条替换规则：命中 [pattern] 的文本替换成 [replacement]。
 *
 * 规则有两种来源：
 * - 内置预设（[PromptRiskRewriter.builtinRules]），按上游实测得出的第三方客户端提示词指纹；
 * - 用户自定义（安全防护页录入），用于跟进上游新增的检测。
 */
data class PromptRule(
    /** 展示名，同时作为「是哪个预设」的标识。 */
    val name: String,
    val pattern: String,
    val replacement: String,
) {
    /** 所属预设；自定义规则为 [PromptRiskRewriter.CUSTOM_SET]。 */
    val setName: String get() = name.substringBefore('/', name)
}

/**
 * 风控提示词替换的命中记录，供「替换取证」面板展示。
 */
data class PromptRewriteEvidence(
    val seq: Long,
    val atMillis: Long,
    /** 供应商 id（如 antigravity / codebuddy）。 */
    val providerId: String,
    val model: String,
    /** 命中的规则展示名。 */
    val matchedRules: List<String>,
    val originalSnippet: String,
    val rewrittenSnippet: String,
)

/**
 * 风控提示词替换器。
 *
 * 上游（Antigravity / WorkBuddy 等）会检查请求里的**系统提示词指纹**来判断这条请求
 * 是不是官方客户端发出的，命中后不一定直接报错，常见做法是伪装成配额耗尽的
 * 429 RESOURCE_EXHAUSTED，或是让模型降智。这些指纹基本都是第三方 Agent 框架嵌在
 * system 里的固定句子（身份声明、浏览器上下文说明、工具用法指引等），
 * 等义改写这些句子就能让字面匹配失效，而模型看到的语义不变。
 *
 * 与 [RequestSanitizer] 的区别：那个是「反审核脱敏」，处理敏感词（DoS/exploit）并注入零宽
 * 空格；这里是整句等义替换，针对的是反代检测特征。两者互不重叠，可同时开启。
 *
 * 作用范围严格限定在系统轨（system / developer 消息），绝不触碰用户输入。
 */
class PromptRiskRewriter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** 用户自定义规则在展示时的分组名。 */
        const val CUSTOM_SET = "自定义"

        /**
         * 内置预设规则。
         *
         * 来源是各家上游的公开实测报告（见各条注释），不是本项目自测结论。
         * 规则按「整句等义替换」设计：只换掉触发检测的那句，同段里的其它有效指令
         * （工具用法、约束条件）保持原样，不能整段删除否则会改变模型行为。
         *
         * 替换文本必须保持语义等价：模型读到的意思不变，只是字面串变了。
         * 工具体名（如 codemode）不做改写——改了模型可能叫不出那个工具，得不偿失。
         */
        val builtinRules: List<PromptRule> = listOf(
            // ---------------------------------------------------------------- Antigravity 专有
            // 反重力对「浏览器上下文说明」有字面指纹：命中后伪装成 429 RESOURCE_EXHAUSTED。
            // 上游会先做大小写归一与空白折叠再匹配，所以整句等义改写即可，不必逐字符打散。
            PromptRule(
                name = "Antigravity/浏览器上下文声明",
                pattern = "The browser provides no implicit DOM, route, or screenshot context.",
                replacement = "The browser exposes no DOM tree, route list, or page screenshot context.",
            ),
            // CLI 内核组装的第一句系统提示词，被上游列为反代特征。
            PromptRule(
                name = "Antigravity/交互式 Agent 开场白",
                pattern = "You are an interactive agent that helps users with software engineering tasks.",
                replacement = "This assistant helps users with software engineering tasks.",
            ),
            // “the user did not write” 这组措辞是另一条已知触发点。
            PromptRule(
                name = "Antigravity/user-did-not-write 措辞",
                pattern = "the user did not write",
                replacement = "the operator did not author",
            ),
            // opencode 在身份句后的同位语续写，单独出现也会触发。
            PromptRule(
                name = "Antigravity/coding agent harness 同位语",
                pattern = "a coding agent harness",
                replacement = "a coding assistant environment",
            ),
            // ---------------------------------------------------------------- WorkBuddy / CodeBuddy 专有
            // 以下来自 CodeBuddyProvider 的 FLAGGED_TEXT / HOST_FINGERPRINTS，是 WorkBuddy 上游的检测点。
            // DSH（DeepSeek Harness）的宿主标识，上游按「连续词组」匹配，拆开即失效。
            PromptRule(
                name = "WorkBuddy/DSH 宿主标识 Runtime",
                pattern = "DeepSeek Runtime",
                replacement = "DSH runtime",
            ),
            PromptRule(
                name = "WorkBuddy/DSH 宿主标识 Harness",
                pattern = "DeepSeek Harness",
                replacement = "DSH harness",
            ),
            // WorkBuddy 的系统首行带上了模型名，被上游当作非官方客户端声明。
            PromptRule(
                name = "WorkBuddy/系统首行模型声明",
                pattern = "This conversation is powered by",
                replacement = "Selected model:",
            ),
            // 上游内部错误码的回显，请求体出现即整单拦。
            PromptRule(
                name = "WorkBuddy/内部错误码回显",
                pattern = "11-128",
                replacement = "11 - 128",
            ),
            // ---------------------------------------------------------------- 通用：两家都会查的第三方客户端指纹
            // 这些条目同时出现在 WorkBuddy 的 FLAGGED_TEXT 和反重力的检测里，
            // 放单独一组：否则关掉其中一个供应商的预设会把另一家的保护一起带走。
            //
            // Claude Code 的官方身份声明；去掉 official 即不再命中。
            // 真实提示词里撇号可能是直引号或弯引号，两种都要覆盖。
            PromptRule(
                name = "通用/Claude Code 身份声明",
                pattern = "You are Claude Code, Anthropic's official CLI for Claude",
                replacement = "You are Claude Code, Anthropic's CLI for Claude",
            ),
            PromptRule(
                name = "通用/Claude Code 身份声明（弯引号）",
                pattern = "You are Claude Code, Anthropic\u2019s official CLI for Claude",
                replacement = "You are Claude Code, Anthropic\u2019s CLI for Claude",
            ),
            // Codex CLI 的项目描述句；补上冠词并改写成不同句式即可避开。
            PromptRule(
                name = "通用/Codex CLI 项目描述",
                pattern = "Codex CLI is an open source project led by OpenAI.",
                replacement = "The Codex CLI is an open source project maintained by OpenAI.",
            ),
            // Anthropic 计费头，出现在 system 文本里属于客户端特征。
            PromptRule(
                name = "通用/计费头残留",
                pattern = "x-anthropic-billing-header",
                replacement = "x-client-header",
            ),
            // git 分支说明的固定括注，属于 Claude Code 提示词片段。
            PromptRule(
                name = "通用/主分支括注",
                pattern = "Main branch (you will usually use this for PRs)",
                replacement = "Main branch (usually the base for PRs)",
            ),
            // CodeBuddy 与反重力都会查的宿主指纹。
            // 注意这条影响面最大：project root 是通用说法，正常代码说明里也常见，
            // 如果发现模型对项目结构的理解变差，可在界面上单独关掉。
            PromptRule(
                name = "通用/project root 词组",
                pattern = "project root",
                replacement = "project directory",
            ),
            // Codex 的桌面上下文标题，CodeBuddy 侧要求改成中性写法。
            PromptRule(
                name = "通用/Codex 桌面上下文标题",
                pattern = "# Codex desktop context",
                replacement = "# Desktop application context",
            ),
        )

        /** 按预设分组，供 UI 渲染。 */
        fun builtinRuleSets(): Map<String, List<PromptRule>> =
            builtinRules.groupBy { it.setName }

        /** 导出文件格式版本；将来结构变动时用于拒绝旧版本文件。 */
        const val EXPORT_VERSION = 1

        /**
         * 把一组规则编码成可分享的 JSON。
         *
         * 只导出规则本身，不带开关状态与预设开关：导入方拿到的是「一套词表」，
         * 怎么启用由对方自己在界面里决定。
         */
        fun encodeRules(rules: List<PromptRule>): String {
            val obj = com.google.gson.JsonObject().apply {
                addProperty("version", EXPORT_VERSION)
                add(
                    "rules",
                    com.google.gson.JsonArray().apply {
                        for (r in rules) {
                            add(
                                com.google.gson.JsonObject().apply {
                                    addProperty("name", r.name)
                                    addProperty("pattern", r.pattern)
                                    addProperty("replacement", r.replacement)
                                },
                            )
                        }
                    },
                )
            }
            return obj.toString()
        }

        /**
         * 解析导入文件。
         *
         * 宽容处理：接受 `{version, rules:[...]}`，也接受裸数组 `[...]`，
         * 因为用户手写或从别处粘过来的文件未必带外层包装。
         *
         * @return 解析出的规则；格式不可识别时返回 null（调用方报错给用户）。
         */
        fun decodeRules(raw: String): List<PromptRule>? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val element = runCatching { com.google.gson.JsonParser.parseString(text) }.getOrNull() ?: return null
            val array = when {
                element.isJsonArray -> element.asJsonArray
                element.isJsonObject -> element.asJsonObject.getAsJsonArray("rules") ?: return null
                else -> return null
            }
            val parsed = mutableListOf<PromptRule>()
            for (item in array) {
                val obj = runCatching { item.asJsonObject }.getOrNull() ?: continue
                val pattern = obj.get("pattern")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (pattern.isEmpty()) continue
                parsed.add(
                    PromptRule(
                        name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                            .ifEmpty { "导入/${pattern.take(24)}" },
                        pattern = pattern,
                        replacement = obj.get("replacement")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    ),
                )
            }
            return parsed
        }
    }

    private val evidenceHistory = ConcurrentLinkedDeque<PromptRewriteEvidence>()
    private val maxEvidenceCount = 50
    private val evidenceSeq = AtomicLong(0)

    /** 生效规则：内置预设（可整体关闭）+ 用户自定义。 */
    @Volatile
    private var activeRules: List<PromptRule> = emptyList()

    @Volatile
    private var enabledPresets: Set<String> = builtinRuleSets().keys

    @Volatile
    private var customRules: List<PromptRule> = emptyList()

    /** 重新装配规则链。 */
    @Synchronized
    fun reload(presets: Set<String>, custom: List<PromptRule>, disabledBuiltin: Set<String> = emptySet()) {
        enabledPresets = presets
        customRules = custom
        activeRules = builtinRules.filter { it.setName in presets && it.name !in disabledBuiltin } + custom
    }

    fun currentRulesCount(): Int = activeRules.size

    fun evidenceList(): List<PromptRewriteEvidence> = evidenceHistory.toList()

    fun clearEvidence() {
        evidenceHistory.clear()
    }

    /**
     * 对一段系统轨文本按规则做等义替换。
     *
     * @return 替换后的文本与命中的规则名；无命中时原样返回（调用方据此跳过写回）。
     */
    fun rewriteText(text: String): Pair<String, List<String>> {
        if (text.isEmpty()) return text to emptyList()
        var current = text
        val matched = mutableListOf<String>()
        for (rule in activeRules) {
            if (rule.pattern.isEmpty()) continue
            if (!current.contains(rule.pattern, ignoreCase = true)) continue
            val next = current.replace(rule.pattern, rule.replacement, ignoreCase = true)
            if (next != current) {
                current = next
                matched.add(rule.name)
            }
        }
        return current to matched
    }

    /**
     * 替换 OpenAI 请求体里的系统轨：`messages` 中 role 为 system / developer 的文本。
     *
     * 只改系统消息，user / assistant / tool 的消息一律不动。
     */
    fun rewriteOpenAiBody(body: String, providerId: String, model: String, enabled: Boolean): String {
        if (!enabled || activeRules.isEmpty()) return body
        val obj = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return body
        val messages = obj.getAsJsonArray("messages") ?: return body

        var modified = false
        val allMatched = mutableListOf<String>()
        var originalSample = ""
        var rewrittenSample = ""

        for (element in messages) {
            val msg = runCatching { element.asJsonObject }.getOrNull() ?: continue
            val role = msg.get("role")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (role != "system" && role != "developer") continue

            when (val content = msg.get("content")) {
                null -> continue
                is com.google.gson.JsonPrimitive -> {
                    if (!content.isString) continue
                    val original = content.asString
                    val (rewritten, matched) = rewriteText(original)
                    if (matched.isEmpty()) continue
                    if (originalSample.isEmpty()) {
                        originalSample = original.take(300)
                        rewrittenSample = rewritten.take(300)
                    }
                    msg.addProperty("content", rewritten)
                    allMatched += matched
                    modified = true
                }
                // 多模态消息：content 是 parts 数组，文本在每项的 text 字段
                else -> {
                    if (!content.isJsonArray) continue
                    for (part in content.asJsonArray) {
                        val partObj = runCatching { part.asJsonObject }.getOrNull() ?: continue
                        val textElem = partObj.get("text") ?: continue
                        if (!textElem.isJsonPrimitive || !textElem.asJsonPrimitive.isString) continue
                        val original = textElem.asString
                        val (rewritten, matched) = rewriteText(original)
                        if (matched.isEmpty()) continue
                        if (originalSample.isEmpty()) {
                            originalSample = original.take(300)
                            rewrittenSample = rewritten.take(300)
                        }
                        partObj.addProperty("text", rewritten)
                        allMatched += matched
                        modified = true
                    }
                }
            }
        }

        if (!modified) return body
        recordEvidence(providerId, model, allMatched.distinct(), originalSample, rewrittenSample)
        return obj.toString()
    }

    private fun recordEvidence(
        providerId: String,
        model: String,
        rules: List<String>,
        originalSnippet: String,
        rewrittenSnippet: String,
    ) {
        evidenceHistory.addFirst(
            PromptRewriteEvidence(
                seq = evidenceSeq.getAndIncrement(),
                atMillis = nowMillis(),
                providerId = providerId,
                model = model,
                matchedRules = rules,
                originalSnippet = originalSnippet,
                rewrittenSnippet = rewrittenSnippet,
            ),
        )
        while (evidenceHistory.size > maxEvidenceCount) evidenceHistory.removeLast()
    }
}
