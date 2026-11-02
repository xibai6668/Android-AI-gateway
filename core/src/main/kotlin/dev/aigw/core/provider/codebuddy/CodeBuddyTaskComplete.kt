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
 * 一个成长任务的完成配方。
 *
 * [events] 每调用一次生成**一轮**全新事件（id 与时间戳都不同），[round] 从 0 递增；
 * 进度缺口比 [maxRounds] 大时按缺口来，不会为了凑数白打上游。
 */
internal data class GrowthRecipe(
    val label: String,
    val maxRounds: Int = 1,
    /** 对话类任务必须先真发一次对话，埋点才会被计入。 */
    val needsChat: Boolean = false,
    /** 只在 CST 23:00-次日 08:00 计数（夜猫子活动）。 */
    val nightOnly: Boolean = false,
    val events: (GrowthContext, Int) -> List<JsonObject>,
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
 * `t_canvas_automation` / `t_buddy_apps` / `_run_local_expert_task`。
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

    private val PLAYBOOK = Triple("01-ProductDesign", "产品设计", "document")

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
    )

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
        // 对话类：必须先真发一次对话，埋点才会被计入
        "chat_5" -> GrowthRecipe(
            label = "和AI聊天5次",
            maxRounds = 5,
            needsChat = true,
            events = { ctx, round -> chatEvents(ctx, round) },
        )

        "Model_chat_GLM5.2" -> GrowthRecipe(
            label = "GLM-5.2模型对话",
            needsChat = true,
            events = { ctx, _ -> chatEvents(ctx, 0) },
        )

        // 「夜猫子」只在 CST 23:00-08:00 计数，窗口外报多少都不算
        "black_cat" -> GrowthRecipe(
            label = "夜猫子活动",
            needsChat = true,
            nightOnly = true,
            events = { ctx, _ -> chatEvents(ctx, 0, mode = "night") },
        )

        // 桌面端任务：桌面 6 连事件即可（官方客户端换血对话的降级口径）
        "RichMeow_Chat" -> GrowthRecipe(label = "桌面端对话") { ctx, _ ->
            desktopChatSequence(ctx.uid, ctx.nickname, ctx.nowMillis)
        }

        "skill_1" -> GrowthRecipe(label = "尝鲜热门技能") { ctx, _ ->
            val convId = newConversationId()
            val requestId = newMessageId(ctx.nowMillis)
            desktopChatSequence(ctx.uid, ctx.nickname, ctx.nowMillis, convId, requestId, finishReason = "tool_calls") +
                envelope(
                    ctx,
                    GrowthEvent("skill_info")
                        .str("id", SKILL_FALLBACK_NAME)
                        .str("skillId", SKILL_FALLBACK_ID)
                        .str("skillVersion", "1.0.0")
                        .str("toolStatus", "success")
                        .num("fileCount", 56)
                        .str("source", "workbuddy-desktop")
                        .str("conversationId", convId)
                        .str("requestId", requestId)
                        .str("messageId", requestId)
                        .str("traceId", requestId)
                        .str("requestModelId", DESKTOP_MODEL)
                        .str("requestModelName", DESKTOP_MODEL),
                )
        }

        "template_5" -> GrowthRecipe(label = "使用5个模板", maxRounds = TEMPLATE_SCENES.size) { ctx, round ->
            val (id, name) = TEMPLATE_SCENES[round % TEMPLATE_SCENES.size]
            templateEvents(ctx, id, name)
        }

        "expert_5" -> GrowthRecipe(label = "召唤5次专家", maxRounds = 5) { ctx, round ->
            expertEvents(ctx, round)
        }

        "automation_1" -> GrowthRecipe(label = "设置自动化任务") { ctx, _ -> automationEvents(ctx) }

        "create_canvas" -> GrowthRecipe(label = "设计创意模式") { ctx, _ -> canvasEvents(ctx) }

        "playbook_prompt" -> GrowthRecipe(label = "探索优秀灵感") { ctx, _ -> playbookEvents(ctx) }

        "Buddy_App" -> GrowthRecipe(label = "发现应用") { ctx, _ ->
            buddyAppSequence(ctx, DEFAULT_BUDDY_ID, "发现应用")
        }

        "Buddy_App_QQ" -> GrowthRecipe(label = "企鹅教师助手") { ctx, _ ->
            buddyAppSequence(ctx, QQ_BUDDY_ID, "企鹅教师助手")
        }

        else -> LOCAL_EXPERTS[code]?.let { expert ->
            GrowthRecipe(label = expert.profession) { ctx, _ -> localExpertEvents(ctx, expert) }
        }
    }

    // ------------------------------------------------------------------ 事件组

    /**
     * 对话类任务的埋点（`chat_request_send` / `chat_request_response` / `chat_message_send`）。
     *
     * 这三条自带 `ideName=web-Agents`：参照实现里它们覆盖掉信封的同名键，
     * 表示「这次对话来自网页 Agents 入口」，照抄以免上游按桌面入口归错进度。
     */
    private fun chatEvents(ctx: GrowthContext, round: Int, mode: String = "craft"): List<JsonObject> {
        val prompt = CHAT_PROMPTS[round % CHAT_PROMPTS.size]
        val reply = "好的，已经为你处理完成。"
        val convId = newConversationId()
        val requestId = "cmb-" + UUID.randomUUID()
        val now = ctx.nowMillis
        fun common(event: GrowthEvent): GrowthEvent = event
            .str("userId", ctx.uid)
            .str("userNickname", ctx.nickname)
            .str("ideName", "web-Agents")
            .str("ideType", "web-Agents")
            .str("machineId", derive(ctx.uid, "machine"))
            .str("mode", "CLOUD")
            .str("userAgent", DESKTOP_UA)
            .str("os", "Win32")
            .str("timezone", "Asia/Shanghai")

        return listOf(
            envelope(
                ctx,
                common(
                    GrowthEvent("chat_request_send")
                        .num("timestamp", now)
                        .num("reportDelay", 0)
                        .str("mode", mode)
                        .str("conversationId", convId)
                        .str("requestId", requestId)
                        .str("requestModelId", GROWTH_CHAT_MODEL)
                        .str("requestModelName", "GLM-5.2")
                        .num("inputLength", prompt.length)
                        .str("customAgentName", ""),
                ),
            ),
            envelope(
                ctx,
                common(
                    GrowthEvent("chat_request_response")
                        .num("timestamp", now + 100)
                        .num("reportDelay", 0)
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
                        .num("timestamp", now + 50)
                        .num("reportDelay", 0)
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

    /** 对话类任务发的真实内容：每次换一条短问，与参照实现同款。 */
    fun chatPrompt(round: Int): String = CHAT_PROMPTS[round % CHAT_PROMPTS.size]

    private val CHAT_PROMPTS = listOf(
        "你好",
        "今天天气怎么样？",
        "1+1等于几？",
        "Python是什么？",
        "推荐一本好书",
    )

    /**
     * 桌面端成功对话的 6 连事件（点亮「桌面端对话」，也是技能类任务的底座）。
     *
     * 会话/请求/消息 id 都取自真实对话的**服务端 id**；官方换血流程走不通时参照实现
     * 用现生成的 id 兜底，这里沿用兜底口径。
     */
    private fun desktopChatSequence(
        uid: String,
        nickname: String,
        nowMillis: Long,
        conversationId: String = newConversationId(),
        requestId: String = newMessageId(nowMillis),
        messageId: String = newMessageId(nowMillis),
        finishReason: String = "stop",
    ): List<JsonObject> {
        val ctx = GrowthContext(uid, nickname, nowMillis)
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
                .num("firstTokenAt", nowMillis)
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
     * 专家 id 用按账号稳定派生的合成值——参照实现取不到专家市场时同样兜底合成，
     * 上游对这两条事件不校验专家真实性。
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
                .str("requestModelId", DESKTOP_MODEL)
                .str("requestModelName", DESKTOP_MODEL),
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

    /** 设计创意模式：走不通真实画布时的降级口径（创建任务 + 建画布两条）。 */
    private fun canvasEvents(ctx: GrowthContext): List<JsonObject> {
        val canvasId = "wbx-canvas-${ctx.nowMillis}"
        return listOf(
            GrowthEvent("agent_task_created")
                .str("source", "CLOUD")
                .str("name", "")
                .str("mode", "craft")
                .str("requestModelId", "default")
                .str("task_mode", "design"),
            GrowthEvent("wbx_design_canvas_task_create")
                .str("id", canvasId)
                .str("source", "summon_keyword")
                .bool("isCustomModel", false)
                .str("name", "")
                .num("inputLength", 12)
                .num("cost", 0)
                .bool("isSuccessful", true),
        ).map { envelope(ctx, it) }
    }

    /** 探索优秀灵感：官方灵感案例的「做同款」。 */
    private fun playbookEvents(ctx: GrowthContext): List<JsonObject> {
        val (id, name, type) = PLAYBOOK
        return listOf(
            envelope(
                ctx,
                GrowthEvent("playbook_prompt_send")
                    .str("ext1", UUID.randomUUID().toString())
                    .str("requestId", UUID.randomUUID().toString())
                    .str("id", id)
                    .str("name", name)
                    .str("type", type)
                    .num("promptLength", 30)
                    .num("isOfficial", 1)
                    .str("source", "growth-center"),
            ),
        )
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
     * LOCAL 专家任务（轻量云专家 / 无畏契约 / 四个 expert_use_*）的降级口径：
     * 召唤 + 实际使用两条事件，会话 id 现生成（官方换血对话走不通时参照实现也这么做）。
     */
    private fun localExpertEvents(ctx: GrowthContext, expert: Expert): List<JsonObject> {
        val conversationId = newConversationId()
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
                .num("characterCount", 12)
                .str("mode", "LOCAL")
                .str("conversationId", conversationId)
                .str("requestId", requestId)
                .str("messageId", requestId)
                .str("requestModelId", DESKTOP_MODEL)
                .str("requestModelName", DESKTOP_MODEL)
                .str("userId", ctx.uid),
        ).map { envelope(ctx, it) }
    }
}
