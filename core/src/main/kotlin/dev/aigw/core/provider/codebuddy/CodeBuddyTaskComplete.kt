package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.UUID

/** 构造一条上报事件。 */
internal class GrowthEvent(private val code: String) {
    val json: JsonObject = JsonObject()

    init {
        json.addProperty("eventCode", code)
    }

    fun str(key: String, value: String): GrowthEvent = apply { json.addProperty(key, value) }
    fun num(key: String, value: Number): GrowthEvent = apply { json.addProperty(key, value) }
    fun bool(key: String, value: Boolean): GrowthEvent = apply { json.addProperty(key, value) }
    fun arr(key: String): GrowthEvent = apply { json.add(key, JsonArray()) }
    fun arr(key: String, vararg values: String): GrowthEvent = apply {
        json.add(key, JsonArray().apply { values.forEach { add(it) } })
    }
    fun sub(key: String, value: JsonObject): GrowthEvent = apply { json.add(key, value) }
}

/** 账号与时钟：事件里的指纹、时间戳都从这里取。 */
internal class GrowthContext(
    val uid: String,
    val nickname: String,
    val nowMillis: Long,
)

/**
 * 一次真实对话的会话标识。
 *
 * 埋点里的 `conversationId` 必须指向**真实发出的那次对话**，自造 UUID 上游不认
 * （参照实现用真实会话实测过），所以推进任务时先真发对话、再把它的 id 交给事件构造。
 * [messageId] 是服务端回传的消息 id，官方口径要求它也是真实值；拿不到时退回合成的 id。
 */
internal data class GrowthSession(val conversationId: String, val messageId: String)

/**
 * 一个成长任务的完成配方。
 *
 * [events] 每调用一次生成**一轮**全新事件（id 与时间戳都不同），[round] 从 0 递增；
 * 进度缺口比 [maxRounds] 大时按缺口来，不会为了凑数白打上游。
 * [session] 是这一轮真实对话的会话标识，null 表示没发成对话或该任务不需要发对话。
 */
internal data class GrowthRecipe(
    val label: String,
    val maxRounds: Int = 1,
    /** 该轮要真发的对话内容；null 表示这个任务不需要真发对话。 */
    val chatFor: ((Int) -> String)? = null,
    /** 只在 CST 23:00-次日 08:00 计数（夜猫子活动）。 */
    val nightOnly: Boolean = false,
    val events: (GrowthContext, Int, GrowthSession?) -> List<JsonObject>,
)

/**
 * WorkBuddy 成长任务的「自动完成」配方。
 *
 * 官方客户端的「去完成」按钮本质上是往 `POST /v2/report`（裸数组）打一组埋点事件，
 * 服务端据此记任务进度。这里只负责把事件构造出来——发请求、排序、回读进度、领奖都在
 * [CodeBuddyProvider] 里。
 *
 * 事件形状、设备指纹口径与各任务的推进方式照抄开源实现
 * [L0NE-6/WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily)（MIT）的
 * `workbuddy_daily.py`：`desktop_fingerprint` / `desktop_chat_sequence` /
 * `desktop_buddy5_sequence` / `t_chat_n` / `t_template_5` / `t_expert_5` /
 * `t_canvas_automation` / `t_buddy_apps` / `_desktop_fingerprint_fallback` /
 * `_run_local_expert_task`。
 *
 * 每条配方都同时给出「有真实会话」与「拿不到真实会话」两种口径：前者与官方客户端一致，
 * 后者是参照实现的降级路径（保住旧口径，不让整个任务因为一次对话失败就放弃）。
 */
internal object CodeBuddyTaskComplete {

    /** 官方 5.9.3 桌面端的版本三件套：上报时要声明自己是哪个客户端。 */
    const val DESKTOP_VERSION = "5.9.3"
    const val DESKTOP_COMMIT = "d46924219e79332f49c37ccebf27292f87001b19"
    const val DESKTOP_RELEASE_DATE = 1793093056315L
    const val DESKTOP_UA = "WorkBuddy/5.9.3 WorkBuddy/5.9.3 CLI/2.165.0"

    /** 对话类任务用的模型（与埋点里声明的 requestModelId 保持一致）。 */
    const val GROWTH_CHAT_MODEL = "glm-5.2"

    /** 桌面任务的会话 id 用固定模型名，与参照实现一致。 */
    private const val DESKTOP_MODEL = "fast-model"

    /** 召唤专家（`expert_5`）事件声明的模型口径与桌面任务不同，别混用。 */
    private const val EXPERT_MODEL_ID = "deepseek-v4-flash"
    private const val EXPERT_MODEL_NAME = "DeepSeek V4 Flash"

    private const val CPU_MODEL = "11th Gen Intel(R) Core(TM) i5-11320H @ 3.20GHz"

    /** 企鹅教师助手 Buddy 模板 id（参照实现实测值）。 */
    private const val QQ_BUDDY_ID = "cb_y5Dy46tPQGGWtueMxXbe"
    private const val DEFAULT_BUDDY_ID = "buddy-app-default"

    /** 技能兜底 id：市场里取不到时用上游实测可点亮的那条。 */
    private const val SKILL_FALLBACK_ID = "skill_2097350077599879168"
    private const val SKILL_FALLBACK_NAME = "润泽小馆·日报撰写"

    /** 模板场景 id：上游不校验真实性，用参照实现内置的线上场景值。 */
    private val TEMPLATE_SCENES = listOf(
        "0" to "幻灯片",
        "4" to "深度研究",
        "8" to "数据分析",
        "16" to "设计",
        "20" to "日常开发",
    )

    /** 灵感案例（参照实现的默认案例）。 */
    private const val PLAYBOOK_ID = "01-ProductDesign"
    private const val PLAYBOOK_NAME = "产品设计"

    private const val SKILL_PROMPT = "你好，帮我写一份简短的日报，回答OK即可"
    private const val CANVAS_PROMPT = "帮我在设计创意画布里做一张活动海报"
    private const val PLAYBOOK_PROMPT = "用这个案例帮我做一个同款"

    /** LOCAL 专家任务（固定专家 id，参照实现实测值）。 */
    private val LOCAL_EXPERTS = mapOf(
        "Expert_lighthouse" to Expert("ex_2cvvUZQhDyeJ", "腾讯轻量云专家", "1.0.2", "腾讯轻量云专家"),
        "WorkBuddy_Valorant" to Expert("ex_xQHHD1u5Jkua", "无畏契约拼豆专家", "1.2.1", "无畏契约拼豆专家"),
        "expert_use_maidehao" to Expert("ex_GDl31WahyIH0", "卖得好", "1.0.1", "中国电商运营专家"),
        "expert_use_douyinyin" to Expert("ex_FbY5nlWTqDod", "斗音音", "1.0.2", "抖音策略师"),
        "expert_use_shangjia_xuanchuan" to
            Expert("ex_Weo3x2EZuSDm", "商家宣传设计师", "1.6.0", "商家宣传页与海报设计师"),
        "expert_use_bowangwang" to Expert("ex_WZY136wV5aMt", "播旺旺", "1.0.2", "直播电商教练"),
    )

    internal data class Expert(
        val id: String,
        val name: String,
        val version: String,
        val profession: String,
    ) {
        val prompt: String get() = "你好，请简单介绍一下$profession，回答OK即可"
    }

    // ------------------------------------------------------------------ 标识

    /** 官方会话 id：UUID v4。 */
    fun newConversationId(): String = UUID.randomUUID().toString()

    /** 官方消息/请求 id：前 12 位是毫秒时间戳，后 20 位随机 hex。 */
    fun newMessageId(nowMillis: Long): String =
        "%012x".format(nowMillis) + UUID.randomUUID().toString().replace("-", "").take(20)

    /**
     * 由 uid 稳定派生 36 位 hex 设备标识（`sha256("<salt>:<uid>")` 前 36 位）。
     *
     * 上游按 machineId / sessionId / qimei36 识别设备与去重活跃：随机值会被当成新设备，
     * 多账号共用同一组值又会被判成同一台机器，所以必须「按账号稳定派生」。
     */
    fun derive(uid: String, salt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$salt:$uid".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(36)

    // ------------------------------------------------------------------ 指纹

    /** 公共桌面指纹：铺在每个上报事件的底层。 */
    fun fingerprint(uid: String, nickname: String, nowMillis: Long): JsonObject = JsonObject().apply {
        addProperty("timezone", "Asia/Shanghai")
        addProperty("reportDelay", 2000)
        addProperty("userId", uid)
        addProperty("username", nickname)
        addProperty("userNickname", nickname)
        addProperty("product", "SaaS")
        addProperty("releaseDate", DESKTOP_RELEASE_DATE)
        addProperty("commit", DESKTOP_COMMIT)
        addProperty("qimei36", derive(uid, "qimei"))
        addProperty("ideName", "WorkBuddy")
        addProperty("ideType", "WorkBuddy")
        addProperty("ideVersion", DESKTOP_VERSION)
        addProperty("machineId", derive(uid, "machine"))
        addProperty("sessionId", derive(uid, "session"))
        addProperty("extName", "workbuddy-desktop")
        addProperty("extVersion", DESKTOP_VERSION)
        addProperty("os", "win32")
        addProperty("arch", "x64")
        addProperty("osVersion", "10.0.28000")
        addProperty("cpuModel", CPU_MODEL)
        addProperty("cpuCores", 8)
        addProperty("memorySize", 16)
        addProperty("timestamp", nowMillis)
        addProperty("presentAt", nowMillis)
    }

    /** 业务字段铺在指纹上（同名时业务字段优先，与参照实现一致）。 */
    fun envelope(ctx: GrowthContext, event: GrowthEvent): JsonObject {
        val out = fingerprint(ctx.uid, ctx.nickname, ctx.nowMillis)
        event.json.entrySet().forEach { (key, value) -> out.add(key, value) }
        return out
    }

    // ------------------------------------------------------------------ 配方

    /** 该 task_code 的完成配方；没有就是「暂不支持自动完成」。 */
    fun recipeFor(code: String): GrowthRecipe? = when (code) {
        // 对话类：先真发一次对话，再用它的会话 id 上报，否则不计进度
        "chat_5" -> {
            val prompt = { round: Int -> CHAT_PROMPTS[round % CHAT_PROMPTS.size] }
            GrowthRecipe(
                label = "和AI聊天5次",
                maxRounds = CHAT_PROMPTS.size,
                chatFor = { round -> prompt(round) },
                events = { ctx, round, session -> chatEvents(ctx, prompt(round), session) },
            )
        }

        "Model_chat_GLM5.2" -> {
            val prompt = "你好，请介绍一下你自己"
            GrowthRecipe(
                label = "GLM-5.2模型对话",
                chatFor = { prompt },
                events = { ctx, _, session -> chatEvents(ctx, prompt, session) },
            )
        }

        // 「夜猫子」只在 CST 23:00-08:00 计数，窗口外报多少都不算
        "black_cat" -> {
            val prompt = "聊天"
            GrowthRecipe(
                label = "夜猫子活动",
                nightOnly = true,
                chatFor = { prompt },
                events = { ctx, _, session -> chatEvents(ctx, prompt, session, mode = "night") },
            )
        }

        // 桌面端对话：官方换血流程走不通时的 6 连事件口径
        "RichMeow_Chat" -> GrowthRecipe(label = "桌面端对话") { ctx, _, _ ->
            desktopChatSequence(ctx, newConversationId(), newMessageId(ctx.nowMillis), newMessageId(ctx.nowMillis))
        }

        "skill_1" -> GrowthRecipe(
            label = "尝鲜热门技能",
            chatFor = { SKILL_PROMPT },
            events = { ctx, _, session -> skillEvents(ctx, session) },
        )

        "template_5" -> GrowthRecipe(label = "使用5个模板", maxRounds = TEMPLATE_SCENES.size) { ctx, round, _ ->
            val (id, name) = TEMPLATE_SCENES[round % TEMPLATE_SCENES.size]
            templateEvents(ctx, id, name)
        }

        "expert_5" -> GrowthRecipe(label = "召唤5次专家", maxRounds = 5) { ctx, round, _ ->
            expertEvents(ctx, round)
        }

        "automation_1" -> GrowthRecipe(label = "设置自动化任务") { ctx, _, _ -> automationEvents(ctx) }

        "create_canvas" -> GrowthRecipe(
            label = "设计创意模式",
            chatFor = { CANVAS_PROMPT },
            events = { ctx, _, session -> canvasEvents(ctx, session) },
        )

        "playbook_prompt" -> GrowthRecipe(
            label = "探索优秀灵感",
            chatFor = { PLAYBOOK_PROMPT },
            events = { ctx, _, session -> playbookEvents(ctx, session) },
        )

        "Buddy_App" -> GrowthRecipe(label = "发现应用") { ctx, _, _ ->
            buddyAppSequence(ctx, DEFAULT_BUDDY_ID, "发现应用")
        }

        "Buddy_App_QQ" -> GrowthRecipe(label = "企鹅教师助手") { ctx, _, _ ->
            buddyAppSequence(ctx, QQ_BUDDY_ID, "企鹅教师助手")
        }

        else -> LOCAL_EXPERTS[code]?.let { expert ->
            GrowthRecipe(
                label = expert.profession,
                chatFor = { expert.prompt },
                events = { ctx, _, session -> localExpertEvents(ctx, expert, session) },
            )
        }
    }

    // ------------------------------------------------------------------ 事件组

    /**
     * 对话类任务的埋点（`chat_request_send` / `chat_request_response` / `chat_message_send`）。
     *
     * 这三条自带 `ideName=web-Agents`：参照实现里它们覆盖掉信封的同名键，
     * 表示「这次对话来自网页 Agents 入口」，照抄以免上游按桌面入口归错进度。
     * `mode` 必须写在 `ideName` 之后：参照实现是 `**common` 之后才覆盖 `mode`，
     * 反过来会被信封的 `CLOUD` 吃掉，夜猫子的 `night` 就丢了。
     */
    private fun chatEvents(
        ctx: GrowthContext,
        prompt: String,
        session: GrowthSession?,
        mode: String = "craft",
    ): List<JsonObject> {
        val reply = "好的，已经为你处理完成。"
        val convId = session?.conversationId ?: newConversationId()
        val requestId = "cmb-" + UUID.randomUUID()
        val now = ctx.nowMillis
        fun common(event: GrowthEvent): GrowthEvent = event
            .str("userId", ctx.uid)
            .str("userNickname", ctx.nickname)
            .str("ideName", "web-Agents")
            .str("ideType", "web-Agents")
            .str("machineId", derive(ctx.uid, "machine"))
            .num("timestamp", now)
            .num("reportDelay", 0)
            .str("userAgent", DESKTOP_UA)
            .str("os", "Win32")
            .str("timezone", "Asia/Shanghai")

        return listOf(
            envelope(
                ctx,
                common(
                    GrowthEvent("chat_request_send")
                        .str("conversationId", convId)
                        .str("requestId", requestId)
                        .str("requestModelId", GROWTH_CHAT_MODEL)
                        .str("requestModelName", "GLM-5.2")
                        .num("inputLength", prompt.length)
                        .str("customAgentName", ""),
                ).str("mode", mode),
            ),
            envelope(
                ctx,
                common(
                    GrowthEvent("chat_request_response")
                        .str("conversationId", convId)
                        .str("requestId", requestId)
                        .str("requestModelId", GROWTH_CHAT_MODEL)
                        .str("requestModelName", "GLM-5.2")
                        .num("toolCallCount", 0)
                        .num("inputToken", maxOf(1, prompt.length / 4))
                        .num("outputToken", maxOf(1, reply.length / 4))
                        .num("totalToken", maxOf(2, (prompt.length + reply.length) / 4)),
                ),
            ),
            envelope(
                ctx,
                common(
                    GrowthEvent("chat_message_send")
                        .str("conversationId", convId)
                        .str("requestId", requestId)
                        .str("messageId", "cmb-" + UUID.randomUUID())
                        .str("requestModelId", GROWTH_CHAT_MODEL)
                        .str("requestModelName", "GLM-5.2")
                        .num("historyCount", 1)
                        .bool("isContextTruncated", false)
                        .num("currentStepCount", 1)
                        .str("traceId", requestId)
                        .str("rootRequestId", requestId)
                        .str("parentConversationId", convId)
                        .str("agentName", "cli")
                        .str("agentType", "main"),
                ),
            ),
        )
    }

    private val CHAT_PROMPTS = listOf(
        "你好",
        "今天天气怎么样？",
        "1+1等于几？",
        "Python是什么？",
        "推荐一本好书",
    )

    /**
     * 桌面端成功对话的 6 连事件（点亮「桌面端对话」，也是技能 / 画布 / 专家任务的事件底座）。
     */
    private fun desktopChatSequence(
        ctx: GrowthContext,
        conversationId: String,
        requestId: String,
        messageId: String,
        finishReason: String = "stop",
    ): List<JsonObject> {
        val assistantId = "$messageId-assistant"
        return listOf(
            GrowthEvent("agent_task_created")
                .str("source", "LOCAL")
                .str("name", "working")
                .str("task_target", "local")
                .str("mode", "craft")
                .str("requestModelId", DESKTOP_MODEL)
                .str("requestModelName", DESKTOP_MODEL)
                .bool("has_repo", false)
                .str("repo_type", "none")
                .str("workspace_type", "empty")
                .bool("has_connector", false)
                .arr("connector_types")
                .bool("has_mention", false)
                .arr("mention_types")
                .bool("has_template", false)
                .str("action", "")
                .str("template_name", "")
                .bool("has_expert", false)
                .str("expert_id", "")
                .str("expert_name", "")
                .str("expert_industry_id", "")
                .bool("has_skill", false)
                .arr("skill_names")
                .str("conversationId", conversationId)
                .str("messageId", messageId)
                .str("buddyId", "")
                .str("buddyName", ""),
            GrowthEvent("chat_message_send")
                .str("messageId", assistantId)
                .num("historyCount", 0)
                .bool("isContextTruncated", false)
                .num("currentStepCount", 1)
                .str("traceId", requestId)
                .str("rootRequestId", requestId)
                .str("parentConversationId", conversationId)
                .str("agentName", "cli")
                .str("agentType", "main"),
            GrowthEvent("chat_request_send")
                .num("inputLength", 24)
                .bool("isPlan", false)
                .bool("isAutoExecuteTerminal", false)
                .bool("isAutoModify", false)
                .bool("codebaseEnable", false)
                .num("maxToken", 0)
                .num("maxSteps", 500)
                .num("temperature", 0)
                .num("maxRetries", 0)
                .arr("mentionContexts")
                .arr("knowledgeId")
                .arr("knowledgeName")
                .str("codebaseId", "")
                .num("mentionContextCount", 0)
                .str("command", "")
                .str("recommendId", "")
                .str("skillId", "")
                .num("skillCount", 0)
                .num("totalCount", 0)
                .str("traceId", requestId)
                .str("rootRequestId", requestId)
                .str("parentConversationId", conversationId)
                .str("agentName", "cli")
                .str("agentType", "main"),
            GrowthEvent("chat_message_response")
                .str("messageId", assistantId)
                .str("responseModelId", DESKTOP_MODEL)
                .num("inputToken", 120)
                .num("outputToken", 80)
                .num("totalToken", 200)
                .num("cachedTokens", 0)
                .num("cachedWriteTokens", 0)
                .num("cachedMissTokens", 0)
                .bool("isSuccessful", true)
                .str("messageErrorCode", "")
                .str("finishReason", finishReason)
                .num("firstTokenAt", ctx.nowMillis)
                .str("traceId", requestId)
                .str("conversationId", conversationId)
                .str("rootRequestId", requestId)
                .str("parentConversationId", conversationId)
                .str("agentName", "cli")
                .str("agentType", "main"),
            GrowthEvent("chat_message_status")
                .str("messageId", assistantId)
                .str("messageErrorCode", "0")
                .str("traceId", requestId)
                .str("rootRequestId", requestId)
                .str("parentConversationId", conversationId)
                .str("agentName", "cli")
                .str("agentType", "main"),
            GrowthEvent("chat_request_response")
                .str("mode", "craft")
                .num("toolCallCount", 0)
                .num("inputToken", 120)
                .num("outputToken", 80)
                .num("totalToken", 200)
                .num("cachedTokens", 0)
                .num("cachedWriteTokens", 0)
                .num("cachedMissTokens", 0)
                .bool("isSuccessful", true)
                .str("messageErrorCode", "")
                .str("finishReason", "stop")
                .str("rootRequestId", requestId)
                .str("parentConversationId", conversationId)
                .str("agentName", "cli")
                .str("agentType", "main"),
        ).map { envelope(ctx, it) }
    }

    /** 尝鲜热门技能：桌面 6 连（finishReason=tool_calls）+ 技能加载事件，共用同一组会话 id。 */
    private fun skillEvents(ctx: GrowthContext, session: GrowthSession?): List<JsonObject> {
        val conversationId = session?.conversationId ?: newConversationId()
        val messageId = session?.messageId ?: newMessageId(ctx.nowMillis)
        val chain = desktopChatSequence(
            ctx = ctx,
            conversationId = conversationId,
            requestId = messageId,
            messageId = messageId,
            finishReason = "tool_calls",
        )
        return chain + envelope(
            ctx,
            GrowthEvent("skill_info")
                .str("id", SKILL_FALLBACK_NAME)
                .str("skillId", SKILL_FALLBACK_ID)
                .str("skillVersion", "1.0.0")
                .str("toolStatus", "success")
                .num("fileCount", 56)
                .str("source", "workbuddy-desktop")
                .str("conversationId", conversationId)
                .str("requestId", messageId)
                .str("messageId", messageId)
                .str("traceId", messageId)
                .str("requestModelId", DESKTOP_MODEL)
                .str("requestModelName", DESKTOP_MODEL),
        )
    }

    /** 使用模板：一组 4 条事件算 1/5 进度。 */
    private fun templateEvents(ctx: GrowthContext, id: String, name: String): List<JsonObject> {
        val requestId = newMessageId(ctx.nowMillis)
        return listOf(
            GrowthEvent("agent_task_created")
                .str("source", "CLOUD")
                .str("name", "")
                .str("mode", "craft")
                .str("requestModelId", "default")
                .str("action", id)
                .bool("has_template", true)
                .str("template_id", id)
                .str("template_name", name)
                .str("requestId", requestId),
            GrowthEvent("agent_task_created_with_template")
                .str("templateId", id)
                .str("templateName", name)
                .str("template_id", id)
                .str("mode", "working")
                .bool("isCustomModel", true)
                .str("id", id)
                .str("name", name)
                .str("requestId", requestId),
            GrowthEvent("template_used")
                .str("template_id", id)
                .str("templateId", id)
                .str("templateName", name)
                .str("task_mode", "working")
                .str("id", id)
                .str("name", name)
                .str("source", "growth-center"),
            GrowthEvent("playbook_prompt_send")
                .str("ext1", UUID.randomUUID().toString())
                .str("requestId", requestId)
                .str("id", id)
                .str("name", name)
                .str("type", "other")
                .num("promptLength", 30)
                .num("isOfficial", 1)
                .str("source", "growth-center"),
        ).map { envelope(ctx, it) }
    }

    /**
     * 召唤专家：`expert_summoned` + `expert_actual_use` 两条算一次。
     *
     * 专家 id 用按账号稳定派生的合成值——参照实现取不到专家市场时同样兜底合成。
     */
    private fun expertEvents(ctx: GrowthContext, round: Int): List<JsonObject> {
        val id = "expert-" + derive(ctx.uid, "expert$round").take(8)
        val name = "专家${round + 1}"
        val requestId = newMessageId(ctx.nowMillis)
        return listOf(
            GrowthEvent("expert_summoned")
                .str("id", id)
                .str("name", name)
                .str("type", "agent")
                .str("expertTitle", "")
                .str("expertType", "agent"),
            GrowthEvent("expert_actual_use")
                .str("id", id)
                .str("name", name)
                .str("type", "")
                .str("expertType", "agent")
                .str("source", "builtin")
                .str("version", "")
                .num("cost", 0)
                .num("characterCount", 12)
                .str("conversationId", newConversationId())
                .str("requestId", requestId)
                .str("messageId", "msg-" + requestId.takeLast(8))
                .str("requestModelId", EXPERT_MODEL_ID)
                .str("requestModelName", EXPERT_MODEL_NAME),
        ).map { envelope(ctx, it) }
    }

    /** 设置自动化任务：创建 + 调度 + 执行三条事件。 */
    private fun automationEvents(ctx: GrowthContext): List<JsonObject> {
        val schedule = JsonObject().apply {
            addProperty("type", "recurring")
            addProperty("rrule", "FREQ=WEEKLY;BYDAY=FR;BYHOUR=9;BYMINUTE=0")
        }
        return listOf(
            GrowthEvent("agent_task_created")
                .str("source", "CLOUD")
                .str("name", "")
                .str("mode", "craft")
                .str("requestModelId", "default")
                .str("task_mode", "automation")
                .bool("isAutomationBackground", true),
            GrowthEvent("automated_task_create_suc")
                .str("action", "create")
                .str("name", "每周五自动生成周报")
                .str("source", "manually")
                .str("modelId", "deepseek-v4-flash")
                .bool("modelIsThinking", false)
                .str("expertId", "")
                .str("expertMarketplace", "")
                .str("connectorIds", "")
                .num("connectorCount", 0)
                .str("skills", "")
                .num("skillCount", 0)
                .str("scheduleType", "recurring")
                .bool("pushToWeChat", false)
                .bool("pushToWecomBot", false)
                .sub("schedule", schedule)
                .str("prompt", "每周五自动整理本周工作，生成一份周报。"),
            GrowthEvent("automated_task_execute").str("action", "execute"),
        ).map { envelope(ctx, it) }
    }

    /**
     * 设计创意模式。
     *
     * 有真实会话：桌面 6 连 + 建画布 / 打开画布（与官方 task-auto 的形状一致）；
     * 没有则退回参照实现的降级口径（创建任务 + 建画布两条裸事件）。
     */
    private fun canvasEvents(ctx: GrowthContext, session: GrowthSession?): List<JsonObject> {
        val canvasId = "wbx-canvas-${ctx.nowMillis}"
        if (session == null) {
            return listOf(
                GrowthEvent("agent_task_created")
                    .str("source", "CLOUD")
                    .str("name", "")
                    .str("mode", "craft")
                    .str("requestModelId", "default")
                    .str("task_mode", "design"),
                GrowthEvent("wbx_design_canvas_task_create"),
            ).map { envelope(ctx, it) }
        }
        return desktopChatSequence(ctx, session.conversationId, session.messageId, session.messageId) +
            listOf(
                GrowthEvent("wbx_design_canvas_task_create")
                    .str("conversationId", session.conversationId)
                    .str("requestId", session.messageId)
                    .str("source", "summon_keyword")
                    .bool("isCustomModel", false)
                    .str("name", "")
                    .num("inputLength", CANVAS_PROMPT.length)
                    .str("id", canvasId)
                    .num("cost", 0)
                    .bool("isSuccessful", true),
                GrowthEvent("wbx_design_canvas_open")
                    .str("conversationId", session.conversationId)
                    .str("requestId", session.messageId)
                    .str("id", "ardot-file-" + session.messageId.takeLast(8))
                    .str("source", "summon_keyword")
                    .str("type", "page")
                    .num("cost", 13000)
                    .bool("isSuccessful", true),
            ).map { envelope(ctx, it) }
    }

    /**
     * 探索优秀灵感。
     *
     * 有真实会话：桌面 6 连 + 点击/发起三条事件；没有则退回单条 `playbook_prompt_send`。
     */
    private fun playbookEvents(ctx: GrowthContext, session: GrowthSession?): List<JsonObject> {
        if (session == null) {
            return listOf(
                envelope(
                    ctx,
                    GrowthEvent("playbook_prompt_send")
                        .str("ext1", UUID.randomUUID().toString())
                        .str("requestId", UUID.randomUUID().toString())
                        .str("id", PLAYBOOK_ID)
                        .str("name", PLAYBOOK_NAME)
                        .str("type", "other")
                        .num("promptLength", 30)
                        .num("isOfficial", 1)
                        .str("source", "growth-center"),
                ),
            )
        }
        return desktopChatSequence(ctx, session.conversationId, session.messageId, session.messageId) +
            listOf(
                GrowthEvent("web_element_click")
                    .str("pageName", "playbook_detail")
                    .str("elementId", "playbook_ctaClick")
                    .str("elementName", PLAYBOOK_NAME)
                    .str("source", "discover"),
                GrowthEvent("playbook_cta_click")
                    .str("id", PLAYBOOK_ID)
                    .str("name", PLAYBOOK_NAME)
                    .str("type", "document")
                    .str("categoryId", "")
                    .str("categoryName", "")
                    .str("source", "discover")
                    .num("position", 0),
                GrowthEvent("playbook_prompt_send")
                    .str("id", PLAYBOOK_ID)
                    .str("name", PLAYBOOK_NAME)
                    .str("type", "document")
                    .str("categoryId", "")
                    .str("categoryName", "")
                    .str("conversationId", session.conversationId)
                    .str("requestId", session.messageId)
                    .num("promptLength", PLAYBOOK_PROMPT.length)
                    .num("isOfficial", 1)
                    .str("skills", "")
                    .str("skillNames", "")
                    .str("expertId", "")
                    .str("expertName", "")
                    .str("query", "")
                    .str("source", "discover")
                    .str("ext1", "discover"),
            ).map { envelope(ctx, it) }
    }

    /** 进入 Buddy 应用的 5 连事件（发现应用 / 企鹅教师助手）。 */
    private fun buddyAppSequence(ctx: GrowthContext, buddyId: String, buddyName: String): List<JsonObject> {
        fun event(code: String): GrowthEvent = GrowthEvent(code)
            .str("mode", "LOCAL")
            .str("buddyId", buddyId)
            .str("buddyName", buddyName)

        val events = listOf(
            event("buddyapp_discover_click"),
            event("buddyapp_show").str("elementId", buddyId).str("elementName", buddyName).num("position", 2),
            event("buddyapp_enter_click")
                .str("elementId", buddyId)
                .str("elementName", buddyName)
                .num("position", 2)
                .str("isFirstPage", "1"),
            event("buddyapp_auth_confirm_click").str("elementId", buddyId).str("elementName", buddyName),
            event("buddyapp_bindaccount_skip_click").str("elementId", buddyId).str("elementName", buddyName),
        )
        return events.map { envelope(ctx, it) }
    }

    /**
     * LOCAL 专家任务（轻量云专家 / 无畏契约 / 四个 expert_use_*）。
     *
     * 有真实会话：召唤两条 + 桌面 6 连（标注 `has_expert`）+ 实际使用，与官方一致；
     * 没有则退回参照实现的两条事件降级口径（会话 id 现生成）。
     */
    private fun localExpertEvents(
        ctx: GrowthContext,
        expert: Expert,
        session: GrowthSession?,
    ): List<JsonObject> {
        if (session == null) {
            val requestId = newMessageId(ctx.nowMillis)
            return listOf(
                GrowthEvent("expert_summoned")
                    .str("id", expert.id)
                    .str("name", expert.name)
                    .str("type", "agent")
                    .str("expertTitle", expert.profession)
                    .str("expertType", "agent")
                    .str("source", "builtin")
                    .num("timestamp", ctx.nowMillis),
                GrowthEvent("expert_actual_use")
                    .str("id", expert.id)
                    .str("name", expert.name)
                    .str("expertTitle", expert.profession)
                    .str("type", "")
                    .str("expertType", "agent")
                    .str("source", "builtin")
                    .str("version", expert.version)
                    .num("cost", 0)
                    .num("characterCount", expert.prompt.length)
                    .str("mode", "LOCAL")
                    .str("conversationId", newConversationId())
                    .str("requestId", requestId)
                    .str("messageId", requestId)
                    .str("requestModelId", DESKTOP_MODEL)
                    .str("requestModelName", DESKTOP_MODEL)
                    .str("userId", ctx.uid),
            ).map { envelope(ctx, it) }
        }

        val summon = listOf(
            GrowthEvent("expert_summon_click")
                .str("id", expert.id)
                .str("name", expert.name)
                .str("expertTitle", expert.profession)
                .str("type", "agent")
                .str("expertType", "agent")
                .str("source", "builtin")
                .str("mode", "LOCAL"),
            GrowthEvent("expert_summoned")
                .str("id", expert.id)
                .str("name", expert.name)
                .str("expertTitle", expert.profession)
                .str("type", "agent")
                .str("expertType", "agent")
                .str("source", "builtin")
                .str("version", expert.version)
                .str("mode", "LOCAL"),
        )
        // 桌面 6 连里的 agent_task_created 要标注「这次对话带专家」，否则上游按普通对话归因
        val chain = desktopChatSequence(ctx, session.conversationId, session.messageId, session.messageId)
            .onEach { event ->
                if (event.get("eventCode")?.asString == "agent_task_created") {
                    event.addProperty("has_expert", true)
                    event.addProperty("expert_id", expert.id)
                    event.addProperty("expert_name", expert.name)
                    event.addProperty("expert_industry_id", "")
                }
            }
        val use = GrowthEvent("expert_actual_use")
            .str("id", expert.id)
            .str("name", expert.name)
            .str("expertTitle", expert.profession)
            .str("type", "")
            .str("expertType", "agent")
            .str("source", "builtin")
            .str("version", expert.version)
            .num("cost", 0)
            .num("characterCount", expert.prompt.length)
            .str("mode", "LOCAL")
            .str("conversationId", session.conversationId)
            .str("requestId", session.messageId)
            .str("messageId", session.messageId)
            .str("requestModelId", DESKTOP_MODEL)
            .str("requestModelName", DESKTOP_MODEL)
        return summon.map { envelope(ctx, it) } + chain + envelope(ctx, use)
    }
}