package dev.aigw.core.provider.custom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AudioTranscriptionRequest
import dev.aigw.core.provider.AudioTranscriptionResult
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.MediaCall
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.bodySnippet
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.net.HttpURLConnection

/**
 * 用户手动导入的 OpenAI 兼容供应商。
 *
 * 无登录流程，凭证就是 API Key；配多个 key 时每个 key 是账号池里的一个「账号」，
 * 由账号池做轮询。模型列表以用户手填为准，也可用 [fetchModels] 从 `/v1/models` 拉取。
 */
class CustomProvider(
    private val config: CustomProviderConfig,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider {

    override val id: String = config.providerId
    override val displayName: String = config.name
    override val authKind: AuthKind = AuthKind.NONE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView =
        ProviderModelCatalogView(
            models = config.models.map { ProviderModel(id = it, name = it) },
            fromFallback = false,
            error = "",
        )

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return config.models.firstOrNull().orEmpty()
        return model
    }

    override fun hosts(): List<String> = runCatching {
        java.net.URI(config.baseUrl.trim()).host
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?.let { listOf(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val key = parseKey(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreaming(openAiBody)
        val model = requestedModel(openAiBody)

        val conn = try {
            openConnection(chatUrl(config.baseUrl), key, openAiBody, "POST")
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
            val body = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, body)
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
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return emptyUpstreamFailure(status, contentType, aggregated)
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        // 200 但空 body（代理吞响应、上游静默拒绝）：当成功会让客户端「输出完成却什么都没有」
        if (text.isBlank()) return emptyUpstreamFailure(status, contentType, text)
        // 非流式响应必须是 OpenAI completion；HTML 登录页之类不能当回复透传
        if (!isChatCompletion(text)) {
            return StreamFailureChatCall(
                status,
                UpstreamError(
                    ErrorKind.CLIENT,
                    "上游响应不符合 OpenAI 协议（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}）：${text.trim().take(200)}",
                ),
            )
        }
        // 客户端要流式但上游只回了普通 JSON：包成 SSE，严格客户端才能解析出内容
        if (streaming) {
            return StreamingChatCall(status, OpenAiSseAggregator.completionAsSse(text).byteInputStream(Charsets.UTF_8))
        }
        return AggregatedChatCall(status, text)
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

    /** 是否是合法的 OpenAI completion（非流式响应）。 */
    private fun isChatCompletion(body: String): Boolean {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return false
        return obj.get("choices")?.isJsonArray == true
    }

    /**
     * 自定义供应商是 OpenAI 兼容中转，`/audio/transcriptions` 与对话同一个根地址，
     * 因此转写能力天然可透传；其余基于私有网页协议的上游没有公开转写端点，保持默认 false。
     */
    override val supportsAudioTranscription: Boolean get() = true

    override fun transcribeAudio(
        account: ProviderAccount,
        request: AudioTranscriptionRequest,
    ): MediaCall<AudioTranscriptionResult> {
        val key = parseKey(account) ?: return MediaCall.Failure(401, "凭证无法解析")

        val conn = try {
            openConnection(audioUrl(config.baseUrl), key, request.rawBody, request.rawContentType, "POST")
        } catch (e: Exception) {
            return MediaCall.Failure(0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return MediaCall.Failure(0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val body = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return MediaCall.Failure(status, body)
        }

        val text = runCatching { conn.inputStream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        if (text.isBlank()) return MediaCall.Failure(status, "上游返回空响应（HTTP $status）")

        // response_format=text 时上游直接回纯文本，没有 JSON 可解
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return MediaCall.Success(AudioTranscriptionResult(text.trim()))
        val duration = runCatching { obj.get("duration")?.takeIf { it.isJsonPrimitive }?.asDouble }
            .getOrNull() ?: 0.0
        return MediaCall.Success(
            AudioTranscriptionResult(
                text = obj.stringOrNull("text").orEmpty(),
                language = obj.stringOrNull("language").orEmpty(),
                durationSeconds = duration,
            ),
        )
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body).ifEmpty { "上游 HTTP $status" + bodySnippet(body) }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 404 -> ErrorKind.NOT_FOUND
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            status == 400 || status == 422 -> ErrorKind.CLIENT
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    /** 自定义供应商没有额度接口；返回 null 表示「不支持」。 */
    override fun creditInfo(account: ProviderAccount): CreditInfo? = null

    /** 已保存的模型列表（UI 读取展示用）。 */
    fun savedModels(): List<String> = config.models

    /** 接口地址（UI 读取展示用）。 */
    val baseUrl: String get() = config.baseUrl

    /** 支持粘贴裸 API Key（或 `{"apiKey":"…"}` 形式的 JSON）。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val apiKey = if (trimmed.startsWith("{")) {
            runCatching { JsonParser.parseString(trimmed).asJsonObject.get("apiKey")?.asString }
                .getOrNull().orEmpty()
        } else {
            trimmed
        }
        if (apiKey.isEmpty()) return null
        return ProviderAccount(id, uidOf(apiKey), config.name, secretOf(apiKey))
    }

    // ------------------------------------------------------------------ 内部

    private fun parseKey(account: ProviderAccount): String? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull()
        return obj?.get("apiKey")?.asString?.takeIf { it.isNotEmpty() }
    }

    private fun requestedModel(body: String): String =
        runCatching { JsonParser.parseString(body).asJsonObject.get("model")?.asString.orEmpty() }
            .getOrDefault("")

    private fun isStreaming(body: String): Boolean =
        runCatching { JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean ?: false }
            .getOrDefault(false)

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("error")?.let { error ->
            error.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (key in listOf("message", "msg", "detail")) {
            obj.get(key)?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    companion object {
        const val KEY_PREFIX = "key-"

        /**
         * 把用户填的接口地址规范成「根地址 + 版本段」。
         *
         * 用户抄来的地址五花八门：只填域名、填到 `/v1`、直接贴 `/v1/chat/completions`。
         * 不规范化时 `https://api.x.com` 会被拼成 `https://api.x.com/chat/completions`（缺 `/v1`，必 404），
         * 而界面上看不出问题。规则：去掉对话/模型端点尾巴，末尾不是版本段（`/v1`、`/api/v4`）就补 `/v1`。
         */
        fun normalizeBaseUrl(raw: String): String {
            var base = raw.trim().trimEnd('/')
            if (base.isEmpty()) return ""
            for (suffix in listOf("/chat/completions", "/audio/transcriptions", "/models")) {
                if (base.length > suffix.length && base.endsWith(suffix, ignoreCase = true)) {
                    base = base.substring(0, base.length - suffix.length).trimEnd('/')
                }
            }
            if (base.isEmpty()) return ""
            val path = runCatching { java.net.URI(base).path.orEmpty() }.getOrDefault("")
            val hasVersion = VERSION_SUFFIX.containsMatchIn(path)
            return if (hasVersion) base else "$base/v1"
        }

        /** 实际会请求的对话地址，给界面做预览用。 */
        fun chatEndpointOf(baseUrl: String): String = chatUrl(baseUrl)

        /** 实际会请求的模型目录地址，给界面做预览用。 */
        fun modelsEndpointOf(baseUrl: String): String = modelsUrl(baseUrl)

        /**
         * 用给定的 baseUrl 与 key 拉模型列表。
         *
         * 不依赖已保存的配置：新建供应商时用户还没保存，也要能先拉一次看看有哪些模型。
         * 失败原因必须原样带回：所有错误都压成「空列表」时，界面只能笼统地说
         * 「检查地址与 Key」，用户无从判断是 401、404 还是网络不通。
         */
        fun fetchModels(baseUrl: String, apiKey: String): ModelFetchResult {
            if (baseUrl.isBlank()) return ModelFetchResult(emptyList(), "先填写接口地址")
            val conn = try {
                openConnection(modelsUrl(baseUrl), apiKey, null, "GET")
            } catch (e: Exception) {
                return ModelFetchResult(emptyList(), "连接失败：${e.message ?: "未知错误"}")
            }
            return try {
                val status = conn.responseCode
                if (status !in 200..299) {
                    val body = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
                    return ModelFetchResult(emptyList(), describeModelFetchFailure(status, baseUrl, body))
                }
                val text = HttpSupport.readLimited(conn.inputStream)
                val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    ?: return ModelFetchResult(emptyList(), "返回的不是模型列表（可能不是 OpenAI 兼容接口）")
                val data = obj.arrayOrNull("data")
                    ?: return ModelFetchResult(emptyList(), "返回里没有 data 字段（可能不是 OpenAI 兼容接口）")
                val models = data.mapNotNull { element ->
                    val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                    item.get("id")?.asString?.takeIf { it.isNotEmpty() }
                }
                if (models.isEmpty()) {
                    ModelFetchResult(emptyList(), "上游没有返回任何模型，换一个 Key 或手动填写模型 id")
                } else {
                    ModelFetchResult(models, "")
                }
            } catch (e: Exception) {
                ModelFetchResult(emptyList(), "拉取失败：${e.message ?: "未知错误"}")
            } finally {
                conn.disconnect()
            }
        }

        /** 把 HTTP 状态翻译成用户能照着修的一句话。 */
        private fun describeModelFetchFailure(status: Int, baseUrl: String, body: String): String {
            val snippet = body.trim().take(160).ifEmpty { "（空）" }
            return when (status) {
                401, 403 -> "HTTP $status：Key 无效，或这个 Key 没有查看模型的权限。原始返回：$snippet"
                404 -> "HTTP 404：接口地址不对，已按 ${modelsUrl(baseUrl)} 请求。原始返回：$snippet"
                429 -> "HTTP 429：请求太频繁或额度用尽，稍后再试"
                in 500..599 -> "HTTP $status：上游服务异常，稍后再试"
                else -> "HTTP $status：$snippet"
            }
        }

        private fun openConnection(
            url: String,
            key: String,
            body: String?,
            method: String,
        ): HttpURLConnection = openConnection(
            url = url,
            key = key,
            body = body?.toByteArray(Charsets.UTF_8),
            contentType = if (body != null) "application/json" else null,
            method = method,
        )

        /**
         * 字节体版本：语音转写的 multipart 不能按 UTF-8 编码来回倒一手，
         * 且 Content-Type（含 boundary）必须用客户端原值，不能写死 application/json。
         */
        private fun openConnection(
            url: String,
            key: String,
            body: ByteArray?,
            contentType: String?,
            method: String,
        ): HttpURLConnection {
            val conn = HttpSupport.openWithHeaders(
                url = url,
                method = method,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                body = null,
            ) { conn ->
                conn.setRequestProperty("Accept", "text/event-stream, application/json")
                if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            return conn
        }

        /** 转写端点：去掉 baseUrl 尾部的对话/模型路径后挂 `/audio/transcriptions`。 */
        private fun audioUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/audio/transcriptions"

        private fun chatUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/chat/completions"

        private fun modelsUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/models"

        /** 自定义供应商的账号 uid 由 key 派生（只用于区分账号，不泄露完整 key）。 */
        fun uidOf(apiKey: String): String =
            KEY_PREFIX + apiKey.hashCode().toUInt().toString(16).padStart(8, '0')

        fun secretOf(apiKey: String): String =
            JsonObject().apply { addProperty("apiKey", apiKey) }.toString()

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000

        /** 路径末尾的版本段：`/v1`、`/v1beta`、`/api/v4` 这类都算已经带了版本。 */
        private val VERSION_SUFFIX = Regex("/v\\d+[a-z0-9]*$", RegexOption.IGNORE_CASE)
    }
}

/** 拉模型的结果：要么有模型，要么有一条能照着修的错误说明。 */
data class ModelFetchResult(
    val models: List<String>,
    val error: String = "",
)
