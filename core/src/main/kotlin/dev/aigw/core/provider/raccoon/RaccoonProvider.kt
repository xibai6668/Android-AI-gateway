package dev.aigw.core.provider.raccoon

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.WebLoginSupport
import dev.aigw.core.provider.WebLoginTicket
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.queryParam
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID

/**
 * 商汤「小浣熊」（`xiaohuanxiong.com`）的插件账号通道。
 *
 * 登录是网页回调：浏览器打开 `/login?appname=Raccoon&redirect=<回调>`，用户登录后重定向回
 * 回调地址并带上 `authorization_code`，再换 access/refresh token。
 *
 * 对话端点 `POST /api/plugin/llm/v1/chat-completions` 虽然字段名接近 OpenAI，但**真正的
 * OpenAI 结构被包在 `data` 里**（外层是 `{"status":{...},"data":{...}}`），且请求体
 * `max_tokens` 保持标准名；响应流带外层包裹，需逐行翻译回标准 OpenAI SSE。
 * 这些差异全部封在本类内部，网关侧零感知。
 */
class RaccoonProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val host: String = HOST,
) : Provider, WebLoginSupport {

    override val id: String = ID
    override val displayName: String = "小浣熊"
    override val authKind: AuthKind = AuthKind.WEBVIEW_CALLBACK
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN)

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

    override fun hosts(): List<String> = listOf("xiaohuanxiong.com")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreamingBody(openAiBody)
        val model = resolveModel(requestedModelOf(openAiBody))
        val body = prepareRaccoonBody(openAiBody)

        val conn = try {
            openChatConnection(credential, body)
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        // 优先用 v2（桌面客户端通道，与能用的反代实现一致）；v1 只在 v2 不存在时回退。
        if (status == 404) {
            conn.disconnect()
            val fallback = try {
                open("$host$PATH_CHAT_V1", body, chatHeaders(credential))
            } catch (e: Exception) {
                return FailedChatCall(0, e.message ?: "连接失败")
            }
            val v1Status = try {
                fallback.responseCode
            } catch (e: Exception) {
                fallback.disconnect()
                return FailedChatCall(0, e.message ?: "连接失败")
            }
            return finishChat(fallback, v1Status, streaming, model)
        }
        return finishChat(conn, status, streaming, model)
    }

    /** v2 优先，v2 不存在时回退 v1。 */
    private fun openChatConnection(credential: Credential, body: String): HttpURLConnection =
        open("$host$PATH_CHAT", body, chatHeaders(credential))

    private fun finishChat(
        conn: HttpURLConnection,
        status: Int,
        streaming: Boolean,
        model: String,
    ): ChatCall {
        if (status !in 200..299) {
            val errorBody = runCatching { readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            val translator = RaccoonSseTranslator(newCompletionId(), model, nowMillis() / 1000)
            val transformed = LineTransformStream(
                source = stream,
                transform = { line -> translator.translate(line) },
                onFinish = { translator.close() },
            )
            if (streaming) {
                return StreamingChatCall(status, transformed) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(transformed, model)
            } finally {
                conn.disconnect()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return emptyUpstreamFailure(status, contentType, aggregated)
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        if (text.isBlank()) return emptyUpstreamFailure(status, contentType, text)
        val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return StreamFailureChatCall(
                status,
                UpstreamError(ErrorKind.CLIENT, "上游响应不是合法 JSON（HTTP $status）：${text.trim().take(200)}"),
            )
        val bizCode = parsed.objOrNull("status")?.longOrNull("code") ?: 0L
        if (bizCode != 0L) {
            val message = extractMessage(text).ifEmpty { "上游业务错误（code=$bizCode）" }
            return StreamFailureChatCall(status, UpstreamError(ErrorKind.CLIENT, message))
        }
        val completion = jsonToCompletion(parsed, model) ?: return StreamFailureChatCall(
            status,
            UpstreamError(ErrorKind.CLIENT, "上游响应缺少 choices（HTTP $status）：${text.trim().take(200)}"),
        )
        if (OpenAiSseAggregator.isEmptyCompletion(completion)) {
            return emptyUpstreamFailure(status, contentType, completion)
        }
        if (streaming) {
            return StreamingChatCall(
                status,
                OpenAiSseAggregator.completionAsSse(completion).byteInputStream(StandardCharsets.UTF_8),
            )
        }
        return AggregatedChatCall(status, completion)
    }

    /** 上游 200 但没给出任何内容：保持可检测的失败，不能报成「成功的空回复」。 */
    private fun emptyUpstreamFailure(status: Int, contentType: String, raw: String): ChatCall = StreamFailureChatCall(
        status,
        UpstreamError(
            ErrorKind.CLIENT,
            "上游返回了空响应（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}" +
                if (raw.isBlank()) "）" else "，片段：${raw.trim().take(120)}）",
        ),
    )

    /** 把上游响应里的 choices 包成标准 `chat.completion`。 */
    private fun jsonToCompletion(envelope: JsonObject, model: String): String? {
        val data = envelope.objOrNull("data")
        val rawChoices = data?.arrayOrNull("choices") ?: envelope.arrayOrNull("choices") ?: return null
        // web 通道非流式的 choices[].delta 是字符串，需转成标准 message；OpenAI 风格的 message 直接透传。
        val choices = JsonArray().apply {
            for (element in rawChoices) {
                val choice = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val out = JsonObject()
                out.addProperty("index", choice.longOrNull("index")?.toInt() ?: 0)
                val message = choice.objOrNull("message")
                when {
                    message != null -> out.add("message", message)
                    choice.get("delta")?.isJsonPrimitive == true -> out.add(
                        "message",
                        JsonObject().apply {
                            addProperty("role", "assistant")
                            addProperty("content", choice.get("delta").asString)
                        },
                    )
                    else -> out.add("message", JsonObject().apply { addProperty("role", "assistant"); addProperty("content", "") })
                }
                out.addProperty("finish_reason", choice.stringOrNull("finish_reason").orEmpty().ifEmpty { "stop" })
                add(out)
            }
        }
        return JsonObject().apply {
            addProperty("id", data?.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: newCompletionId())
            addProperty("object", "chat.completion")
            addProperty("created", nowMillis() / 1000)
            addProperty("model", model)
            add("choices", choices)
            (data?.objOrNull("usage") ?: envelope.objOrNull("usage"))?.let { add("usage", it) }
        }.toString()
    }

    /** 查询可用积分余额（web 通道）。 */
    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = parse(account) ?: return null
        return try {
            val (status, text) = getJson("$host$PATH_BALANCE", jsonHeaders(credential))
            if (status !in 200..299) return CreditInfo(0, known = false, detail = "上游 HTTP $status")
            val data = envelopeData(text) ?: return CreditInfo(0, known = false, detail = "响应缺少 data")
            val points = data.firstLong("available_points", "availablePoints", "points", "balance", "credits")
            CreditInfo(points, known = true, detail = "可用积分 $points")
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询积分失败")
        }
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body).ifEmpty { "上游 HTTP $status" }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 404 -> ErrorKind.NOT_FOUND
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    // ------------------------------------------------------------------ 凭证刷新

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: return null
        val nowSeconds = nowMillis() / 1000
        if (credential.expiresAt > 0 && credential.expiresAt - nowSeconds > skewSeconds) return null
        if (credential.refreshToken.isEmpty()) return null
        // 上游该端点若不可用（404/405/400），安全降级为 null：宁可过期后由 401 触发重新登录，
        // 也不要在这里抛异常或清空凭证误伤账号。
        return runCatching {
            val (status, text) = postJson(
                "$host$PATH_REFRESH",
                JsonObject().apply { addProperty("refresh_token", credential.refreshToken) }.toString(),
                jsonHeaders(credential),
            )
            if (status !in 200..299) return@runCatching null
            val data = envelopeData(text) ?: return@runCatching null
            val access = data.firstString("access_token", "accessToken")
            if (access.isEmpty()) return@runCatching null
            val refreshed = credential.copy(
                accessToken = access,
                refreshToken = data.firstString("refresh_token", "refreshToken").ifEmpty { credential.refreshToken },
                expiresAt = jwtClaimLong(access, "exp") ?: credential.expiresAt,
            )
            toProviderAccount(refreshed, existingUid = account.uid)
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 网页登录

    override fun beginWebLogin(callbackUrl: String, region: String): WebLoginTicket {
        // 不要带 login_source=desktop：授权页会走 `office-raccoon://auth/callback` 自定义 scheme 分支
        // （手机上没有 App 能接），反而不回 redirect。不传时授权页才把 `?authorization_code=…` 回推到 callbackUrl。
        val loginUrl = "$host$PATH_LOGIN" +
            "?appname=" + enc(APP_NAME) +
            "&redirect=" + enc(callbackUrl)
        return WebLoginTicket(randomHex(16), loginUrl, callbackUrl)
    }

    override fun completeWebLogin(callbackUrl: String): ProviderAccount {
        val code = queryParam(callbackUrl, "authorization_code").orEmpty()
        if (code.isEmpty()) throw IllegalStateException("回调链接缺少 authorization_code")
        val (status, text) = postJson(
            "$host$PATH_LOGIN_WITH_CODE",
            JsonObject().apply { addProperty("authorization_code", code) }.toString(),
            emptyMap(),
        )
        if (status !in 200..299) {
            throw IllegalStateException(extractMessage(text).ifEmpty { "换取凭证失败（HTTP $status）" })
        }
        val data = envelopeData(text) ?: throw IllegalStateException("换取凭证响应异常：${text.take(200)}")
        val accessToken = data.firstString("access_token", "accessToken")
        val refreshToken = data.firstString("refresh_token", "refreshToken")
        if (accessToken.isEmpty()) throw IllegalStateException("换取凭证响应缺少 access_token")

        var nickname = jwtClaim(accessToken, "name")
        var pro = false
        var orgCode = ""
        val userId = jwtClaim(accessToken, "iss")
        val userInfo = runCatching { fetchUserInfo(accessToken) }.getOrNull()
        if (userInfo != null) {
            nickname = userInfo.firstString("name", "nickname", "nick_name", "username").ifEmpty { nickname }
            pro = userInfo.boolOrNull("pro") == true
            orgCode = firstOrgCode(userInfo)
        }

        val credential = Credential(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = jwtClaimLong(accessToken, "exp") ?: 0L,
            userId = userId,
            nickname = nickname,
            orgCode = orgCode,
        )
        if (pro) hooks.onLog("小浣熊：账号为 Pro，可用 Pro 模型")
        return toProviderAccount(credential)
    }

    /** 粘贴回调链接导入：与网页登录同一条换取链路。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return completeWebLogin(trimmed)
    }

    /** 每日签到领积分（web 通道）。 */
    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        return try {
            val (status, text) = postJson("$host$PATH_GRANT", "{}", jsonHeaders(credential))
            if (status !in 200..299) {
                return ProviderActionResult.failure(extractMessage(text).ifEmpty { "签到失败（HTTP $status）" })
            }
            val message = extractMessage(text)
            val points = runCatching {
                JsonParser.parseString(text).asJsonObject.objOrNull("data")
                    ?.firstLong("points", "available_points", "amount", "reward", "credits") ?: 0L
            }.getOrDefault(0L)
            ProviderActionResult.success(
                when {
                    points > 0 -> "签到成功，+$points 积分"
                    message.isNotEmpty() -> message
                    else -> "签到成功"
                },
            )
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    // ------------------------------------------------------------------ 上游

    private fun fetchModels(credential: Credential): List<ProviderModel> {
        val (status, text) = getJson("$host$PATH_MODELS", jsonHeaders(credential))
        if (status !in 200..299) throw IllegalStateException("模型接口 HTTP $status")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("模型响应不是合法 JSON")
        val data = obj.get("data") ?: return emptyList()
        val array = when {
            data.isJsonArray -> data.asJsonArray
            data.isJsonObject -> {
                val d = data.asJsonObject
                d.arrayOrNull("models") ?: d.arrayOrNull("profiles") ?: d.arrayOrNull("list") ?: return emptyList()
            }
            else -> return emptyList()
        }
        val result = ArrayList<ProviderModel>()
        val seen = HashSet<String>()
        for (element in array) {
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val modelId = item.firstString("id", "model", "model_id", "modelId", "code")
            if (modelId.isEmpty() || !seen.add(modelId)) continue
            result.add(
                ProviderModel(
                    id = modelId,
                    name = item.firstString("name", "display_name", "displayName", "title", "model_name").ifEmpty { modelId },
                    contextWindow = item.firstLong("context_window", "contextWindow", "max_input_tokens", "maxInputTokens"),
                ),
            )
        }
        return result
    }

    private fun fetchUserInfo(accessToken: String): JsonObject? {
        val (status, text) = getJson("$host$PATH_USER_INFO", bearerHeaders(accessToken))
        if (status !in 200..299) return null
        return envelopeData(text)
    }

    private fun firstOrgCode(userInfo: JsonObject): String {
        val orgs = userInfo.arrayOrNull("orgs") ?: userInfo.arrayOrNull("organizations") ?: return ""
        for (element in orgs) {
            val org = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val code = org.firstString("code", "org_code", "orgCode", "id")
            if (code.isNotEmpty()) return code
        }
        return ""
    }

    // ------------------------------------------------------------------ HTTP

    private fun open(url: String, body: String?, headers: Map<String, String>): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Accept", "text/event-stream, application/json")
        }
        for ((key, value) in headers) conn.setRequestProperty(key, value)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    private fun postJson(url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val conn = open(url, body, headers)
        return try {
            val status = conn.responseCode
            val text = if (status in 200..299) readLimited(conn.inputStream) else readLimited(conn.errorStream)
            status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = open(url, null, headers)
        return try {
            val status = conn.responseCode
            val text = if (status in 200..299) readLimited(conn.inputStream) else readLimited(conn.errorStream)
            status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun jsonHeaders(credential: Credential): Map<String, String> = buildMap {
        putAll(bearerHeaders(credential.accessToken))
        put("User-Agent", CLIENT_UA)
        put("Accept-Language", "zh-Hans")
        if (credential.orgCode.isNotEmpty()) put("X-Org-Code", credential.orgCode)
    }

    private fun bearerHeaders(accessToken: String): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to "Bearer $accessToken",
    )

    /** 对话请求头。web 通道需要伪装成官方桌面客户端。 */
    private fun chatHeaders(credential: Credential): Map<String, String> = buildMap {
        put("Accept", "text/event-stream, application/json")
        put("Content-Type", "application/json")
        put("Authorization", "Bearer ${credential.accessToken}")
        put("User-Agent", CLIENT_UA)
        put("Accept-Language", "zh-Hans")
        if (credential.orgCode.isNotEmpty()) put("X-Org-Code", credential.orgCode)
    }

    // ------------------------------------------------------------------ 工具

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        val access = obj.firstString("accessToken", "access_token")
        if (access.isEmpty()) return null
        return Credential(
            accessToken = access,
            refreshToken = obj.firstString("refreshToken", "refresh_token"),
            expiresAt = obj.firstLong("expiresAt", "expires_at"),
            userId = obj.firstString("userId", "user_id", "uid"),
            nickname = obj.firstString("nickname", "nickName", "name"),
            orgCode = obj.firstString("orgCode", "org_code"),
        )
    }

    private fun toProviderAccount(credential: Credential, existingUid: String = ""): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("accessToken", credential.accessToken)
            addProperty("refreshToken", credential.refreshToken)
            addProperty("expiresAt", credential.expiresAt)
            addProperty("userId", credential.userId)
            addProperty("nickname", credential.nickname)
            if (credential.orgCode.isNotEmpty()) addProperty("orgCode", credential.orgCode)
        }.toString()
        val uid = existingUid.ifEmpty { credential.userId }.ifEmpty {
            "raccoon-" + credential.accessToken.hashCode().toUInt().toString(16)
        }
        return ProviderAccount(id, uid, credential.nickname, secret)
    }

    private fun envelopeData(body: String): JsonObject? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        return obj.objOrNull("data")
    }

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("error")?.stringOrNull("message")?.takeIf { it.isNotEmpty() }?.let { return it }
        for (key in listOf("message", "msg", "details", "detail")) {
            obj.stringOrNull(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

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

    private fun newCompletionId(): String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val userId: String,
        val nickname: String,
        val orgCode: String,
    )

    companion object {
        const val ID = "raccoon"

        const val HOST = "https://xiaohuanxiong.com"

        const val APP_NAME = "Raccoon"

        // ---- web 通道（官网/桌面客户端在用）----
        const val PATH_LOGIN = "/code/authorize"
        const val PATH_LOGIN_WITH_CODE = "/api/web/auth/v1/login_with_authorization_code"
        const val PATH_USER_INFO = "/api/web/auth/v1/user_info"
        const val PATH_REFRESH = "/api/web/auth/v1/refresh"
        const val PATH_CHAT = "/api/web/llm/v2/chat/completions"
        const val PATH_CHAT_V1 = "/api/web/llm/v1/chat/completions"
        const val PATH_MODELS = "/api/web/llm/v2/model_catalog"
        const val PATH_BALANCE = "/api/web/points/v1/balance"

        /** 每日签到领积分。 */
        const val PATH_GRANT = "/api/web/desktop/v1/login/points/grant"

        /** 伪装成官方桌面 Web 客户端的 UA（web 通道的调用方就是它）。 */
        const val CLIENT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 对话默认停止符，与官方 Web 前端一致。 */
        const val DEFAULT_STOP = "<|endofmessage|>"

        /** 内置模型快照：上游拉不到时兜底。 */
        val FALLBACK_MODELS: List<ProviderModel> = listOf(
            ProviderModel("raccoon-chat-ml-5-5", "Raccoon Chat", 128_000),
            ProviderModel("Raccoon-Work", "Raccoon Work", 128_000),
        )

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
        private const val MAX_BODY_BYTES = 1 shl 20

        private val random = SecureRandom()

        fun randomHex(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 把标准 OpenAI 请求体改写为小浣熊上游格式。
 *
 * - 字段名保持 OpenAI 标准（上游 v2 是 LiteLLM 兼容层，`max_tokens` 等标准名直接认；
 *   改名成 `max_new_tokens` 会被上游以 500 unexpected keyword argument 拒绝）；
 * - 保留 `max_tokens`/`temperature`/`top_p`/`frequency_penalty`/`presence_penalty`/`stop`；
 * - **强制 `stream:true`**（上游拒绝非流式），非流式由本类聚合；
 * - `tools` 原样映射，并带上 `tool_choice:"auto"`。
 *
 * 上游只接受上述字段，其它字段（`n`/`user`/`response_format` 等）一律丢弃。
 */
internal fun prepareRaccoonBody(src: String): String {
    val obj = runCatching { JsonParser.parseString(src).asJsonObject }.getOrNull() ?: return src
    return JsonObject().apply {
        obj.stringOrNull("model")?.let { addProperty("model", it) }
        obj.arrayOrNull("messages")?.let { add("messages", it) }
        obj.longOrNull("max_tokens")?.let { addProperty("max_tokens", it) }
        obj.doubleOrNull("temperature")?.let { addProperty("temperature", it) }
        obj.doubleOrNull("top_p")?.let { addProperty("top_p", it) }
        obj.doubleOrNull("frequency_penalty")?.let { addProperty("frequency_penalty", it) }
        obj.doubleOrNull("presence_penalty")?.let { addProperty("presence_penalty", it) }
        addProperty("n", 1)
        addProperty("stream", true)
        // 官方 Web 前端固定用这个停止符；客户端没传时补上，传了则尊重客户端
        val stop = obj.get("stop")?.takeIf { !it.isJsonNull }
        if (stop != null) add("stop", stop) else addProperty("stop", RaccoonProvider.DEFAULT_STOP)
        obj.arrayOrNull("tools")?.let { tools ->
            add("tools", tools)
            addProperty("tool_choice", "auto")
        }
    }.toString()
}

/**
 * 小浣熊上游 SSE → 标准 OpenAI SSE 的逐行翻译器。
 *
 * 上游每行形如 `data: {"status":{"code":0},"data":{"choices":[...]}}`，真正的 OpenAI
 * 结构在 `data` 里；以 `data: [DONE]` 结束，另有 `: ping` 心跳行需忽略。逐行转换，
 * 返回值即待写出的 SSE 文本，便于单测断言。
 */
internal class RaccoonSseTranslator(
    private val id: String,
    private val model: String,
    private val created: Long,
) {
    private var done = false
    private var frameId = id

    fun translate(rawLine: String): List<String> {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith(":")) return emptyList()
        if (!line.startsWith("data:")) return emptyList()
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return emptyList()
        if (payload == "[DONE]") {
            done = true
            return listOf(SSE_DONE)
        }
        val envelope = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return emptyList()
        val status = envelope.objOrNull("status")
        val code = status?.longOrNull("code") ?: 0L
        if (code != 0L) {
            done = true
            val message = status?.stringOrNull("message").orEmpty().ifEmpty { "上游流内错误（code=$code）" }
            return listOf(errorFrame(code, message), SSE_DONE)
        }
        // 上游流式帧带外层包裹（`{"status":..,"data":{...}}`）；也兼容直接是 OpenAI chunk 的情形。
        val data = envelope.objOrNull("data") ?: envelope
        envelope.objOrNull("data")?.stringOrNull("id")?.let { frameId = it }
        val choices = data.arrayOrNull("choices") ?: return emptyList()
        val out = ArrayList<String>(1)
        for (element in choices) {
            val choice = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            // web 通道的 delta 是**字符串**（正文片段）；OpenAI 风格的 delta 是对象，两者都兼容。
            val deltaElement = choice.get("delta")
            val delta: JsonObject = when {
                deltaElement == null || deltaElement.isJsonNull -> JsonObject()
                deltaElement.isJsonObject -> deltaElement.asJsonObject
                deltaElement.isJsonPrimitive -> JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", deltaElement.asString)
                }
                else -> JsonObject()
            }
            val finishReason = choice.stringOrNull("finish_reason")
            if (delta.size() == 0 && finishReason == null) continue
            out.add(chunk(choice.longOrNull("index")?.toInt() ?: 0, delta, finishReason))
        }
        return out
    }

    fun close(): List<String> = if (done) emptyList() else listOf(SSE_DONE)

    private fun chunk(index: Int, delta: JsonObject, finishReason: String?): String {
        val choice = JsonObject().apply {
            addProperty("index", index)
            add("delta", delta)
            if (finishReason != null) addProperty("finish_reason", finishReason)
        }
        val obj = JsonObject().apply {
            addProperty("id", frameId)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
        }
        return "data: $obj\n\n"
    }

    /** 用 OpenAI 标准错误帧（顶层 error 对象）：网关的 parseChunk 能识别它并把调用记为失败。 */
    private fun errorFrame(code: Long, message: String): String {
        val obj = JsonObject().apply {
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
                addProperty("type", ErrorKind.CLIENT.name)
            })
        }
        return "data: $obj\n\n"
    }

    companion object {
        const val SSE_DONE = "data: [DONE]\n\n"
    }
}

private fun JsonObject.doubleOrNull(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asDouble

/** 取第一个非空字符串字段（兼容 camelCase / snake_case 两套命名）。 */
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

/** 从 JWT 的 payload 里取某个字符串声明；取不到返回空串。 */
internal fun jwtClaim(accessToken: String, key: String): String {
    val obj = jwtPayload(accessToken) ?: return ""
    return obj.firstString(key)
}

/** 从 JWT 的 payload 里取某个数值声明（如 exp）；取不到返回 null。 */
internal fun jwtClaimLong(accessToken: String, key: String): Long? {
    val obj = jwtPayload(accessToken) ?: return null
    return obj.get(key)?.takeIf { it.isJsonPrimitive }?.asLong
}

private fun jwtPayload(accessToken: String): JsonObject? {
    val payload = accessToken.split('.').getOrNull(1) ?: return null
    if (payload.isEmpty()) return null
    val json = runCatching {
        String(java.util.Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
    }.getOrNull() ?: return null
    return runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
}
