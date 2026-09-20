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
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
class CodeBuddyProvider(
    private val region: () -> String = { REGION_CN },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, DeviceCodeSupport {

    override val id: String = ID
    override val displayName: String = "WorkBuddy"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN, ProviderCapability.TASKS)

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val credential = account?.let { parse(it) }
        if (credential == null) {
            return ProviderModelCatalogView(FALLBACK_MODELS, fromFallback = true, error = "没有可用账号，显示内置快照")
        }
        return try {
            val models = fetchModels(credential)
            if (models.isEmpty()) {
                ProviderModelCatalogView(FALLBACK_MODELS, fromFallback = true, error = "上游返回空模型列表")
            } else {
                ProviderModelCatalogView(models, fromFallback = false, error = "")
            }
        } catch (e: Exception) {
            ProviderModelCatalogView(FALLBACK_MODELS, fromFallback = true, error = e.message ?: "拉取模型失败")
        }
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return FALLBACK_MODELS.first().id
        return model
    }

    override fun hosts(): List<String> =
        listOf("copilot.tencent.com", "codebuddy.cn", "workbuddy.ai")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreaming(openAiBody)
        val model = resolveModel(requestedModel(openAiBody))
        val prepared = prepareCodeBuddyBody(openAiBody)

        val conn = try {
            open("${chatBase(credential)}$PATH_CHAT", credential, prepared, chatHeaders(credential))
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
            val errorBody = runCatching { readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            if (streaming) {
                return StreamingChatCall(status, stream) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(stream, model)
            } finally {
                conn.disconnect()
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        return AggregatedChatCall(status, text)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body)
        val kind = when {
            status == 402 -> ErrorKind.QUOTA
            status == 429 -> ErrorKind.SOFT_RATE
            status == 404 -> ErrorKind.NOT_FOUND
            status in 500..599 -> ErrorKind.SERVER
            matchesQuota(body) -> ErrorKind.QUOTA
            matchesSessionDead(body) -> ErrorKind.SESSION_DEAD
            status in 400..499 -> ErrorKind.CLIENT
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message.ifEmpty { "上游 HTTP $status" })
    }

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
                return CreditInfo(0, known = false, detail = "上游 HTTP $status" + describeBody(text))
            }
            val data = envelopeData(text) ?: return CreditInfo(0, known = false, detail = "响应缺少 data")
            val accounts = data.objOrNull("Response")?.objOrNull("Data")?.arrayOrNull("Accounts")
            var remain = 0L
            accounts?.forEach { element ->
                val item = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
                val size = item.get("CycleCapacitySize")?.asLong ?: 0L
                val cycleRemain = item.get("CycleCapacityRemain")?.asLong ?: 0L
                val value = if (size > 0 || cycleRemain > 0) {
                    cycleRemain
                } else {
                    item.get("CapacityRemain")?.asLong ?: 0L
                }
                if (value > 0) remain += value
            }
            CreditInfo(remain, known = true, detail = "周期剩余 $remain")
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询额度失败")
        }
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        ACTION_TASKS -> growth(account)
        else -> ProviderActionResult.unsupported(action)
    }

    /**
     * 成长中心一键领取（WorkBuddy 的「任务」就在这里）。
     *
     * 顺序按官方客户端：领旅行礼物 → 派 Buddy 出发 → 领任务/领任务奖 → 开盲盒。
     * 各步单独容错：某一步的业务拒绝（活动未开、无配额）不该让其它步失败。
     */
    private fun growth(account: ProviderAccount): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        val base = "${chatBase(credential)}$PATH_GROWTH"
        val headers = billingHeaders(credential)
        val parts = ArrayList<String>()

        claimTravel(credential, base, headers, parts)
        claimTasks(credential, base, headers, parts)
        openBlindBox(credential, base, headers, parts)

        return ProviderActionResult.success(
            if (parts.isEmpty()) "成长中心没有可领取的内容" else parts.joinToString("；"),
        )
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
     * 任务：未领取的先接单（进度从接单那刻才开始计），已接单且达标的领奖。
     */
    private fun claimTasks(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val (status, body) = getJson("$base/tasks", headers)
            if (status !in 200..299) return@runCatching
            val tasks = jsonOf(body)?.arrayOrNull("tasks") ?: return@runCatching
            for (element in tasks) {
                val task = runCatching { element.asJsonObject }.getOrNull() ?: continue
                if (task.get("locked")?.asBoolean == true) continue
                val acceptStatus = task.firstString("accept_status")
                if (acceptStatus == "claimed") continue

                val progress = task.objOrNull("progress")
                val target = progress?.firstLong("target")?.takeIf { it > 0 } ?: 1L
                val current = progress?.firstLong("current") ?: 0L
                val done = current >= target
                // 已接单但还没做完：等用户完成，别重复提交
                if (!done && acceptStatus.isNotEmpty()) continue
                val claiming = done && acceptStatus.isNotEmpty()

                val taskCode = task.firstString("task_code")
                if (taskCode.isEmpty()) continue
                val payload = JsonObject().apply { addProperty("task_code", taskCode) }.toString()
                val (code, text) = postJson("$base/tasks/accept", credential, payload, headers)
                if (code !in 200..299) continue
                val title = task.firstString("title").ifEmpty { taskCode }
                val credit = jsonOf(text)?.firstLong("credit") ?: 0L
                parts.add(
                    if (claiming && credit > 0) "领任务奖「$title」+$credit" else "领取任务「$title」",
                )
            }
        }
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
            if (status !in 200..299) {
                return ProviderActionResult.failure(
                    "签到失败（HTTP $status）" + describeBody(text),
                )
            }
            val code = runCatching { JsonParser.parseString(text).asJsonObject.get("code")?.asLong }.getOrNull() ?: -1L
            if (code == 0L) return ProviderActionResult.success("签到成功")
            val message = extractMessage(text)
            if (message.contains("已签到") || message.contains("already")) {
                ProviderActionResult.success("今日已签到")
            } else {
                ProviderActionResult.failure(message.ifEmpty { "签到失败（code=$code）" })
            }
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    // ------------------------------------------------------------------ 凭证

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: return null
        val nowSeconds = nowMillis() / 1000
        if (credential.expiresAt > 0 && credential.expiresAt - nowSeconds > skewSeconds) return null
        if (credential.refreshToken.isEmpty()) return null
        return try {
            val (status, text) = postJson(
                "${chatBase(credential)}$PATH_TOKEN_REFRESH",
                credential,
                null,
                refreshHeaders(credential),
            )
            if (status !in 200..299) return null
            val data = envelopeData(text) ?: return null
            val access = data.get("accessToken")?.asString.orEmpty()
            if (access.isEmpty()) return null
            val expiresIn = data.get("expiresIn")?.asLong ?: 0L
            val refreshed = credential.copy(
                accessToken = access,
                refreshToken = data.get("refreshToken")?.asString.orEmpty().ifEmpty { credential.refreshToken },
                domain = data.get("domain")?.asString.orEmpty().ifEmpty { credential.domain },
                expiresAt = if (expiresIn > 0) nowSeconds + expiresIn else credential.expiresAt,
            )
            toProviderAccount(refreshed)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ 设备授权登录

    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val base = if (region == REGION_GLOBAL) GLOBAL_CHAT_BASE else CN_CHAT_BASE
        val (status, text) = try {
            postJson("$base$PATH_AUTH_STATE?platform=CLI", null, "{}", emptyMap())
        } catch (e: Exception) {
            throw IllegalStateException("请求授权链接失败：${e.message}")
        }
        if (status !in 200..299) throw IllegalStateException("请求授权链接失败（HTTP $status）")
        val data = envelopeData(text) ?: throw IllegalStateException("授权响应异常：${text.take(200)}")
        val state = data.get("state")?.asString.orEmpty()
        val authUrl = data.get("authUrl")?.asString.orEmpty()
        if (state.isEmpty() || authUrl.isEmpty()) throw IllegalStateException("授权响应缺少 state 或 authUrl")
        return DeviceAuthTicket(loginUrl = authUrl, state = state)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val base = if (region == REGION_GLOBAL) GLOBAL_CHAT_BASE else CN_CHAT_BASE
        val encoded = URLEncoder.encode(state, "UTF-8")
        val (status, text) = try {
            getJson("$base$PATH_AUTH_TOKEN?state=$encoded")
        } catch (e: Exception) {
            return DeviceAuthPoll.Failed(e.message ?: "轮询失败")
        }
        if (status !in 200..299) return DeviceAuthPoll.Failed("轮询失败（HTTP $status）")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return DeviceAuthPoll.Failed("轮询响应不是合法 JSON")
        val code = obj.get("code")?.asLong ?: -1L
        if (code == CODE_LOGIN_PENDING) return DeviceAuthPoll.Pending
        if (code != 0L) {
            val message = obj.get("msg")?.asString.orEmpty()
            return DeviceAuthPoll.Failed(message.ifEmpty { "登录未完成（code=$code）" })
        }
        val data = obj.objOrNull("data") ?: return DeviceAuthPoll.Failed("登录响应缺少 data")
        return DeviceAuthPoll.Success(toProviderAccount(credentialOf(data)))
    }

    // ------------------------------------------------------------------ 上游

    private fun fetchModels(credential: Credential): List<ProviderModel> {
        val (status, text) = getJson("${chatBase(credential)}$PATH_MODELS", chatHeaders(credential))
        if (status !in 200..299) throw IllegalStateException("模型接口 HTTP $status")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("模型响应不是合法 JSON")
        if (obj.get("code")?.asLong != 0L) throw IllegalStateException("模型接口 code=${obj.get("code")}")
        val data = obj.objOrNull("data") ?: return emptyList()

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
    ): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept-Encoding", "identity")
        }
        for ((key, value) in headers) conn.setRequestProperty(key, value)
        if (body != null) {
            conn.doOutput = true
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    private fun postJson(
        url: String,
        credential: Credential?,
        body: String?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val conn = open(url, credential, body, headers)
        return try {
            val status = conn.responseCode
            val text = if (status in 200..299) readLimited(conn.inputStream) else readLimited(conn.errorStream)
            status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val conn = open(url, null, null, headers, method = "GET")
        return try {
            val status = conn.responseCode
            val text = if (status in 200..299) readLimited(conn.inputStream) else readLimited(conn.errorStream)
            status to text
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

    private fun chatHeaders(credential: Credential): Map<String, String> = buildMap {
        putAll(commonHeaders(credential))
        put("Authorization", "Bearer ${credential.accessToken}")
        if (credential.uid.isNotEmpty()) put("X-User-Id", credential.uid)
        if (credential.enterpriseId.isNotEmpty()) put("X-Enterprise-Id", credential.enterpriseId)
        if (credential.domain.isNotEmpty()) put("X-Domain", credential.domain)
        put("X-Product", "SaaS")
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
        if (credential.enterpriseId.isNotEmpty()) put("X-Enterprise-Id", credential.enterpriseId)
        put("X-Auth-Refresh-Source", "workbuddy")
    }

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

    private fun regionOf(credential: Credential?): String {
        val domain = credential?.domain.orEmpty().lowercase()
        if (domain.endsWith(".workbuddy.ai") || domain == "workbuddy.ai") return REGION_GLOBAL
        if (domain.isNotEmpty()) return REGION_CN
        return region()
    }

    private fun chatBase(credential: Credential?): String =
        if (regionOf(credential) == REGION_GLOBAL) GLOBAL_CHAT_BASE else CN_CHAT_BASE

    private fun billingBase(credential: Credential?): String =
        if (regionOf(credential) == REGION_GLOBAL) GLOBAL_BILLING_BASE else CN_BILLING_BASE

    private fun originFor(credential: Credential?): String =
        if (regionOf(credential) == REGION_GLOBAL) GLOBAL_ORIGIN else CN_ORIGIN

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
            expiresAt = obj.firstLong("expiresAt", "expires_at"),
            domain = obj.firstString("domain"),
            // 响应里没给时从 access_token（JWT）里解，旧的存量账号也能自动修好
            uid = obj.firstString("uid", "userId", "user_id")
                .ifEmpty { jwtClaim(accessToken, "user_id", "userId", "uid", "sub") },
            enterpriseId = obj.firstString("enterpriseId", "enterprise_id", "entId", "tenantId", "tenant_id")
                .ifEmpty { jwtClaim(accessToken, "tenant_id", "tenantId", "enterprise_id", "enterpriseId") },
            nickname = obj.firstString("nickname", "nickName", "name"),
        )
    }

    /** 从 access_token（JWT）的 payload 里取某个声明；取不到返回空串。 */

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

    private fun matchesQuota(body: String): Boolean {
        val lower = body.lowercase()
        return QUOTA_MARKERS.any { lower.contains(it) || body.contains(it) }
    }

    private fun matchesSessionDead(body: String): Boolean =
        SESSION_DEAD_MARKERS.any { body.contains(it) }

    private fun readLimited(input: InputStream?): String {
        if (input == null) return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BODY_BYTES) {
                out.write(buffer, 0, MAX_BODY_BYTES - out.size())
                break
            }
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun requestedModel(body: String): String =
        runCatching { JsonParser.parseString(body).asJsonObject.get("model")?.asString.orEmpty() }
            .getOrDefault("")

    private fun isStreaming(body: String): Boolean =
        runCatching { JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean ?: false }
            .getOrDefault(false)

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

        const val CN_CHAT_BASE = "https://copilot.tencent.com"
        const val CN_BILLING_BASE = "https://www.codebuddy.cn"
        const val CN_ORIGIN = "https://www.codebuddy.cn"
        const val GLOBAL_CHAT_BASE = "https://www.workbuddy.ai"
        const val GLOBAL_BILLING_BASE = "https://www.workbuddy.ai"
        const val GLOBAL_ORIGIN = "https://www.workbuddy.ai"

        const val CLIENT_UA = "CLI/2.63.2 CodeBuddy/2.63.2"

        const val PATH_AUTH_STATE = "/v2/plugin/auth/state"
        const val PATH_AUTH_TOKEN = "/v2/plugin/auth/token"
        const val PATH_TOKEN_REFRESH = "/v2/plugin/auth/token/refresh"
        const val PATH_CHAT = "/v2/chat/completions"
        const val PATH_MODELS = "/console/enterprises/personal/models"
        const val PATH_USER_RESOURCE = "/v2/billing/meter/get-user-resource"
        const val PATH_CHECKIN = "/v2/billing/meter/daily-checkin"

        /** 成长中心：任务、Buddy 旅行、盲盒都在这个前缀下。 */
        const val PATH_GROWTH = "/v2/activity/growth"

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

        /** 内置模型快照：上游拉不到时兜底（`cli` agent 的常见模型）。 */
        val FALLBACK_MODELS: List<ProviderModel> = listOf(
            ProviderModel("deepseek-v4-flash", "DeepSeek V4 Flash", 128_000),
            ProviderModel("deepseek-v4-pro", "DeepSeek V4 Pro", 128_000),
            ProviderModel("glm-5.2", "GLM-5.2", 128_000),
            ProviderModel("kimi-k2.5", "Kimi-K2.5", 128_000),
            ProviderModel("minimax-m2.5", "MiniMax-M2.5", 128_000),
        )

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
        private const val MAX_BODY_BYTES = 1 shl 20
    }
}

/**
 * 改写发往 CodeBuddy 的请求体：
 * 1. 强制 `stream:true`（上游拒绝非流式）；
 * 2. `tool_choice` 归一化成字符串（上游该字段是 string，对象形式会 400）。
 */
internal fun prepareCodeBuddyBody(src: String): String {
    val obj = runCatching { JsonParser.parseString(src).asJsonObject }.getOrNull() ?: return src
    obj.addProperty("stream", true)
    normalizeCodeBuddyToolChoice(obj)
    return obj.toString()
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

/** 取第一个非空字段（兼容 camelCase / snake_case 两套命名）。 */
internal fun JsonObject.firstString(vararg keys: String): String {
    for (key in keys) {
        val value = get(key) ?: continue
        if (!value.isJsonPrimitive) continue
        val text = runCatching { value.asString }.getOrNull().orEmpty()
        if (text.isNotEmpty()) return text
    }
    return ""
}

internal fun JsonObject.firstLong(vararg keys: String): Long {
    for (key in keys) {
        val value = get(key) ?: continue
        if (!value.isJsonPrimitive) continue
        val number = runCatching { value.asLong }.getOrNull()
        if (number != null && number > 0) return number
    }
    return 0L
}

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

/**
 * 从 access_token（JWT）的 payload 里取某个声明；取不到返回空串。
 *
 * 登录响应里不一定带 uid，但 access_token 是 JWT，里面通常有 user_id ——
 * 靠这个兜底，已经存进账号池的旧凭证也能自动补上 uid。
 */
internal fun jwtClaim(accessToken: String, vararg keys: String): String {
    val payload = accessToken.split('.').getOrNull(1) ?: return ""
    if (payload.isEmpty()) return ""
    val json = runCatching {
        String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
    }.getOrNull() ?: return ""
    val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return ""
    return obj.firstString(*keys)
}
