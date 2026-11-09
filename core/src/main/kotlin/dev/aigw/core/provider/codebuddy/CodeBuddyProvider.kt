package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.ProviderTask
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.provider.ProviderTaskListView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.bodySnippet
import dev.aigw.core.protocol.FALLBACK_CONVERSATION_ID
import dev.aigw.core.protocol.stableConversationId
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.firstLong
import dev.aigw.core.util.firstString
import dev.aigw.core.util.jwtClaim
import dev.aigw.core.util.jwtClaimLong
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 腾讯 WorkBuddy / CodeBuddy（`copilot.tencent.com`，国际版 `workbuddy.ai`）。
 *
 * 登录是设备授权：`POST /v2/plugin/auth/state?platform=CLI` 拿授权链接，
 * 用户在 WebView 里扫码/登录后，`GET /v2/plugin/auth/token?state=…` 轮询拿 token。
 * 对话走 `/v2/chat/completions`（OpenAI 兼容，但**上游拒绝非流式**，必须强制 `stream:true`）。
 */
class CodeBuddyProvider internal constructor(
    private val region: () -> String = { REGION_CN },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hooks: ProviderHooks = ProviderHooks(),
    /** 端点解析：默认按区域取官方端点，测试可指向本地假上游。 */
    private val endpointFor: (String) -> Endpoint = { endpointOf(it) },
    /** 写动作之间的停顿（上游对密集写有风控）；测试里换成空实现。 */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : Provider, DeviceCodeSupport, dev.aigw.core.provider.RegionAwareSupport {

    override val id: String = ID
    override val displayName: String = "WorkBuddy"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CREDIT_REFRESH,
        ProviderCapability.CHECKIN,
        ProviderCapability.TASKS,
    )

    // ------------------------------------------------------------------ 模型

    /**
     * 模型目录**只在登录后**向官方接口拉取。
     *
     * 没有账号时不返回任何模型：拉取不到真实目录就返回空列表，
     * 不能把硬编码清单当成可用模型列出来，否则用户会以为能直接调用，实际一发请求就 401。
     */
    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 没有账号不算「失败」——登录前本就不该有模型，不能报红吓人。
        val credential = account?.let { parse(it) }
            ?: return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "")
        return try {
            val models = fetchModels(credential)
            if (models.isEmpty()) {
                ProviderModelCatalogView(emptyList(), fromFallback = false, error = "上游返回空模型列表")
            } else {
                ProviderModelCatalogView(models, fromFallback = false, error = "")
            }
        } catch (e: Exception) {
            ProviderModelCatalogView(emptyList(), fromFallback = false, error = e.message ?: "拉取模型失败")
        }
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        // 默认走官方的 Auto（id = default-model）；模型列表里首位顺序会随上游变化，不能拿它当默认。
        if (model.isEmpty() || model == "auto") return DEFAULT_MODEL_ID
        return model
    }

    /** 上游域名后缀（代理分流的判定依据）：国内 + 国际两套站点。 */
    override fun hosts(): List<String> =
        regionHosts(REGION_CN) + regionHosts(REGION_GLOBAL)

    /**
     * 按区域分开返回上游域名：国内站与国际站是两套不同站点，代理开关要能分别控制。
     *
     * 国内：插件链路（`copilot.tencent.com` / `codebuddy.cn`）与网页链路（`workbuddy.cn`）；
     * 国际：`workbuddy.ai` / `codebuddy.ai`（旧域名）。
     */
    override fun hosts(region: String?): List<String> = when (region) {
        null -> hosts()
        REGION_GLOBAL -> regionHosts(REGION_GLOBAL)
        else -> regionHosts(REGION_CN)
    }

    private fun regionHosts(region: String): List<String> = if (region == REGION_GLOBAL) {
        listOf("workbuddy.ai", "codebuddy.ai")
    } else {
        listOf("copilot.tencent.com", "codebuddy.cn", "workbuddy.cn")
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        // 已确定过期、且刷新刚刚连续失败：如实报一个**可重试**的错误。
        // 不能带着过期 token 打上游——那会拿到 401，被分类成 SESSION_DEAD 而把账号硬禁用，
        // 一次瞬时刷新失败就变成了用户看到的「登录失效」。连续失败超过上限就不再护着，
        // 让真正的死会话（refresh_token 已被吊销）照常暴露出来。
        if (shouldShieldExpiredCredential(
                expiresAt = credential.expiresAt,
                streak = synchronized(refreshLock) { refreshFailureStreak[account.uid] ?: 0 },
                nowSeconds = nowMillis() / 1000,
            )
        ) {
            return FailedChatCall(503, "凭证已过期且刷新未成功，稍后会自动重试")
        }
        val streaming = isStreamingBody(openAiBody)
        val model = resolveModel(requestedModelOf(openAiBody))
        val request = prepareCodeBuddyRequest(openAiBody)
        val prepared = request.body
        // 工具名被改写过时，响应里的调用名要还原（空表 = 直接透传）
        val restoreNames = request.toolNameRestore

        val conn = try {
            open("${chatBase(credential)}$PATH_CHAT", credential, prepared, chatHeaders(credential, prepared))
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            if (streaming) {
                val body = if (restoreNames.isEmpty()) stream else ToolNameRestoringStream(stream, restoreNames)
                return StreamingChatCall(status, body) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(stream, model)
            } finally {
                conn.disconnect()
            }
            return AggregatedChatCall(status, restoreToolNames(aggregated, restoreNames))
        }

        val text = runCatching { stream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        // 200 也可能是「HTTP 200 + 业务错误码」（与 Loomy 同形）：不校验会把真实错误
        // 当成正常聚合结果，下游只会报「上游返回了空回复」，用户看不到真正原因。
        val businessCode = runCatching { JsonParser.parseString(text).asJsonObject.get("code")?.asLong }
            .getOrNull()
        if (businessCode != null && businessCode != 0L) {
            return StreamFailureChatCall(200, classify(200, text))
        }
        return AggregatedChatCall(status, restoreToolNames(text, restoreNames))
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body)
        val kind = when {
            // 401/403 是凭证失效：必须判 SESSION_DEAD，网关才会禁用该账号并换家。
            // 落到下面的 400..499 → CLIENT 的话会被当作客户端错误早返回，
            // 死账号被反复选中、用户持续收到「请求被上游拒绝」。其余五家都这么映射。
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            // 429 分两种：业务码 6004 是**该模型**超限（换号无用，换模型立刻可用），
            // 其余 429（无业务码/账号级限流）才按可退避的限流处理。
            status == 429 -> if (businessCode(body) == CODE_MODEL_LIMITED) ErrorKind.MODEL_LIMIT else ErrorKind.SOFT_RATE
            status == 404 -> ErrorKind.NOT_FOUND
            status in 500..599 -> ErrorKind.SERVER
            businessCode(body) == CODE_MODEL_LIMITED -> ErrorKind.MODEL_LIMIT
            matchesQuota(body) -> ErrorKind.QUOTA
            matchesSessionDead(body) -> ErrorKind.SESSION_DEAD
            status in 400..499 -> ErrorKind.CLIENT
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message.ifEmpty { "上游 HTTP $status" + bodySnippet(body) })
    }

    /** 取响应体里的业务码；取不到/非数字返回 null。 */
    private fun businessCode(body: String): Long? =
        runCatching { JsonParser.parseString(body).asJsonObject.get("code")?.asLong }.getOrNull()

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = parse(account) ?: return null
        return try {
            val (status, text) = postJson(
                "${billingBase(credential)}$PATH_USER_RESOURCE",
                credential,
                userResourceBody(),
                billingHeaders(credential),
            )
            if (status !in 200..299) {
                return CreditInfo(
                    0,
                    known = false,
                    detail = failure("额度接口", endpoint(credential), "${billingBase(credential)}$PATH_USER_RESOURCE", status, text),
                )
            }
            val data = envelopeData(text) ?: return CreditInfo(0, known = false, detail = "响应缺少 data")
            val accounts = data.objOrNull("Response")?.objOrNull("Data")?.arrayOrNull("Accounts")
                ?: return CreditInfo(0, known = false, detail = "响应缺少 Response.Data.Accounts")
            var remain = 0L
            var fieldSeen = false
            accounts.forEach { element ->
                val item = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
                // 字段缺失（结构改口径）不能当成「额度就是 0」：三个键全缺时视为解析不了，报 known=false
                val size = item.longOrNull("CycleCapacitySize")
                val cycleRemain = item.longOrNull("CycleCapacityRemain")
                val capacityRemain = item.longOrNull("CapacityRemain")
                if (size == null && cycleRemain == null && capacityRemain == null) return@forEach
                fieldSeen = true
                val value = if ((size ?: 0L) > 0 || (cycleRemain ?: 0L) > 0) {
                    cycleRemain ?: 0L
                } else {
                    capacityRemain ?: 0L
                }
                if (value > 0) remain += value
            }
            if (!fieldSeen) return CreditInfo(0, known = false, detail = "响应里没有可识别的额度字段")
            CreditInfo(remain, known = true, detail = "周期剩余 $remain")
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询额度失败")
        }
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult {
        // 国际版（workbuddy.ai）没有签到制度与成长中心，这两个操作只对国内版有意义。
        // 选目标时已由 [supportsAction] 排掉，这里只是兵底（防止直接调用绕过过滤）。
        val credential = parse(account)
        if (credential != null && regionOf(credential) == REGION_GLOBAL) {
            return when (action) {
                ACTION_CHECKIN, ACTION_TASKS ->
                    ProviderActionResult.failure("国际版没有签到与成长中心")
                else -> ProviderActionResult.unsupported(action)
            }
        }
        // 企业账号没有个人成长体系：同属不适用，选目标时已排掉
        if (credential != null && credential.enterpriseId.isNotEmpty()) {
            return when (action) {
                ACTION_CHECKIN, ACTION_TASKS ->
                    ProviderActionResult.failure("企业账号没有个人成长体系（签到/任务仅个人账号可用）")
                else -> ProviderActionResult.unsupported(action)
            }
        }
        // 指定了具体任务就是「只动这一个」（任务列表里单条任务上的按钮）
        val singleTask = payload.get("task_code")?.asString.orEmpty()
        return when (action) {
            ACTION_CHECKIN -> checkin(account)
            ACTION_TASKS -> growth(account, singleTask)
            else -> ProviderActionResult.unsupported(action)
        }
    }

    /**
     * 成长中心一键领取（WorkBuddy 的「任务」就在这里）。
     *
     * 顺序按官方客户端的行为链：
     *   签到日历 → 新手礼包 / 补偿 → 领养 Buddy → 连登档位兑奖 → 抽奖
     *   → 领旅行礼物 / 派 Buddy 出发 → **成长任务：接单 → 推进 → 领奖** → 开盲盒 → 开学季
     *
     * 各步单独容错：某一步的业务拒绝（活动未开、无配额、条件未达）不该让其它步失败。
     * [singleTask] 非空时只处理那一条任务（其余步骤照跑）。
     */
    private fun growth(account: ProviderAccount, singleTask: String = ""): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        val base = "${chatBase(credential)}$PATH_GROWTH"
        val headers = billingHeaders(credential)
        val parts = ArrayList<String>()

        claimGiftAndCompensation(credential, headers, parts)
        adoptBuddy(credential, headers, parts)
        redeemStreak(credential, headers, parts)
        drawLottery(credential, headers, parts)
        claimTravel(credential, base, headers, parts)
        completeGrowthTasks(account, credential, base, headers, singleTask, parts)
        openBlindBox(credential, base, headers, parts)
        claimSchoolSeason(credential, headers, parts)

        return ProviderActionResult.success(
            if (parts.isEmpty()) "成长中心没有可领取的内容" else parts.joinToString("；"),
        )
    }

    /** 新手礼包与补偿：两个独立的一次性领取，各自容错。 */
    private fun claimGiftAndCompensation(
        credential: Credential,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        val billingRoot = chatBase(credential)
        for ((path, label) in listOf(
            "/billing/meter/claim-gift" to "新手礼包",
            "/billing/meter/claim-compensation" to "补偿",
        )) {
            runCatching {
                val (status, body) = postJson("${billingRoot}$path", credential, "{}", headers)
                if (status !in 200..299) return@runCatching
                val credit = jsonOf(body)?.firstNumber("credit", "reward_credit", "amount")
                if (credit != null) parts.add("$label +$credit")
            }
        }
    }

    /**
     * 领养 Buddy：先报「完成任务」，再同意协议，最后触发领养。
     *
     * 三步有先后依赖，任一步失败就停（上游会以 `first_buddy task not completed yet` 拒绝）。
     * 注意路径**没有 /v2 前缀**（与其它成长接口不同）。
     */
    private fun adoptBuddy(
        credential: Credential,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        val root = chatBase(credential)
        runCatching {
            val (infoStatus, infoBody) = getJson("$root$PATH_GROWTH/buddy/info", headers)
            if (infoStatus !in 200..299) return@runCatching
            val adopted = jsonOf(infoBody)?.let { obj ->
                val buddy = obj.objOrNull("buddy") ?: obj.objOrNull("data")?.objOrNull("buddy")
                buddy != null && buddy.entrySet().isNotEmpty()
            } ?: false
            if (adopted) return@runCatching

            val agree = "${chatBase(credential)}/activity/growth/buddy/agreement"
            runCatching { postJson(agree, credential, """{"agree":true}""", headers) }
            val first = "${chatBase(credential)}/activity/growth/buddy/first"
            val (status, body) = postJson(first, credential, "{}", headers)
            if (status in 200..299) {
                val credit = jsonOf(body)?.firstNumber("credit", "reward_credit")
                parts.add(if (credit != null) "领养 Buddy +$credit" else "领养 Buddy")
            }
        }
    }

    /**
     * 连登档位兑奖：查连登状态，对**已达标但未领取**的档位调 redeem。
     *
     * 档位固定 `7d` / `14d` / `28d`；每次请求要带一个全新的小写 UUIDv4 作为幂等键。
     */
    private fun redeemStreak(
        credential: Credential,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val root = chatBase(credential)
            val (status, body) = getJson("$root/activity/growth/streak", headers)
            if (status !in 200..299) return@runCatching
            val obj = jsonOf(body) ?: return@runCatching
            val entitle = obj.objOrNull("redemption_status") ?: obj.objOrNull("data")?.objOrNull("redemption_status")
            for (tier in listOf("7d", "14d", "28d")) {
                val state = entitle?.str("tier_${tier}_status").orEmpty().lowercase()
                // 已领过 / 未达标都跳过；只处理明确可领的
                if (state.isEmpty() || state == "claimed" || state == "redeemed" || state == "false") continue
                if (state != "true" && state != "available" && state != "claimable") continue
                val (rs, _) = postJson("$root/activity/growth/redeem", credential, """{"tier":"$tier","client_token":"${uuid4()}"}""", headers)
                if (rs in 200..299) parts.add("连登 $tier 兑奖")
            }
        }
    }

    /** 抽奖：先查次数，有剩余就抽（每次全新 client_token）。 */
    private fun drawLottery(
        credential: Credential,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val root = chatBase(credential)
            val (status, body) = getJson("$root/activity/growth/lottery/summary", headers)
            if (status !in 200..299) return@runCatching
            val chances = jsonOf(body)?.firstNumber("chances", "chance", "count") ?: return@runCatching
            if (chances <= 0) return@runCatching
            val draws = minOf(chances.toInt(), MAX_LOTTERY_DRAWS)
            repeat(draws) {
                val (rs, rb) = postJson(
                    "$root/activity/growth/lottery/draw",
                    credential,
                    """{"client_token":"${uuid4()}"}""",
                    headers,
                )
                if (rs in 200..299) {
                    val prize = jsonOf(rb)?.firstString("prize_code").orEmpty()
                    parts.add(if (prize.isEmpty()) "抽奖" else "抽奖 $prize")
                }
            }
        }
    }

    /** 开学季：活动在期时领任务奖、再用获得的机会抽转盘。 */
    private fun claimSchoolSeason(
        credential: Credential,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val root = chatBase(credential)
            val (status, body) = getJson("$root/portal/activity/school/tasks", headers)
            if (status !in 200..299) return@runCatching
            val obj = jsonOf(body) ?: return@runCatching
            val payload = obj.objOrNull("data") ?: obj
            if (payload.get("in_period")?.asBoolean != true) return@runCatching
            payload.arrayOrNull("tasks")?.forEach { element ->
                val task = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
                val code = task.firstString("task_code")
                if (code.isEmpty()) return@forEach
                val done = task.objOrNull("progress")?.let { p ->
                    val cur = p.firstNumber("current", "progress") ?: 0.0
                    val target = p.firstNumber("target", "target_count") ?: 1.0
                    cur >= target && target > 0
                } ?: false
                val st = task.firstString("status").lowercase()
                if (!done || st == "claimed" || st == "received") return@forEach
                val (rs, _) = postJson("$root/portal/activity/school/tasks/$code/claim", credential, "{}", headers)
                if (rs in 200..299) parts.add("开学季任务 $code")
            }
            // 有抽奖机会就转一次
            val (cs, cb) = getJson("$root/portal/activity/school/config", headers)
            if (cs in 200..299) {
                val balance = jsonOf(cb)?.let { o ->
                    (o.objOrNull("chance") ?: o.objOrNull("data")?.objOrNull("chance"))?.let {
                        it.firstNumber("balance", "count")
                    }
                } ?: 0.0
                if (balance > 0) {
                    val (ws, wb) = postJson(
                        "$root/portal/activity/school/wheel/draw",
                        credential,
                        """{"draw_uuid":"${uuid4()}"}""",
                        headers,
                    )
                    if (ws in 200..299) {
                        val prize = jsonOf(wb)?.firstString("prize_code").orEmpty()
                        parts.add(if (prize.isEmpty()) "开学季转盘" else "开学季转盘 $prize")
                    }
                }
            }
        }
    }

    /** 旅行：已到达先领礼物，空闲则派 Buddy 出发。 */
    private fun claimTravel(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val (status, body) = getJson("$base/buddy/travel/status", headers)
            if (status !in 200..299) return@runCatching
            val obj = jsonOf(body) ?: return@runCatching
            val state = obj.firstString("state")
            val dailyLimit = obj.get("daily_limit_reached")?.asBoolean ?: false
            when {
                state == "arrived" -> {
                    val recordId = obj.firstString("record_id")
                    if (recordId.isEmpty()) return@runCatching
                    val payload = JsonObject().apply { addProperty("record_id", recordId) }.toString()
                    val (code, text) = postJson("$base/buddy/travel/claim", credential, payload, headers)
                    if (code in 200..299) {
                        val credit = jsonOf(text)?.firstLong("reward_credit") ?: 0L
                        parts.add(if (credit > 0) "旅行礼物 +$credit" else "已领旅行礼物")
                    }
                }
                state == "idle" && !dailyLimit -> {
                    val (cfgCode, cfgBody) = getJson("$base/buddy/travel/config", headers)
                    if (cfgCode !in 200..299) return@runCatching
                    val location = jsonOf(cfgBody)?.arrayOrNull("locations")?.firstOrNull()
                        ?.let { runCatching { it.asJsonObject }.getOrNull() } ?: return@runCatching
                    val locationId = location.firstString("id")
                    if (locationId.isEmpty()) return@runCatching
                    val payload = JsonObject().apply { addProperty("location_id", locationId) }.toString()
                    val (code, text) = postJson("$base/buddy/travel/depart", credential, payload, headers)
                    if (code in 200..299) {
                        val name = jsonOf(text)?.objOrNull("location")?.firstString("name").orEmpty()
                        parts.add(if (name.isEmpty()) "已派 Buddy 出发" else "派 Buddy 去$name")
                    }
                }
            }
        }
    }

    /**
     * 取 CodeBuddy 响应里的 `data` 外层。
     *
     * 该供应商大部分接口把业务字段包在 `{code,msg,data}` 里，个别接口直接给顶层对象，
     * 少了这一步会静默地「什么都读不到」——而且看上去像上游没有数据（例如成长任务中心
     * 会显示「活动未开启」、「领取」按钮点了没反应）。
     */
    private fun dataOrRoot(obj: JsonObject): JsonObject = obj.objOrNull("data") ?: obj

    /** 拉任务列表原始报文；上游非 2xx 或网络异常时返回 null（调用方各自处理）。 */
    private fun fetchTasksBody(credential: Credential, base: String, headers: Map<String, String>): String? =
        runCatching {
            val (status, body) = getJson("$base/tasks", headers)
            body.takeIf { status in 200..299 }
        }.getOrNull()

    /**
     * 批量接单：上游按批接收 `task_codes`（参照实现一次最多 20 个）。
     *
     * 接单失败过去被静默吞掉，任务自然永远不点亮，看上去像「这个按钮没用」。
     */
    private fun acceptTaskCodes(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        codes: List<String>,
        parts: MutableList<String>,
    ) {
        if (codes.isEmpty()) return
        for (chunk in codes.distinct().chunked(ACCEPT_CHUNK_SIZE)) {
            runCatching {
                val (status, _) = postJson("$base/tasks/accept", credential, acceptPayload(chunk), headers)
                if (status !in 200..299) return@runCatching
                parts.add("接取任务 ${chunk.size} 个")
            }
        }
    }

    /**
     * 领单个任务的奖励。
     *
     * 注意路径**没有 `/v2` 前缀**（与领养 Buddy 等接口一致），响应里是 `data.credit` / `data.energy`。
     */
    private fun claimTaskReward(credential: Credential, task: ProviderTask, parts: MutableList<String>) {
        runCatching {
            val url = "${chatBase(credential)}$PATH_GROWTH/tasks/${task.code}/claim"
            val (status, body) = postJson(url, credential, "{}", billingHeaders(credential))
            if (status !in 200..299) return@runCatching
            val envelope = jsonOf(body)
            // 200 + 非 0 业务码（典型：task not completed）算失败，不能当领到奖
            if (envelope?.get("code")?.asLong?.let { it != 0L } == true) return@runCatching
            val credit = envelope?.let { dataOrRoot(it) }?.firstLong("credit") ?: 0L
            parts.add(if (credit > 0) "领任务奖「${task.title}」+$credit" else "领取任务「${task.title}」")
        }
    }

    /** 该任务现在是否要发 accept 请求。
     *
     * 上游 `accept_status` 取值是 `not_accepted` / `in_progress` / `claimed`：
     * - `claimed` 已领完，不再打；
     * - `in_progress` 且未做完：已经接单，等用户完成，不重复提交；
     * - 其余（含 `not_accepted`）要接单——按官方说明，**不先接单就不会记进度**；
     * - 已完成且未领取：这次请求就是领奖。
     */
    internal fun needsTaskAccept(task: ProviderTask): Boolean = when {
        task.claimed -> false
        task.status.equals("in_progress", ignoreCase = true) && !task.progressDone -> false
        else -> true
    }

    /** 接单请求体：上游要 `task_codes` 数组；提出来便于单测锁定形状。 */
    internal fun acceptPayload(codes: List<String>): String =
        JsonObject().apply {
            add("task_codes", JsonArray().apply { codes.forEach { add(it) } })
        }.toString()

    /**
     * 自动完成成长任务：先接单，再按 [CodeBuddyTaskComplete] 的配方推进进度，最后领奖。
     *
     * 与 [growth] 的分工：[growth] 只是「一键领取」（接单 + 领已达标奖励），任务本身要用户自己去官方
     * 客户端做；这里把任务也做完——上游把进度记在客户端埋点事件上，逐条上报对应事件即可推进，
     * 对话类任务还会真发一次对话。
     *
     * 顺序与官方客户端一致：**先接单再做事**（没接单不计进度），最后领奖。
     * 没有配方的任务不假装完成，在结果里点名列出。
     *
     * 结果累加进 [parts]；[singleTask] 非空时只处理这一条（任务列表里单条任务上的按钮）。
     */
    private fun completeGrowthTasks(
        account: ProviderAccount,
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        singleTask: String,
        parts: MutableList<String>,
    ) {
        fun scoped(task: ProviderTask): Boolean = singleTask.isEmpty() || task.code == singleTask

        val before = fetchTasksBody(credential, base, headers)?.let { parseGrowthTasks(it).tasks }
        if (before == null) {
            parts.add("拉取任务列表失败")
            return
        }
        acceptTaskCodes(
            credential,
            base,
            headers,
            // 未解锁的先不接：接了也只会拿到「task locked until …」
            before.filter { scoped(it) && !it.locked && needsTaskAccept(it) }.map { it.code },
            parts,
        )

        val pushed = ArrayList<String>()
        for (task in before.filter { scoped(it) && !it.completed && !it.locked }) {
            val recipe = CodeBuddyTaskComplete.recipeFor(task.code) ?: continue
            if (recipe.nightOnly && !isNightWindow()) {
                parts.add("「${recipe.label}」只在 23:00-08:00 计数，已跳过")
                continue
            }
            val gap = if (task.target > 0) (task.target - task.current).toInt().coerceAtLeast(1) else 1
            var rounds = 0
            repeat(minOf(gap, recipe.maxRounds)) { round ->
                // 先真发对话、再拿它的会话 id 上报：埋点里的会话必须真实存在，否则不计进度
                val prompt = recipe.chatFor?.invoke(round)
                val session = prompt?.let { runCatching { growthChat(account, it) }.getOrNull() }
                val events = recipe.events(
                    GrowthContext(
                        uid = credential.uid.ifEmpty { account.uid },
                        nickname = account.nickname,
                        nowMillis = nowMillis(),
                    ),
                    round,
                    session,
                )
                val (status, _) = reportEvents(credential, events)
                if (status !in 200..299) return@repeat
                rounds++
                pause(WRITE_GAP_MS)
            }
            if (rounds > 0) pushed.add("${recipe.label}×$rounds")
        }
        if (pushed.isNotEmpty()) parts.add("已推进 " + pushed.joinToString("、"))

        // 回读一次再领奖：上游记进度有延迟，判据用进度本身而不是 accept_status
        val after = fetchTasksBody(credential, base, headers)?.let { parseGrowthTasks(it).tasks }.orEmpty()
        for (task in after.filter { scoped(it) && !it.locked && it.claimable }) {
            claimTaskReward(credential, task, parts)
        }

        val unsupported = after
            .filter { scoped(it) && !it.completed && !it.locked && CodeBuddyTaskComplete.recipeFor(it.code) == null }
            .map { it.title }
        if (unsupported.isNotEmpty()) parts.add("暂不支持自动完成：" + unsupported.joinToString("、"))
    }

    /**
     * 桌面域埋点上报：`POST /v2/report`，请求体是**裸数组**（不是 `{code,msg,data}` 信封）。
     *
     * 官方客户端的「去完成」就是这个请求；X-Domain 固定声明 `copilot.tencent.com`
     * （与桌面客户端的 chatBase 一致，区域分叉只发生在对话/额度链路）。
     */
    private fun reportEvents(credential: Credential, events: List<JsonObject>): Pair<Int, String> {
        if (events.isEmpty()) return 200 to ""
        val body = JsonArray().apply { events.forEach { add(it) } }.toString()
        val headers = buildMap {
            put("Authorization", "Bearer ${credential.accessToken}")
            put("Accept", "application/json, text/plain, */*")
            put("X-Domain", "copilot.tencent.com")
            put("X-Product", "SaaS")
            put("X-Request-ID", CodeBuddyTaskComplete.newMessageId(nowMillis()))
            put("User-Agent", CodeBuddyTaskComplete.DESKTOP_UA)
            if (credential.uid.isNotEmpty()) put("X-User-Id", credential.uid)
        }
        return runCatching {
            postJson("${chatBase(credential)}$PATH_REPORT", credential, body, headers)
        }.getOrElse { e ->
            // 网络异常不该中断整个账号：单条上报失败按「没生效」处理，后面的任务照常走
            0 to (e.message ?: "上报失败")
        }
    }

    /**
     * 任务推进用的一次真实对话，返回这次对话的会话标识。
     *
     * 埋点里的 conversationId 必须指向**真实发出的那次对话**（自造 UUID 上游不认），
     * 所以这里把请求头里声明的会话 id（`X-Conversation-ID`，与 [stableConversationId] 同值）
     * 与响应回传的服务端消息 id 一并返回，交给 [CodeBuddyTaskComplete] 组装事件。
     * 对话没发成（或响应不可解析）时返回 null，配方退回合成的 id 兜底。
     */
    private fun growthChat(account: ProviderAccount, prompt: String): GrowthSession? {
        val body = JsonObject().apply {
            addProperty("model", CodeBuddyTaskComplete.GROWTH_CHAT_MODEL)
            addProperty("stream", false)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", GROWTH_CHAT_SYSTEM)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", prompt)
                    })
                },
            )
        }.toString()
        val call = openChat(account, body)
        val aggregated = try {
            (call as? AggregatedChatCall)?.aggregated
        } finally {
            call.close()
        }
        if (call.status !in 200..299 || aggregated == null) return null
        val conversationId = stableConversationId(body).ifEmpty { FALLBACK_CONVERSATION_ID }
        val serverId = runCatching {
            JsonParser.parseString(aggregated).asJsonObject.get("id")?.asString
        }.getOrNull().orEmpty()
        val messageId = serverId
            .takeIf { it.isNotEmpty() && it != OpenAiSseAggregator.FALLBACK_ID }
            ?: CodeBuddyTaskComplete.newMessageId(nowMillis())
        return GrowthSession(conversationId, messageId)
    }

    /** 夜猫子活动只在 CST 23:00-08:00 计数（UTC+8 小时）。 */
    private fun isNightWindow(): Boolean {
        val hour = ((nowMillis() / 3_600_000L + 8) % 24).toInt()
        return hour >= 23 || hour < 8
    }

    /** 盲盒：有配额才开。 */
    private fun openBlindBox(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val (status, body) = getJson("$base/buddy/quota", headers)
            if (status !in 200..299) return@runCatching
            val quota = jsonOf(body) ?: return@runCatching
            val remain = quota.firstLong("remaining", "quota", "count", "balance", "left")
            if (remain <= 0) return@runCatching
            val (code, text) = postJson("$base/buddy/open", credential, "{}", headers)
            if (code !in 200..299) return@runCatching
            val credit = jsonOf(text)?.firstLong("credit", "reward_credit") ?: 0L
            parts.add(if (credit > 0) "开盲盒 +$credit" else "已开盲盒")
        }
    }

    /**
     * 解析成长任务报文（单独提出供单测）：任务挂在 `data.tasks` 下，与其它 CodeBuddy 接口一致。
     *
     * 上游不保证返回 `in_period`（成长任务接口就没有这个字段），缺失时记为 null（未知），
     * 不能默认成“不在期”——那会在界面上伪造一条「活动未开启」。
     */
    internal fun parseGrowthTasks(body: String): ProviderTaskListView {
        val obj = jsonOf(body) ?: return ProviderTaskListView(emptyList(), error = "响应不是合法 JSON")
        val payload = dataOrRoot(obj)
        val inPeriod = payload.get("in_period")?.let { if (it.isJsonPrimitive) it.asBoolean else null }
        val array = payload.arrayOrNull("tasks") ?: JsonArray()
        val tasks = array.mapNotNull { element ->
            val task = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val code = task.firstString("task_code")
            if (code.isEmpty()) return@mapNotNull null
            // 进度既可能在顶层 current/target，也可能藏在 progress 对象里，后者优先
            var current = task.firstLong("current")
            var target = task.firstLong("target")
            task.objOrNull("progress")?.let { progress ->
                val pc = progress.firstLong("current")
                val pt = progress.firstLong("target")
                if (pt > 0 || pc > 0) {
                    current = pc
                    target = pt
                }
            }
            val acceptStatus = task.firstString("accept_status")
            ProviderTask(
                code = code,
                title = task.firstString("title").ifEmpty { code },
                description = task.firstString("description"),
                current = current,
                target = target,
                status = acceptStatus,
                locked = task.get("locked")?.asBoolean ?: false,
                // 上游同时下发 accept_status 与 claimed 布尔；两个都要认（领奖前靠它避免重复领）
                claimed = acceptStatus == "claimed" || task.get("claimed")?.asBoolean == true,
                rewardCredit = task.firstLong("reward_credit"),
            )
        }
        return ProviderTaskListView(tasks, inPeriod = inPeriod)
    }

    /**
     * 签到/成长中心只对国内个人账号存在：国际版没有这套体系，企业账号走企业额度口径。
     * 批量选目标时就会把这两种排掉，不会跑完再报错。
     */
    override fun supportsAction(account: ProviderAccount, action: String): Boolean {
        if (action != ACTION_CHECKIN && action != ACTION_TASKS) return true
        val credential = parse(account) ?: return true
        if (regionOf(credential) == REGION_GLOBAL) return false
        return credential.enterpriseId.isEmpty()
    }

    /**
     * 该任务是否要在读取任务列表时顺手领奖：未解锁且已达标未领。
     *
     * 单独提出来供单测锁定口径——它决定 taskList 会额外发哪些写请求。
     */
    internal fun autoClaimTargets(tasks: List<ProviderTask>): List<ProviderTask> =
        tasks.filter { !it.locked && it.claimable }

    /**
     * 成长任务明细：读 `/v2/activity/growth/tasks`，把 `data.tasks[]` 映射成 [ProviderTask]。
     *
     * 读取时顺手补齐已达标奖励：对未解锁且已达标的任务逐条领奖，成功后再回读一次拿最新快照，
     * 让界面直接显示「已领取」（任务中心不再有单条「领取」按钮）。
     * 国际版（workbuddy.ai）没有成长中心，直接返回空列表。
     */
    override fun taskList(account: ProviderAccount): ProviderTaskListView? {
        val credential = parse(account) ?: return ProviderTaskListView(emptyList(), error = "凭证无法解析")
        if (regionOf(credential) == REGION_GLOBAL) return ProviderTaskListView(emptyList())
        // 企业账号没有个人成长体系，不发请求（上游会回 403/10001）
        if (credential.enterpriseId.isNotEmpty()) return ProviderTaskListView(emptyList())
        val url = "${chatBase(credential)}$PATH_GROWTH/tasks"
        val headers = billingHeaders(credential)
        return try {
            val (status, body) = getJson(url, headers)
            if (status !in 200..299) {
                return ProviderTaskListView(
                    emptyList(),
                    error = failure("成长中心", endpoint(credential), url, status, body),
                )
            }
            val first = parseGrowthTasks(body)
            val targets = autoClaimTargets(first.tasks)
            if (targets.isEmpty()) return first
            for (task in targets) claimTaskReward(credential, task, ArrayList())
            // 领完再回读一次拿最新快照；回读失败（非 2xx / 异常）就退回首次结果
            val refreshed = runCatching {
                val (rs, rb) = getJson(url, headers)
                if (rs in 200..299) parseGrowthTasks(rb) else null
            }.getOrNull()
            refreshed ?: first
        } catch (e: Exception) {
            ProviderTaskListView(emptyList(), error = e.message ?: "拉取任务列表失败")
        }
    }

    /** WorkBuddy 的额度包明细（周期额度 / 容量包）。 */
    override fun creditPacks(account: ProviderAccount): List<QuotaPack> {
        val credential = parse(account) ?: return emptyList()
        return try {
            val (status, text) = postJson(
                "${billingBase(credential)}$PATH_USER_RESOURCE",
                credential,
                userResourceBody(),
                billingHeaders(credential),
            )
            if (status !in 200..299) return emptyList()
            val data = envelopeData(text) ?: return emptyList()
            val accounts = data.objOrNull("Response")?.objOrNull("Data")
                ?.arrayOrNull("Accounts") ?: return emptyList()
            accounts.mapNotNull { element ->
                val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val cycleSize = item.get("CycleCapacitySize")?.asLong ?: 0L
                val capacitySize = item.get("CapacitySize")?.asLong ?: 0L
                QuotaPack(
                    name = item.firstString("PackageName").ifEmpty { "额度包" },
                    group = if (cycleSize > 0) "周期额度" else "容量包",
                    limit = if (cycleSize > 0) cycleSize else capacitySize,
                    used = if (cycleSize > 0) {
                        item.get("CycleCapacityUsed")?.asLong ?: 0L
                    } else {
                        item.get("CapacityUsed")?.asLong ?: 0L
                    },
                    remain = if (cycleSize > 0) {
                        item.get("CycleCapacityRemain")?.asLong ?: 0L
                    } else {
                        item.get("CapacityRemain")?.asLong ?: 0L
                    },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        return try {
            val (status, text) = postJson(
                "${billingBase(credential)}$PATH_CHECKIN",
                credential,
                "{}",
                billingHeaders(credential),
            )
            val message = extractMessage(text)
            // 「今天已签到」上游有两种返回形态：HTTP 200 + 业务码，以及 **HTTP 400 + 错误正文**。
            // 后者过去在 status 分支里直接判失败，导致自动签到把幂等回应当成故障：整批永远
            // 「部分失败」、不记完成日、每小时重试一次。幂等判定必须在状态检查之前，
            // 且不能只认 200 —— 对「今天已经做过」这件事，4xx 也是成功。
            if (message.contains("已签到") || message.contains("already") ||
                message.contains("明天再来") || message.contains("明日再来")
            ) {
                return ProviderActionResult.success("今日已签到")
            }
            if (status !in 200..299) {
                return ProviderActionResult.failure(
                    failure("签到", endpoint(credential), "${billingBase(credential)}$PATH_CHECKIN", status, text),
                )
            }
            val code = runCatching { JsonParser.parseString(text).asJsonObject.get("code")?.asLong }.getOrNull() ?: -1L
            if (code == 0L) return ProviderActionResult.success("签到成功")
            ProviderActionResult.failure(message.ifEmpty { "签到失败（code=$code）" })
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    // ------------------------------------------------------------------ 凭证

    private val refreshLock = Any()

    /**
     * 最近一次成功刷新的凭证，按账号 uid 记录。
     *
     * 用 uid 而不是 refresh_token 作键：上游会轮换 refresh_token，旧值随即作废，
     * 以旧值为键会查不到刚写入的缓存而重复刷新。
     */
    @Volatile
    private var lastRefreshed: Map<String, Credential> = emptyMap()

    /** 各账号最近一次刷新失败的秒级时间戳（按 uid），用于失败冷却。 */
    @Volatile
    private var lastRefreshFailedAt: Map<String, Long> = emptyMap()

    /** 连续刷新失败次数（按账号）：用于决定还要不要护着过期凭证，避免瞬时失败把账号弄成「失效」。 */
    private var refreshFailureStreak: Map<String, Int> = emptyMap()

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: return null
        val nowSeconds = nowMillis() / 1000
        if (credential.expiresAt > 0 && credential.expiresAt - nowSeconds > skewSeconds) return null
        if (credential.refreshToken.isEmpty()) return null
        val uid = account.uid
        synchronized(refreshLock) {
            // 锁内复查：并发请求跨入同一临期窗口时，复用别的线程刚刷出的凭证，绝不重复刷新。
            // 必须返回新凭证——返回 null 会让调用方拿旧 token 去请求（直接 401）。
            val cached = lastRefreshed[uid]
            if (cached != null && cached.expiresAt - nowSeconds > skewSeconds) {
                return toProviderAccount(cached)
            }
            // 失败冷却：刷新失败后短时间内不再重打，否则 token 被吊销后会按请求频率轰炸端点。
            val failedAt = lastRefreshFailedAt[uid]
            if (failedAt != null && nowSeconds - failedAt < REFRESH_COOLDOWN_SECONDS) return null
            return try {
                val (status, text) = postJson(
                    "${chatBase(credential)}$PATH_TOKEN_REFRESH",
                    credential,
                    null,
                    refreshHeaders(credential),
                )
                if (status !in 200..299) return refreshFailed(uid, nowSeconds, "HTTP $status" + describeBody(text))
                val data = refreshEnvelopeData(text)
                    ?: return refreshFailed(uid, nowSeconds, "响应不是预期信封" + describeBody(text))
                // 走 credentialOf 而不是逐字段 get("accessToken")：上游两个站点用过 camelCase 与
                // snake_case 两套命名，只认 camelCase 时国际版的刷新会被误判成失败 → 冷却期内
                // 继续用旧 token → 上游 401 → 账号被硬禁用（用户看到的「登录失效」）。
                val parsed = credentialOf(data)
                if (parsed.accessToken.isEmpty()) {
                    return refreshFailed(uid, nowSeconds, "响应里没有 accessToken" + describeBody(text))
                }
                val refreshed = credential.copy(
                    accessToken = parsed.accessToken,
                    refreshToken = parsed.refreshToken.ifEmpty { credential.refreshToken },
                    domain = parsed.domain.ifEmpty { credential.domain },
                    expiresAt = resolveRefreshedExpiry(parsed.expiresAt, parsed.accessToken, nowSeconds),
                )
                lastRefreshed = lastRefreshed + (uid to refreshed)
                if (lastRefreshFailedAt.containsKey(uid)) lastRefreshFailedAt = lastRefreshFailedAt - uid
                if (refreshFailureStreak.containsKey(uid)) refreshFailureStreak = refreshFailureStreak - uid
                hooks.onVerbose("刷新凭证", text)
                toProviderAccount(refreshed)
            } catch (e: Exception) {
                refreshFailed(uid, nowSeconds, "异常：${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** 记录一次刷新失败并返回 null（调用方据此沿用旧凭证/除障）。 */
    private fun refreshFailed(uid: String, nowSeconds: Long, detail: String): ProviderAccount? {
        // 刷新失败从前是静默的（返回 null 而不抛异常），用户只看到「登录失效」却查不到原因；
        // 这里把状态码与响应片段写进日志，排查时能直接看出是哪一侧的问题。
        hooks.onLog("WorkBuddy 刷新凭证失败（$uid）：$detail")
        lastRefreshFailedAt = lastRefreshFailedAt + (uid to nowSeconds)
        refreshFailureStreak = refreshFailureStreak + (uid to ((refreshFailureStreak[uid] ?: 0) + 1))
        return null
    }

    /**
     * 是否应该拦住「带过期凭证去打上游」。
     *
     * 条件：凭证确实已过期 + 刷新刚失败过 + 连续失败次数还没超过上限。
     * 上限存在的意义：真正被吊销的会话（refresh_token 失效）也得能走到 401 → 禁用 → 提醒重新登录，
     * 不能被这个护盾永久堵住。
     */
    internal fun shouldShieldExpiredCredential(expiresAt: Long, streak: Int, nowSeconds: Long): Boolean {
        if (expiresAt <= 0) return false
        if (expiresAt > nowSeconds) return false
        return streak in 1..MAX_SHIELD_STREAK
    }

    /**
     * 刷新响应的 `data`：对 `code` 比对其它端点宽容。
     *
     * 其它端点的信封只有 `code == 0` 才算成功；刷新这条不行——有站点回 200 却不带 `code`
     * （或把 token 平铺在顶层）。一律当失败会让刷新永远失败、账号被反复判成失效。
     * 带了 `code` 且非 0 仍按失败处理（那确实是业务错误）。
     */
    internal fun refreshEnvelopeData(body: String): JsonObject? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val code = obj.get("code")
        if (code != null && code.takeIf { it.isJsonPrimitive }?.asLong != 0L) return null
        obj.objOrNull("data")?.let { return it }
        // 没有 data 包装时，只有看起来像凭证的顶层才认，避免把 {"code":0} 这种空响应当成 token
        val looksLikeCredential = obj.get("accessToken") != null || obj.get("access_token") != null
        return obj.takeIf { looksLikeCredential }
    }

    /**
     * 刷新后的绝对过期时间：响应里的绝对/相对时间 → 新 access_token 的 JWT `exp` → 保守兜底 TTL。
     *
     * 绝不能沿用旧值：能走到刷新这一步就说明旧凭证已经临期，沿用会让之后**每个请求**都再刷一次，
     * 刷新频率等于请求频率——正是上游风控吊销 token、账号「登录失效」的触发条件。
     */
    internal fun resolveRefreshedExpiry(responseExpiresAt: Long, accessToken: String, nowSeconds: Long): Long = when {
        responseExpiresAt > nowSeconds -> responseExpiresAt
        else -> jwtClaimLong(accessToken, "exp") ?: (nowSeconds + REFRESH_FALLBACK_TTL_SECONDS)
    }

    /**
     * 粘贴凭证 JSON 导入。
     *
     * 上游没有回调链接端点，浏览器授权错过后只能手工把凭证 JSON 贴进来。
     * 解析走与设备授权相同的 [credentialOf] → [toProviderAccount] 链路，
     * 因此导入的账号与轮询登录得到的账号结构完全一致，账号池无需区分来源。
     *
     * [credentialOf] 已经兼容 camelCase / snake_case 两套字段名并在 uid 缺失时从 JWT 兜底，
     * 这里只负责报错——非法输入必须给出可读原因（`importAccountJson` 会把 message 当错误回传）。
     */
    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) throw IllegalStateException("凭证 JSON 不能为空")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("不是合法的 JSON，请粘贴完整凭证")
        val credential = credentialOf(obj)
        if (credential.accessToken.isEmpty()) {
            throw IllegalStateException("凭证缺少 accessToken（access_token）字段")
        }
        return toProviderAccount(credential)
    }

    // ------------------------------------------------------------------ 设备授权登录

    /**
     * 授权端点专用请求头。
     *
     * 这两个接口必须在头上**明确声明自己不携带鉴权信息**（`X-No-Authorization` 等），
     * 否则网关会当成一个缺少凭据的普通请求而拒掉——表现就是「浏览器里登录完了，回到 App 报错」。
     * 依据两份独立实现：`cainiao1992/dsh-codebuddy-auth` 的 `NO_AUTH_HEADERS` 与
     * `CLIProxyAPIPlus` 的 `applyPollHeaders` / `FetchAuthState`。
     */
    internal fun authHeaders(base: String, withBody: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json, text/plain, */*")
        if (withBody) put("Content-Type", "application/json")
        put("X-Requested-With", "XMLHttpRequest")
        put("X-Domain", base)
        put("X-No-Authorization", "true")
        put("X-No-User-Id", "true")
        put("X-No-Enterprise-Id", "true")
        put("X-No-Department-Info", "true")
        put("X-Product", "SaaS")
        put("User-Agent", CLIENT_UA)
        put("X-Request-ID", java.util.UUID.randomUUID().toString())
    }

    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val base = endpointOf(region).chatBase
        val (status, text) = try {
            postJson("$base$PATH_AUTH_STATE?platform=CLI", null, "{}", authHeaders(base, withBody = true))
        } catch (e: Exception) {
            throw IllegalStateException("请求授权链接失败：${e.message}")
        }
        if (status !in 200..299) throw IllegalStateException("请求授权链接失败（HTTP $status）：${text.take(160)}")
        val data = envelopeData(text) ?: throw IllegalStateException("授权响应异常：${text.take(200)}")
        val state = data.get("state")?.asString.orEmpty()
        val authUrl = data.get("authUrl")?.asString.orEmpty()
        if (state.isEmpty() || authUrl.isEmpty()) throw IllegalStateException("授权响应缺少 state 或 authUrl")
        return DeviceAuthTicket(loginUrl = authUrl, state = state)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val base = endpointOf(region).chatBase
        val encoded = URLEncoder.encode(state, "UTF-8")
        val (status, text) = try {
            getJson("$base$PATH_AUTH_TOKEN?state=$encoded", authHeaders(base, withBody = false))
        } catch (e: Exception) {
            return DeviceAuthPoll.Failed(e.message ?: "轮询失败")
        }
        if (status !in 200..299) return DeviceAuthPoll.Failed("轮询失败（HTTP $status）：${text.take(160)}")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return DeviceAuthPoll.Failed("轮询响应不是合法 JSON")
        val code = obj.get("code")?.asLong ?: CODE_LOGIN_PENDING
        val data = obj.objOrNull("data")
        // 只有拿到 accessToken 才算成功；其余业务码一律继续轮询（与参照实现一致：
        // 非 0 只是“还没好”，当错误会让用户在浏览器里刚登录完就看到报错）
        if (code == 0L && !data?.get("accessToken")?.asString.isNullOrEmpty()) {
            return DeviceAuthPoll.Success(toProviderAccount(credentialOf(data!!)))
        }
        // 非 0 业务码（含未预期的）继续轮询，不中断登录：
        // 参照实现也把所有非成功码当「还没好」，当错误会让用户刚在浏览器里登录完就看到报错
        return DeviceAuthPoll.Pending
    }

    // ------------------------------------------------------------------ 上游

    private fun fetchModels(credential: Credential): List<ProviderModel> {
        val ep = endpoint(credential)
        val host = ep.chatBase
        val path = ep.modelsPath
        val (status, text) = try {
            // 目录响应带内嵌图标，可能上百 KB：允许 gzip（平台自己带上并透明解压）
            getJson("$host$path", modelsHeaders(credential), MODELS_READ_TIMEOUT_MS, allowCompression = true)
        } catch (e: Exception) {
            throw IllegalStateException("模型接口请求失败（${ep.label} $host$path）：${e.message}", e)
        }
        if (status !in 200..299) {
            throw IllegalStateException(failure("模型接口", ep, "$host$path", status, text))
        }
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("模型响应不是合法 JSON：${text.trim().take(160)}")
        if (obj.get("code")?.asLong != 0L) {
            throw IllegalStateException("模型接口 code=${obj.get("code")}" + describeBody(text))
        }
        return parseModelsCatalog(obj.objOrNull("data") ?: return emptyList())
    }

    /**
     * 从目录响应解析模型清单。
     *
     * 国内（网页控制台）与国际（客户端 product config）两份响应的结构相同：
     * `data.models[]` 是模型定义，`data.agents[name=cli].models` 是 CLI 可用的白名单，
     * 所以解析逻辑共用。
     */
    internal fun parseModelsCatalog(data: JsonObject): List<ProviderModel> {
        val cliIds = LinkedHashSet<String>()
        data.arrayOrNull("agents")?.forEach { element ->
            val agent = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
            if (agent.get("name")?.asString == "cli") {
                agent.arrayOrNull("models")?.forEach { cliIds.add(it.asString) }
            }
        }
        val result = ArrayList<ProviderModel>()
        val seen = HashSet<String>()
        data.arrayOrNull("models")?.forEach { element ->
            val item = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
            val modelId = item.get("id")?.asString.orEmpty()
            if (modelId.isEmpty() || seen.contains(modelId)) return@forEach
            if (cliIds.isNotEmpty() && !cliIds.contains(modelId)) return@forEach
            if (item.get("disabled")?.asBoolean == true) return@forEach
            seen.add(modelId)
            result.add(
                ProviderModel(
                    id = modelId,
                    name = item.get("name")?.asString.orEmpty().ifEmpty { modelId },
                    contextWindow = item.get("maxInputTokens")?.asLong ?: 0L,
                    extra = buildMap {
                        // 上游给的是 "x0.79 credits" 这种串，抽出数值作为倍率展示
                        parseCreditsStatic(item.get("credits")?.asString)?.let { put("rate", "$it×") }
                        item.get("maxOutputTokens")?.asLong?.takeIf { it > 0 }?.let {
                            put("maxOutput", it.toString())
                        }
                        if (item.get("supportsReasoning")?.asBoolean == true) put("reasoning", "true")
                        if (item.get("supportsImages")?.asBoolean == true) put("vision", "true")
                        if (item.get("supportsToolCall")?.asBoolean == true) put("tools", "true")
                        item.get("vendor")?.asString?.takeIf { it.isNotBlank() }?.let { put("vendor", it) }
                    },
                ),
            )
        }
        return result
    }

    // ------------------------------------------------------------------ HTTP

    private fun open(
        url: String,
        credential: Credential?,
        body: String?,
        headers: Map<String, String>,
        method: String = if (body == null) "GET" else "POST",
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        allowCompression: Boolean = false,
    ): HttpURLConnection {
        val merged = buildMap {
            putAll(headers)
            if (body != null) put("Content-Type", "application/json")
        }
        return HttpSupport.open(url, method, merged, body, CONNECT_TIMEOUT_MS, readTimeoutMs, allowCompression)
    }

    private fun postJson(
        url: String,
        credential: Credential?,
        body: String?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val conn = open(url, credential, body, headers)
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        allowCompression: Boolean = false,
    ): Pair<Int, String> {
        val conn = open(url, null, null, headers, method = "GET", readTimeoutMs = readTimeoutMs, allowCompression = allowCompression)
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun commonHeaders(credential: Credential?): Map<String, String> {
        val origin = originFor(credential)
        return mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to origin,
            "Referer" to "$origin/",
            "User-Agent" to CLIENT_UA,
        )
    }

    private fun chatHeaders(credential: Credential, body: String? = null): Map<String, String> = buildMap {
        putAll(commonHeaders(credential))
        put("Authorization", "Bearer ${credential.accessToken}")
        if (credential.uid.isNotEmpty()) put("X-User-Id", credential.uid)
        if (credential.enterpriseId.isNotEmpty()) {
            // 企业账号必须两个都带（与 billingHeaders 口径一致）：
            // 官方客户端每个请求都同时发 X-Enterprise-Id 与 X-Tenant-Id；
            // 只带一个时上游会按**个人账号**回答——模型目录/能力会退化成个人套餐。
            put("X-Enterprise-Id", credential.enterpriseId)
            put("X-Tenant-Id", credential.enterpriseId)
        }
        if (credential.domain.isNotEmpty()) put("X-Domain", credential.domain)
        put("X-Product", "SaaS")
        // 官方 CLI 的会话 ID 头族（对话端点必需）：国际版缺 `X-Conversation-ID`
        // 会直接 400「conversation id missing」。
        if (body != null) putAll(conversationHeaders(body))
    }

    /**
     * 会话 ID 头族。
     *
     * 两个必须区分开的语义：
     * - `X-Conversation-ID` **在同一个会话内必须稳定**：上游按它给提示词缓存分桶，
     *   每请求一个新 id 会让每次请求落到不同桶——长上下文下实测 0% 前缀命中、
     *   积分多花约 3 倍。所以它由消息前缀确定性派生（同会话同值）。
     * - `X-Conversation-Request-ID` / `X-Conversation-Message-ID` / `X-Request-ID`
     *   每个请求都换新（官方 CLI 也是如此，参照实现用抓包验证过）。
     */
    private fun conversationHeaders(body: String): Map<String, String> {
        val traceId = uuid4()
        // 派生不出会话指纹时**不能**改成随机：那会让每次请求落到不同缓存桶。
        // 退回一个固定 UUID（与官方 CLI 的「会话内稳定」语义一致）。
        val conversationId = stableConversationId(body).ifEmpty { FALLBACK_CONVERSATION_ID }
        return mapOf(
            "X-Conversation-ID" to conversationId,
            "X-Conversation-Request-ID" to uuid4(),
            "X-Conversation-Message-ID" to uuid4(),
            "X-Request-ID" to traceId,
            "X-Trace-ID" to traceId,
            "X-Agent-Intent" to "craft",
            "X-Agent-Purpose" to "conversation",
            "X-IDE-Type" to "CLI",
            "X-IDE-Name" to "CLI",
            "x-codebuddy-request" to "1",
        )
    }

    /**
     * 模型目录请求头：同 [chatHeaders]，再叠加该区域的附加头（国际版要 `X-Agent-Type: main`）。
     */
    private fun modelsHeaders(credential: Credential): Map<String, String> = buildMap {
        putAll(chatHeaders(credential))
        putAll(endpoint(credential).extraHeaders)
    }

    private fun billingHeaders(credential: Credential): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${credential.accessToken}")
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        put("X-Requested-With", "XMLHttpRequest")
        put("User-Agent", CLIENT_UA)
        val origin = originFor(credential)
        put("Origin", origin)
        put("Referer", "$origin/")
        put("X-Product", "SaaS")
        if (credential.uid.isNotEmpty()) put("X-User-Id", credential.uid)
        if (credential.enterpriseId.isNotEmpty()) {
            put("X-Enterprise-Id", credential.enterpriseId)
            put("X-Tenant-Id", credential.enterpriseId)
        }
        if (credential.domain.isNotEmpty()) put("X-Domain", credential.domain)
    }

    /** refresh 是唯一允许携带 X-Refresh-Token 的端点。 */
    private fun refreshHeaders(credential: Credential): Map<String, String> = buildMap {
        putAll(commonHeaders(credential))
        put("X-Refresh-Token", credential.refreshToken)
        if (credential.enterpriseId.isNotEmpty()) {
            put("X-Enterprise-Id", credential.enterpriseId)
            put("X-Tenant-Id", credential.enterpriseId)
        }
        put("X-Auth-Refresh-Source", "workbuddy")
    }

    /**
     * 上游失败的统一描述：`<前缀>（<区域> <URL>）HTTP <状态码>：<上游原文>`。
     *
     * 只报状态码无法定位（同一个 500 可能来自完全不同的原因），所以原文一律带出来。
     */
    private fun failure(prefix: String, endpoint: Endpoint, url: String, status: Int, body: String): String =
        "$prefix（${endpoint.label} $url）HTTP $status" + describeBody(body).ifEmpty { "（响应体为空）" }

    private fun userResourceBody(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = Date(nowMillis())
        val far = Date(nowMillis() + 101L * 365 * 24 * 3600 * 1000)
        return JsonObject().apply {
            addProperty("PageNumber", 1)
            addProperty("PageSize", 100)
            addProperty("ProductCode", PRODUCT_CODE)
            add("Status", JsonArray().apply { add(0); add(3) })
            addProperty("PackageEndTimeRangeBegin", format.format(now))
            addProperty("PackageEndTimeRangeEnd", format.format(far))
        }.toString()
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 区域判定：
     * 1. access_token 的 JWT `iss` 是权威依据——上游两个 realm（国内 codebuddy.cn / 国际
     *    workbuddy.ai）就靠它区分，而拿国际版 token 打国内站点会被直接回 500；
     * 2. 否则看凭证里的 `domain`：`workbuddy.ai` / `codebuddy.ai`（国际历史域名）为国际，
     *    其余非空值为国内；
     * 3. 都没有时回退到供应商设置里的区域。
     */
    private fun regionOf(credential: Credential?): String {
        val issuer = jwtClaim(credential?.accessToken.orEmpty(), "iss", "issuer").lowercase()
        if (issuer.contains("workbuddy.ai") || issuer.contains("codebuddy.ai")) return REGION_GLOBAL

        val domain = credential?.domain.orEmpty().trim().lowercase()
        if (
            domain == REGION_GLOBAL ||
            domain == "workbuddy.ai" ||
            domain.endsWith(".workbuddy.ai") ||
            domain == "codebuddy.ai" ||
            domain.endsWith(".codebuddy.ai")
        ) {
            return REGION_GLOBAL
        }
        if (domain.isNotEmpty()) return REGION_CN
        return region()
    }

    /** 网关按账号区域选号用的公开入口（见 [RegionAwareSupport]）。 */
    override fun regionOf(account: ProviderAccount): String? = regionOf(parse(account))

    override fun regions(): List<String> = listOf(REGION_CN, REGION_GLOBAL)

    /** 该凭证的区域端点；所有按区域分叉的 host / 路径 / 头都从这里取。 */
    private fun endpoint(credential: Credential?): Endpoint = endpointFor(regionOf(credential))

    private fun chatBase(credential: Credential?): String = endpoint(credential).chatBase

    private fun billingBase(credential: Credential?): String = endpoint(credential).billingBase

    private fun originFor(credential: Credential?): String = endpoint(credential).origin

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        return credentialOf(obj).takeIf { it.accessToken.isNotEmpty() }
    }

    private fun credentialOf(obj: JsonObject): Credential {
        // 上游不同版本/站点用过 camelCase 与 snake_case 两套字段名，都要兼容：
        // 少一个 uid 就会让 billing 接口缺 X-User-Id 头而报 400。
        val accessToken = obj.firstString("accessToken", "access_token")
        return Credential(
            accessToken = accessToken,
            refreshToken = obj.firstString("refreshToken", "refresh_token"),
            // 设备授权/刷新返回的是**相对的** expiresIn（秒），没有绝对时间戳。不换算成绝对时间的话
            // expiresAt 恒为 0，[refreshAccount] 的「距过期还早就不刷」判断失效，
            // 每个请求都会先做一次 token 刷新往返（首字延迟平白多一跳）。
            expiresAt = obj.firstLong("expiresAt", "expires_at").takeIf { it > 0 }
                ?: obj.firstLong("expiresIn", "expires_in").takeIf { it > 0 }
                    ?.let { nowMillis() / 1000 + it }
                ?: 0L,
            domain = obj.firstString("domain"),
            // 响应里没给时从 access_token（JWT）里解，旧的存量账号也能自动修好
            uid = obj.firstString("uid", "userId", "user_id")
                .ifEmpty { jwtClaim(accessToken, "user_id", "userId", "uid", "sub") },
            enterpriseId = obj.firstString("enterpriseId", "enterprise_id", "entId", "tenantId", "tenant_id")
                .ifEmpty { jwtClaim(accessToken, "tenant_id", "tenantId", "enterprise_id", "enterpriseId") },
            nickname = obj.firstString("nickname", "nickName", "name"),
        )
    }

    private fun toProviderAccount(credential: Credential): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("accessToken", credential.accessToken)
            addProperty("refreshToken", credential.refreshToken)
            addProperty("expiresAt", credential.expiresAt)
            addProperty("domain", credential.domain)
            addProperty("uid", credential.uid)
            addProperty("enterpriseId", credential.enterpriseId)
            addProperty("nickname", credential.nickname)
        }.toString()
        val uid = credential.uid.ifEmpty {
            "codebuddy-" + credential.accessToken.hashCode().toUInt().toString(16)
        }
        return ProviderAccount(id, uid, credential.nickname, secret)
    }

    private fun envelopeData(body: String): JsonObject? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        if (obj.get("code")?.asLong != 0L) return null
        return obj.objOrNull("data")
    }

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.get("msg")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        obj.objOrNull("error")?.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        obj.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /** 响应体里是否含配额/限频标记词（标记词均为小写）。 */
    private fun matchesQuota(body: String): Boolean {
        // 标记词已是小写，只需比一次（lower 就是 body 的小写，比两次是冗余）
        val lower = body.lowercase()
        return QUOTA_MARKERS.any { lower.contains(it) }
    }

    private fun matchesSessionDead(body: String): Boolean =
        SESSION_DEAD_MARKERS.any { body.contains(it) }

    private data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val domain: String,
        val uid: String,
        val enterpriseId: String,
        val nickname: String,
    )

    companion object {
        const val ID = "codebuddy"

        const val ACTION_CHECKIN = dev.aigw.core.provider.ACTION_CHECKIN

        const val REGION_CN = "cn"
        const val REGION_GLOBAL = "global"

        /** 业务码：该模型已超限（换号无用，换模型立刻可用）。 */
        const val CODE_MODEL_LIMITED = 6004L

        /** 刷新失败后的冷却秒数：期间不再重打上游刷新端点。 */
        const val REFRESH_COOLDOWN_SECONDS = 60L

        /** 连续刷新失败几次之后不再拦过期凭证（把真正的死会话还给 401 路径去禁用+提醒重登）。 */
        internal const val MAX_SHIELD_STREAK = 2

        /**
         * 一个区域对应的出站端点。
         *
         * 按区域分叉的一切（host / Origin / 模型目录路径 / 附加头 / 展示名）都收在这里：
         * 同一个账号的每个请求都必须从同一处取端点，否则会出现「一部分请求打到另一个站点」
         * （见项目记忆 provider-region-consistency）。
         */
        internal data class Endpoint(
            val label: String,
            val chatBase: String,
            val billingBase: String,
            val origin: String,
            val modelsPath: String,
            val extraHeaders: Map<String, String> = emptyMap(),
        )

        internal val CN_ENDPOINT = Endpoint(
            label = "国内版",
            chatBase = "https://copilot.tencent.com",
            billingBase = "https://www.codebuddy.cn",
            origin = "https://www.codebuddy.cn",
            modelsPath = PATH_MODELS_CN,
        )

        internal val GLOBAL_ENDPOINT = Endpoint(
            label = "国际版",
            chatBase = "https://www.workbuddy.ai",
            billingBase = "https://www.workbuddy.ai",
            origin = "https://www.workbuddy.ai",
            modelsPath = PATH_MODELS_GLOBAL,
            // WorkBuddy AI 客户端的客户端类型标识，国际版站点要求
            extraHeaders = mapOf("X-Agent-Type" to "main"),
        )

        internal fun endpointOf(region: String): Endpoint =
            if (region == REGION_GLOBAL) GLOBAL_ENDPOINT else CN_ENDPOINT

        const val CLIENT_UA = "CLI/2.63.2 CodeBuddy/2.63.2"

        /**
         * 刷新响应既没给过期时间、新 token 也不是 JWT 时的兜底有效期（秒）。
         *
         * 取 30 分钟：足够避开「每请求都刷」的频率放大，又不会短到把上游额度刷爆。
         */
        internal const val REFRESH_FALLBACK_TTL_SECONDS = 1800L

        const val PATH_AUTH_STATE = "/v2/plugin/auth/state"
        const val PATH_AUTH_TOKEN = "/v2/plugin/auth/token"

        /** 接单接口按批接收 task_codes（参照实现一次最多 20 个）。 */
        private const val ACCEPT_CHUNK_SIZE = 20
        const val PATH_TOKEN_REFRESH = "/v2/plugin/auth/token/refresh"
        const val PATH_CHAT = "/v2/chat/completions"
        const val PATH_MODELS_CN = "/console/enterprises/personal/models"

        /**
         * 国际版的模型目录来源：客户端 product config（`/v3/config`）。
         *
         * 国内那条 `/console/...` 只服务网页会话：无凭证时 302 跳 OIDC 登录页，拿 Bearer token 打
         * 它会被后端直接 500；`/v2/enterprises/personal/models` 则会一直不返回（请求挂死）。
         * 国际版客户端（CLI / 桌面端）实际读 `/v3/config` 的 `data.agents[cli].models`，
         * 与国内那份响应同构，解析逻辑共用。
         */
        const val PATH_MODELS_GLOBAL = "/v3/config"

        /**
         * 模型目录路径：国内走网页控制台接口，国际版走客户端 product config。
         */
        internal fun modelsPath(region: String): String = endpointOf(region).modelsPath
        /** 客户端埋点上报：成长任务的进度就记在这里（裸数组 body，见 [reportEvents]）。 */
        const val PATH_REPORT = "/v2/report"

        /** 写动作之间的停顿：上游对密集写有风控，参照实现用 1.5s。 */
        internal const val WRITE_GAP_MS = 1200L

        /** 任务推进发的真实对话的系统提示词：上游要求首条消息必须是 system。 */
        private const val GROWTH_CHAT_SYSTEM = "你是 WorkBuddy 助手，请简短回答用户的问题。"

        const val PATH_USER_RESOURCE = "/v2/billing/meter/get-user-resource"
        const val PATH_CHECKIN = "/v2/billing/meter/daily-checkin"

        /** 成长中心：任务、Buddy 旅行、盲盒都在这个前缀下。 */
        const val PATH_GROWTH = "/v2/activity/growth"

        /** 抽奖单次最多抽几次（官方客户端有硬上限，这里取一个保守值）。 */
        const val MAX_LOTTERY_DRAWS = 10

        /** 官方默认模型（`name = "Auto"`）。 */
        const val DEFAULT_MODEL_ID = "default-model"

        /** 设备授权等待中的业务码。 */
        const val CODE_LOGIN_PENDING = 11217L

        const val PRODUCT_CODE = "p_tcaca"

        /** 余额不足关键词（上游没有稳定错误码，按文案兜底）。 */
        private val QUOTA_MARKERS = listOf(
            "insufficient credit", "no credit", "credit exhausted", "out of credit",
            "quota exceeded", "quota exhaust", "payment required", "credit not enough",
            "not enough credit", "积分不足", "额度不足", "余额不足", "积分用完", "额度用尽", "没有积分",
        )

        private val SESSION_DEAD_MARKERS = listOf("Offline user session not found", "12153")

        /** 解析 `"x0.79 credits"` 这类串；不合法返回 null。 */
        fun parseCreditsStatic(raw: String?): String? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val token = text.removePrefix("x").removePrefix("X").trim().split(' ').firstOrNull().orEmpty()
            val value = token.toDoubleOrNull() ?: return null
            if (!value.isFinite() || value < 0) return null
            return if (value == value.toLong().toDouble()) value.toLong().toString()
            else value.toString().trimEnd('0').trimEnd('.')
        }

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000

        /** 模型目录是普通 JSON 接口（非流式），给它一个短超时：
         *  用对话那套 300s 会被网关侧的 20s 聚合超时盖掉，看不到真实错误。 */
        private const val MODELS_READ_TIMEOUT_MS = 15_000
    }
}

/**
 * 改写发往 CodeBuddy 的请求体：
 * 1. 强制 `stream:true`（上游拒绝非流式）；
 * 2. `tool_choice` 归一化成字符串（上游该字段是 string，对象形式会 400）；
 * 3. 消息形状对齐上游要求（首条必须 system、`developer` 改叫 `system`）
 *    与 `max_completion_tokens` → `max_tokens`；
 * 4. 去掉上游会拦的特征文本（Claude Code / Codex CLI 的系统提示词片段）。
 */
internal fun prepareCodeBuddyBody(src: String): String = prepareCodeBuddyRequest(src).body

/** 归一化后的请求体 + 工具名还原表（空表 = 无需还原，响应可原样透传）。 */
internal data class PreparedCodeBuddyRequest(
    val body: String,
    val toolNameRestore: Map<String, String>,
)

internal fun prepareCodeBuddyRequest(src: String): PreparedCodeBuddyRequest {
    val obj = runCatching { JsonParser.parseString(src).asJsonObject }.getOrNull()
        ?: return PreparedCodeBuddyRequest(src, emptyMap())
    obj.addProperty("stream", true)
    normalizeCodeBuddyToolChoice(obj)
    normalizeCodeBuddyMessages(obj)
    normalizeCodeBuddyMaxTokens(obj)
    unflagCodeBuddyMessages(obj)
    val restore = normalizeCodeBuddyTools(obj)
    return PreparedCodeBuddyRequest(obj.toString(), restore)
}

/**
 * 工具形状归一：schema 按上游能接受的形状改写，工具名里的非法字符换成 `_`。
 *
 * 不做这两件事时，国际版会直接回 400「Invalid request parameters」（11133）。
 * 返回「改写后的名字 → 原名」，供响应侧还原调用名。
 */
private fun normalizeCodeBuddyTools(obj: JsonObject): Map<String, String> {
    val tools = obj.arrayOrNull("tools") ?: return emptyMap()
    val restore = LinkedHashMap<String, String>()
    for (element in tools) {
        val tool = runCatching { element.asJsonObject }.getOrNull() ?: continue
        val function = tool.objOrNull("function") ?: continue
        function.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let { name ->
            val original = name.asString
            val sanitized = sanitizeToolName(original)
            if (sanitized != original) {
                function.addProperty("name", sanitized)
                restore.putIfAbsent(sanitized, original)
            }
        }
        function.get("parameters")?.let { function.add("parameters", normalizeToolParameters(it)) }
    }
    return restore
}

/** 上游不认 `max_completion_tokens`（新版 OpenAI 客户端会发它），只认 `max_tokens`。 */
private fun normalizeCodeBuddyMaxTokens(obj: JsonObject) {
    val legacy = obj.get("max_completion_tokens") ?: return
    obj.remove("max_completion_tokens")
    if (!obj.has("max_tokens")) obj.add("max_tokens", legacy)
}

/** 缺 system 消息时补的默认提示词（与公开实现一致的中性文本，能过上游的内容检查）。 */
private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."

/**
 * 消息形状归一。
 *
 * 上游把「首条消息不是 system」当成错误拒绝（HTTP 400 code=11128
 * "first message is not system prompt"），所以缺就补一条；
 * 只要不是 system 的 `developer` 角色也改叫 `system`。
 */
private fun normalizeCodeBuddyMessages(obj: JsonObject) {
    val messages = obj.arrayOrNull("messages") ?: return
    for (element in messages) {
        val message = runCatching { element.asJsonObject }.getOrNull() ?: continue
        if (message.get("role")?.asString == "developer") message.addProperty("role", "system")
    }

    // 11153「the conversation must end with a user message」：工具结果之后紧跟 user 消息
    // （Claude Code 的提醒、任务中途输入、工具图片）会被上游拒；中间插一条空 assistant 即可通过。
    // Gson 的 JsonArray 没有 add(index, e)，所以新数组重建。
    val rebuilt = JsonArray()
    var prevRole: String? = null
    for (element in messages) {
        val role = runCatching { element.asJsonObject.get("role")?.asString }.getOrNull()
        if (prevRole == "tool" && role == "user") {
            rebuilt.add(
                JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", "")
                },
            )
        }
        rebuilt.add(element)
        prevRole = role
    }
    obj.add("messages", rebuilt)

    val first = rebuilt.firstOrNull()?.let { runCatching { it.asJsonObject }.getOrNull() }
    if (first != null && first.get("role")?.asString == "system") return
    val withSystem = JsonArray()
    withSystem.add(
        JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", DEFAULT_SYSTEM_PROMPT)
        },
    )
    for (element in rebuilt) withSystem.add(element)
    obj.add("messages", withSystem)
}

/**
 * 上游会以「Illegal API invocation from an unapproved channel」拦下带第三方 CLI 特征文本的请求
 * （Claude Code / Codex CLI 的系统提示词片段），这几处按等义写法替换即可通过。
 *
 * 替换表来自公开实现 magpie（MIT，yetone，https://github.com/yetone/magpie；
 * 用真实客户端逐词实测得出），两套发行版都适用。
 */
private val FLAGGED_TEXT = listOf(
    Regex("x-anthropic-billing-header:[^\\n]*\\n?", RegexOption.IGNORE_CASE) to "",
    Regex("You are Claude Code, Anthropic['’]s official CLI for Claude", RegexOption.IGNORE_CASE) to
        "You are Claude Code, Anthropic's CLI for Claude",
    Regex("Main branch \\(you will usually use this for PRs\\)", RegexOption.IGNORE_CASE) to
        "Main branch (usually the base for PRs)",
    Regex("(?<!the )Codex CLI is an open source project led by OpenAI\\.", RegexOption.IGNORE_CASE) to
        "The Codex CLI is an open source project led by OpenAI.",
)

/** 去掉消息里的敏感特征文本；字符串内容与多模态 parts 都处理。 */
private fun unflagCodeBuddyMessages(obj: JsonObject) {
    val messages = obj.arrayOrNull("messages") ?: return
    for (element in messages) {
        val message = runCatching { element.asJsonObject }.getOrNull() ?: continue
        val content = message.get("content") ?: continue
        when {
            content.isJsonPrimitive && content.asJsonPrimitive.isString ->
                message.addProperty("content", unflagText(content.asString))
            content.isJsonArray -> {
                for (part in content.asJsonArray) {
                    val partObj = runCatching { part.asJsonObject }.getOrNull() ?: continue
                    val text = partObj.get("text") ?: continue
                    if (text.isJsonPrimitive && text.asJsonPrimitive.isString) {
                        partObj.addProperty("text", unflagText(text.asString))
                    }
                }
            }
        }
    }
}

internal fun unflagText(text: String): String {
    var result = text
    for ((pattern, replacement) in FLAGGED_TEXT) result = pattern.replace(result, replacement)
    return result
}

internal fun normalizeCodeBuddyToolChoice(obj: JsonObject) {
    val choice = obj.get("tool_choice") ?: return
    when {
        choice.isJsonPrimitive -> {
            if (choice.asString.equals("none", ignoreCase = true)) {
                obj.remove("tool_choice")
                obj.remove("tools")
                obj.remove("functions")
            }
        }
        choice.isJsonObject -> {
            val type = choice.asJsonObject.get("type")?.asString.orEmpty().lowercase()
            when (type) {
                "none" -> {
                    obj.remove("tool_choice")
                    obj.remove("tools")
                    obj.remove("functions")
                }
                "auto", "required" -> obj.addProperty("tool_choice", type)
                "function" -> {
                    val name = choice.asJsonObject.objOrNull("function")?.get("name")?.asString.orEmpty()
                    obj.addProperty("tool_choice", name.ifEmpty { "auto" })
                }
                else -> obj.remove("tool_choice")
            }
        }
        else -> obj.remove("tool_choice")
    }
}

/** 解析响应体；不是合法 JSON 时返回 null。 */
private fun jsonOf(body: String): JsonObject? =
    runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()

/** 按顺序取第一个能解析成数字的字段（用于 credits / reward_credit 这类可能是字符串的字段）。 */
internal fun JsonObject.firstNumber(vararg keys: String): Double? {
    for (key in keys) {
        val value = get(key) ?: continue
        if (value.isJsonNull) continue
        if (value.isJsonPrimitive) {
            val prim = value.asJsonPrimitive
            if (prim.isNumber) return prim.asDouble
            prim.asString.trim().toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

/** 生成一个小写 UUIDv4（幂等键用，每次请求都要全新）。 */
internal fun uuid4(): String = java.util.UUID.randomUUID().toString()

/**
 * 把上游响应体整理成一句可读的补充说明。
 *
 * 上游报错时只给 HTTP 状态码是不够的（400 可能是参数、头、或账号状态问题），
 * 必须把 `msg` 或响应原文带出来，用户才能反馈、我们才能定位。
 */
private fun describeBody(body: String): String {
    if (body.isBlank()) return ""
    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    val message = obj?.firstString("msg", "message", "desc", "error") ?: ""
    if (message.isNotEmpty()) return "：$message"
    return "：${body.trim().take(160)}"
}
