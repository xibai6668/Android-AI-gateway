package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.ProviderAccount
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 成长任务「自动完成」链路回归。
 *
 * 上游把任务进度记在客户端埋点上（`POST /v2/report`，裸数组），所以这里锁的是**请求形状**：
 * 哪些任务发哪组事件、接单在前、已达标才领奖、以及没有配方的任务不假装完成。
 *
 * 事件配方与指纹口径照抄 L0NE-6/WorkBuddy-Daily（MIT），见 THIRD-PARTY-NOTICES.md。
 */
class CodeBuddyTaskCompleteTest {

    private val fixedNow = 1_700_000_000_000L // CST 2023-11-15 06:13，落在夜猫子窗口内

    private fun account() = assertNotNull(
        provider("http://placeholder").importCredentials(
            """{"accessToken":"tok","uid":"u1","domain":"www.codebuddy.cn"}""",
        ),
    )

    private fun provider(base: String, now: Long = fixedNow) = CodeBuddyProvider(
        region = { CodeBuddyProvider.REGION_CN },
        nowMillis = { now },
        endpointFor = {
            CodeBuddyProvider.Companion.Endpoint(
                label = "假上游",
                chatBase = base,
                billingBase = base,
                origin = base,
                modelsPath = "/models",
            )
        },
        pause = {},
    )

    /** 假上游：记录写请求，任务列表按 [tasksBody] 回。 */
    private class FakeUpstream {
        val reports = ArrayList<JsonArray>()
        val accepts = ArrayList<JsonArray>()
        val claims = ArrayList<String>()

        /** 每次真实对话请求头里声明的会话 id（埋点必须与之对齐，自造 UUID 上游不认）。 */
        val chatConversationIds = ArrayList<String>()
        var tasksBody = """{"code":0,"data":{"tasks":[]}}"""

        private lateinit var server: HttpServer

        fun start(): String {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v2/report") { exchange ->
                reports.add(JsonParser.parseString(read(exchange)).asJsonArray)
                respond(exchange, """{"code":0}""")
            }
            server.createContext("/v2/activity/growth") { exchange -> growth(exchange) }
            server.createContext("/v2/chat/completions") { exchange ->
                chatConversationIds.add(exchange.requestHeaders.getFirst("X-Conversation-ID").orEmpty())
                read(exchange)
                // 上游把服务端消息 id 放在聚合结果的 id 上
                respond(exchange, """{"code":0,"id":"cmb-server-1","model":"glm-5.2","choices":[]}""")
            }
            server.start()
            return "http://127.0.0.1:${server.address.port}"
        }

        fun stop() = server.stop(0)

        private fun growth(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            when {
                path.endsWith("/tasks") -> respond(exchange, tasksBody)
                path.endsWith("/tasks/accept") -> {
                    accepts.add(JsonParser.parseString(read(exchange)).asJsonObject.getAsJsonArray("task_codes"))
                    val codes = accepts.last().map { it.asString }
                    val results = codes.joinToString(",") { """{"task_code":"$it","status":"accepted"}""" }
                    respond(exchange, """{"code":0,"data":{"results":[$results]}}""")
                }
                path.endsWith("/claim") -> {
                    claims.add(path.removePrefix("/v2/activity/growth/tasks/").removeSuffix("/claim"))
                    respond(exchange, """{"code":0,"data":{"credit":100}}""")
                }
                // 没 Buddy 实例：领养链路整段跳过，测试只关心任务本身
                else -> respond(exchange, """{"code":40004,"msg":"no buddy"}""", 404)
            }
        }
    }

    private fun withUpstream(tasks: String, block: (String, FakeUpstream) -> Unit) {
        val upstream = FakeUpstream().apply { tasksBody = tasks }
        val base = upstream.start()
        try {
            block(base, upstream)
        } finally {
            upstream.stop()
        }
    }

    private fun task(
        code: String,
        title: String,
        current: Int,
        target: Int,
        status: String = "not_accepted",
        locked: Boolean = false,
    ) = """{"task_code":"$code","title":"$title","accept_status":"$status","locked":$locked,""" +
        """"progress":{"current":$current,"target":$target},"reward_credit":100}"""

    private fun tasksBody(vararg tasks: String) =
        """{"code":0,"data":{"tasks":[${tasks.joinToString(",")}]}}"""

    /** 把多次上报里的事件按顺序展开。 */
    private fun FakeUpstream.events(): List<JsonObject> =
        reports.flatMap { array -> array.map { it.asJsonObject } }

    private fun FakeUpstream.codes(): List<String> = events().map { it.get("eventCode").asString }

    private fun FakeUpstream.eventsOf(code: String): List<JsonObject> =
        events().filter { it.get("eventCode").asString == code }

    // ---------------------------------------------------------------- 用例

    @Test
    fun `先接单再推进，最后不领未达标的奖`() = withUpstream(
        tasksBody(
            task("template_5", "使用5个模板", 0, 5),
            task("automation_1", "设置自动化任务", 0, 1),
        ),
    ) { base, upstream ->
        val result = provider(base).performAction(account(), ACTION_TASKS, JsonObject())

        // 先接单：没接单上游不记进度
        assertEquals(1, upstream.accepts.size, "应合并成一次批量接单")
        assertEquals(
            listOf("template_5", "automation_1"),
            upstream.accepts.single().map { it.asString },
        )

        // 进度缺口 5 → 模板事件组发 5 轮；自动化任务 1 轮
        val codes = upstream.reports.flatMap { array -> array.map { it.asJsonObject.get("eventCode").asString } }
        assertEquals(5, codes.count { it == "template_used" }, "模板任务每次进度算一组事件：$codes")
        assertEquals(5, codes.count { it == "agent_task_created_with_template" }, codes.toString())
        assertEquals(1, codes.count { it == "automated_task_create_suc" }, codes.toString())
        assertEquals(1, codes.count { it == "automated_task_execute" }, codes.toString())

        // 进度没达标就不该去领奖
        assertTrue(upstream.claims.isEmpty(), "未达标不该领奖：${upstream.claims}")
        assertTrue(result.ok, result.message)
        assertTrue(result.message.contains("已推进"), result.message)
    }

    @Test
    fun `上报信封带稳定设备指纹`() = withUpstream(tasksBody(task("automation_1", "设置自动化任务", 0, 1))) { base, upstream ->
        provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        val event = upstream.reports.first().first().asJsonObject
        assertEquals(CodeBuddyTaskComplete.derive("u1", "machine"), event.get("machineId").asString)
        assertEquals(CodeBuddyTaskComplete.derive("u1", "session"), event.get("sessionId").asString)
        assertEquals("u1", event.get("userId").asString)
        assertEquals("WorkBuddy", event.get("ideName").asString)
        assertEquals(CodeBuddyTaskComplete.DESKTOP_VERSION, event.get("ideVersion").asString)
    }

    @Test
    fun `进度已达标只领奖不再上报`() = withUpstream(
        tasksBody(task("template_5", "使用5个模板", 5, 5, status = "in_progress")),
    ) { base, upstream ->
        val result = provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        assertTrue(upstream.reports.isEmpty(), "已达标的任务不该再推进：${upstream.reports}")
        assertEquals(listOf("template_5"), upstream.claims)
        assertTrue(result.ok, result.message)
    }

    @Test
    fun `已领取的任务完全不碰`() = withUpstream(
        tasksBody(task("template_5", "使用5个模板", 5, 5, status = "claimed")),
    ) { base, upstream ->
        val result = provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        assertTrue(upstream.reports.isEmpty(), upstream.reports.toString())
        assertTrue(upstream.claims.isEmpty(), upstream.claims.toString())
        assertEquals("成长中心没有可领取的内容", result.message)
    }

    @Test
    fun `没有配方的任务如实点名而不是假装完成`() = withUpstream(
        tasksBody(task("wb_wechat_oa_subscribe_task", "关注公众号", 0, 1)),
    ) { base, upstream ->
        val result = provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        assertTrue(upstream.reports.isEmpty(), upstream.reports.toString())
        assertTrue(result.message.contains("暂不支持自动完成"), result.message)
        assertTrue(result.message.contains("关注公众号"), result.message)
    }

    @Test
    fun `对话类任务先真发对话再上报，且埋点会话与真实会话一致`() = withUpstream(tasksBody(task("chat_5", "和AI聊天5次", 2, 5))) { base, upstream ->
        provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        assertEquals(3, upstream.chatConversationIds.size, "缺口 3 次就该真发 3 次对话")
        assertEquals(3, upstream.codes().count { it == "chat_request_send" }, upstream.codes().toString())
        assertEquals(3, upstream.codes().count { it == "chat_request_response" }, upstream.codes().toString())

        // 关键：埋点里的 conversationId 必须指向刚发出的那次对话（自造 UUID 上游不计进度）
        val reported = upstream.eventsOf("chat_request_send").map { it.get("conversationId").asString }
        assertEquals(upstream.chatConversationIds, reported)
        assertTrue(reported.all { it.isNotEmpty() && it != "00000000-0000-0000-0000-000000000000" })
    }

    @Test
    fun `夜猫子只在窗口内推进`() = withUpstream(
        tasksBody(task("black_cat", "夜猫子活动", 0, 1)),
    ) { base, upstream ->
        // CST 2023-11-15 14:00（UTC 06:00），不在 23:00-08:00 窗口
        val result = provider(base, now = 1_700_028_000_000L)
            .performAction(account(), ACTION_TASKS, JsonObject())
        assertTrue(upstream.reports.isEmpty(), "窗口外不该上报：${upstream.reports}")
        assertTrue(upstream.chatConversationIds.isEmpty())
        assertTrue(result.message.contains("23:00-08:00"), result.message)
    }

    @Test
    fun `夜猫子在一天内的窗口内推进且 mode 为 night`() = withUpstream(
        tasksBody(task("black_cat", "夜猫子活动", 0, 1)),
    ) { base, upstream ->
        val result = provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        val send = upstream.eventsOf("chat_request_send").single()
        assertEquals("night", send.get("mode").asString, "mode 不能被信封的 CLOUD 吃掉")
        assertEquals(upstream.chatConversationIds.single(), send.get("conversationId").asString)
        assertTrue(result.ok, result.message)
    }

    @Test
    fun `召唤专家的事件声明专家模型口径`() = withUpstream(
        tasksBody(task("expert_5", "召唤5次专家", 0, 5)),
    ) { base, upstream ->
        provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        val uses = upstream.eventsOf("expert_actual_use")
        assertEquals(5, uses.size, upstream.codes().toString())
        assertEquals("deepseek-v4-flash", uses.first().get("requestModelId").asString)
        assertEquals("DeepSeek V4 Flash", uses.first().get("requestModelName").asString)
    }

    @Test
    fun `技能类任务用真实会话 id 而非自造值`() = withUpstream(
        tasksBody(task("skill_1", "尝鲜热门技能", 0, 1)),
    ) { base, upstream ->
        provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        val conversationId = upstream.chatConversationIds.single()
        val skill = upstream.eventsOf("skill_info").single()
        assertEquals(conversationId, skill.get("conversationId").asString)
        assertEquals("cmb-server-1", skill.get("messageId").asString, "服务端消息 id 要真实回传")
        val created = upstream.eventsOf("agent_task_created").single()
        assertEquals(conversationId, created.get("conversationId").asString)
    }

    @Test
    fun `指定单条任务时只动这一条`() = withUpstream(
        tasksBody(
            task("template_5", "使用5个模板", 0, 5),
            task("automation_1", "设置自动化任务", 0, 1),
        ),
    ) { base, upstream ->
        val payload = JsonObject().apply { addProperty("task_code", "automation_1") }
        provider(base).performAction(account(), ACTION_TASKS, payload)
        assertEquals(listOf("automation_1"), upstream.accepts.single().map { it.asString })
        assertEquals(1, upstream.reports.size, upstream.reports.toString())
        val codes = upstream.reports.single().map { it.asJsonObject.get("eventCode").asString }
        assertTrue("automated_task_create_suc" in codes, codes.toString())
    }

    @Test
    fun `未解锁的任务不接单也不推进`() = withUpstream(
        tasksBody(task("template_5", "使用5个模板", 0, 5, locked = true)),
    ) { base, upstream ->
        provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        assertTrue(upstream.accepts.isEmpty(), "未解锁的任务连 accept 都别打：${upstream.accepts}")
        assertTrue(upstream.reports.isEmpty(), upstream.reports.toString())
        assertTrue(upstream.claims.isEmpty(), upstream.claims.toString())
    }

    @Test
    fun `国际版与企业账号不进任务流程`() {
        val global = assertNotNull(
            provider("http://placeholder").importCredentials(
                """{"accessToken":"tok","uid":"g1","domain":"workbuddy.ai"}""",
            ),
        )
        val enterprise = assertNotNull(
            provider("http://placeholder").importCredentials(
                """{"accessToken":"tok","uid":"e1","enterpriseId":"ent-1","domain":"www.codebuddy.cn"}""",
            ),
        )
        val p = provider("http://placeholder")
        assertTrue(!p.supportsAction(global, ACTION_TASKS))
        assertTrue(!p.supportsAction(enterprise, ACTION_TASKS))
        val result = p.performAction(global, ACTION_TASKS, JsonObject())
        assertTrue(!result.ok && !result.unsupported, result.message)
    }
}

private fun read(exchange: HttpExchange): String =
    exchange.requestBody.readBytes().toString(Charsets.UTF_8)

private fun respond(exchange: HttpExchange, body: String, status: Int = 200) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
