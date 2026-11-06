package dev.aigw.core.provider.antigravity

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.AudioTranscriptionRequest
import dev.aigw.core.provider.AudioTranscriptionResult
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.GeneratedImage
import dev.aigw.core.provider.ImageGenerationRequest
import dev.aigw.core.provider.ImageGenerationResult
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.LoopbackOAuthSupport
import dev.aigw.core.provider.MediaCall
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.SSE_DONE
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.queryParam
import dev.aigw.core.protocol.OpenAiGemini
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.bodySnippet
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.newCompletionId
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Google Antigravity（`agy` CLI 背后的统一网关）。
 *
 * 登录是标准 Google OAuth：WebView 打开授权页，拦截回调 URL（`http://localhost:51121/oauth-callback`）
 * 拿 code 换 token，再用 `loadCodeAssist` 取 project id。对话走 Gemini 风格协议，
 * 由 [OpenAiGemini] 做双向转换。
 */
class AntigravityProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val apiBase: String = API_BASE,
    private val quotaBase: String = QUOTA_BASE,
    private val chatBase: String = CHAT_BASE,
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
    /** 运行时 UA 里的客户端版本；默认取官方清单的最新版（见 [AntigravityVersion]）。 */
    private val clientVersion: () -> String = { AntigravityVersion.current() },
) : Provider, LoopbackOAuthSupport, dev.aigw.core.provider.ConversationalTranscriber {

    override val id: String = ID
    override val displayName: String = "Antigravity"
    override val authKind: AuthKind = AuthKind.OAUTH_LOOPBACK

    // ------------------------------------------------------------------ 模型

    /** 云端拉取的模型清单缓存；null = 还没拉过或拉取失败。 */
    @Volatile
    private var fetchedModels: List<ProviderModel>? = null

    private var fetchedAtMillis = 0L

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val parsed = account?.let { runCatching { parse(it) }.getOrNull() }
        if (parsed != null) {
            val cached = fetchedModels
            if (cached != null && nowMillis() - fetchedAtMillis < MODEL_CACHE_TTL_MILLIS) {
                return ProviderModelCatalogView(models = cached, fromFallback = false, error = "")
            }
            // 不主动刷新（凭证策略同 openChat），直接用当前 Token；过期则走 fallback
            var lastErr = ""
            val fetched = try {
                fetchAvailableModels(parsed)
            } catch (e: Exception) {
                lastErr = e.message ?: "拉取失败"
                null
            }
            if (!fetched.isNullOrEmpty()) {
                fetchedModels = fetched
                fetchedAtMillis = nowMillis()
                return ProviderModelCatalogView(models = fetched, fromFallback = false, error = "")
            }
            // 若云端拉取失败但之前有成功缓存，继续回退使用之前的缓存，避免列表突然变空；
            // 但这已不是本次拉取的结果，必须如实置位，界面据此标注「缓存」
            val previous = fetchedModels
            if (!previous.isNullOrEmpty()) {
                return ProviderModelCatalogView(
                    models = previous,
                    fromFallback = true,
                    error = lastErr.ifEmpty { "上游未返回模型，展示上次成功的结果" },
                )
            }
            return ProviderModelCatalogView(models = emptyList(), fromFallback = false, error = lastErr)
        }
        return ProviderModelCatalogView(models = emptyList(), fromFallback = false, error = "")
    }

    override fun invalidateModelCache() {
        // 只让缓存过期（保留 fetchedModels 作失败兜底），下次 listModels 必然重拉上游
        fetchedAtMillis = 0L
    }

    /**
     * 从云端拉取可用模型（优先 daily 端点，失败降级 prod，与 CLIProxyAPI 一致）：
     * `POST /v1internal:fetchAvailableModels`，响应 `models` 是
     * `{模型id: {displayName, maxTokens, maxOutputTokens}}` 的 map。
     * 跳过上游保留的内部/实验模型。
     */
    private fun fetchAvailableModels(credential: Credential): List<ProviderModel> {
        val payload = if (credential.projectId.isNotEmpty()) {
            JsonObject().apply { addProperty("project", credential.projectId) }.toString()
        } else {
            "{}"
        }
        val endpoints = listOf(
            "$chatBase/$API_VERSION:fetchAvailableModels",
            "$apiBase/$API_VERSION:fetchAvailableModels",
        )
        var lastStatus = 0
        var lastBody = ""
        for (url in endpoints) {
            val (status, text) = try {
                postJson(url, credential.accessToken, payload, connectTimeout = 8_000, readTimeout = 12_000)
            } catch (e: Exception) {
                lastBody = e.message ?: "连接失败"
                continue
            }
            lastStatus = status
            lastBody = text
            if (status in 200..299) {
                val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    ?: continue
                val models = root.get("models")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: continue
                val result = ArrayList<ProviderModel>()
                for ((id, value) in models.entrySet()) {
                    val modelId = id.trim()
                    if (modelId.isEmpty() || EXCLUDED_MODELS.contains(modelId)) continue
                    val meta = value as? JsonObject
                    val displayName = meta?.stringOrNull("displayName").orEmpty().ifEmpty { modelId }
                    // 上游未给 maxTokens 时不填（0 = 界面不展示），不要拿一个常量冒充上下文窗口
                    val contextLength = meta?.longOrNull("maxTokens")?.takeIf { it > 0 } ?: 0L
                    // 音频能力只采上游真实声明的字段；没声明就当不支持，不靠模型名猜
                    val supportsAudio = meta?.let { declaredAudio(it) } == true
                    result.add(
                        ProviderModel(
                            id = modelId,
                            name = displayName,
                            contextWindow = contextLength,
                            extra = if (supportsAudio) mapOf(AUDIO_CAPABILITY to "1") else emptyMap(),
                        ),
                    )
                }
                if (result.isNotEmpty()) {
                    result.sortBy { it.id }
                    return result
                }
            }
        }
        throw IllegalStateException("从云端获取模型失败（HTTP $lastStatus）：${lastBody.take(160)}")
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim().removePrefix("models/").trim()
        val mapped = MODEL_ALIASES[model.lowercase()]
        if (mapped != null) return mapped
        if (model.isEmpty() || model.equals("auto", ignoreCase = true)) {
            return fetchedModels?.firstOrNull()?.id ?: "gemini-3.8-flash-high"
        }
        val lower = model.lowercase()
        return when {
            // 先认「列表里真有的模型」：gemini-4-argon 含 pro 子串，若按 contains 判断会被改写成
            // gemini-pro-agent，用户选的模型就悄悄变了
            fetchedModels?.any { it.id.equals(model, ignoreCase = true) } == true -> model
            lower.contains("flash") -> "gemini-3.8-flash-high"
            lower.contains("pro") -> "gemini-pro-agent"
            lower.contains("claude") || lower.contains("sonnet") || lower.contains("opus") -> "claude-sonnet-4-6"
            else -> model
        }
    }

    /** 全部走 Google 域名（含 cloudcode-pa / oauth2 / www.googleapis.com）。 */
    override fun hosts(): List<String> = listOf("googleapis.com", "accounts.google.com")

    // ------------------------------------------------------------------ 对话

    /**
     * 对话凭证策略（回归 0.1.39 的稳定行为）：
     * provider 内部**绝不主动刷新 Token**——刷新由网关在请求前统一调 [refreshAccount]（有
     * settings.refreshSkewSeconds 控制频率，默认临期才刷）。每次对话前都打 OAuth 会触发
     * Google 风控吊销 refresh_token，这是「越修越频繁失效」的根因，严禁回退。
     */
    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        if (credential.projectId.isEmpty()) {
            return FailedChatCall(400, "该账号缺少 project id，请重新登录以获取")
        }
        val streaming = isStreaming(openAiBody)
        val model = resolveModel(requestedModel(openAiBody))
        val request = OpenAiGemini.toGeminiRequest(openAiBody, model)
        // 非 claude 模型删 maxOutputTokens（上游 gemini 系不接受该字段，会 400）
        val isClaude = model.contains("claude", ignoreCase = true)
        if (!isClaude) {
            request.objOrNull("generationConfig")?.remove("maxOutputTokens")
        }
        val body = OpenAiGemini.envelope(credential.projectId, model, request, OpenAiGemini.newRequestId())
        // 对话走 daily 端点（与 CLIProxyAPI v7 默认一致）：prod 端点对消费级账号（含 Gemini Pro 会员）
        // 会直接回 429 RESOURCE_EXHAUSTED，与额度无关。因此对话域名必须独立于配额域名——
        // 配额查询反过来只有 prod 给完整数据，两者不能共用一个 base。
        hooks.onVerbose?.invoke("Antigravity 发送内容", "$chatBase/$API_VERSION:streamGenerateContent?alt=sse\n$body")

        val session = connectStream(credential, body)
        val conn = session.conn ?: return FailedChatCall(session.status, session.errorBody)
        val status = session.status

        val translator = OpenAiGemini.SseTranslator(newCompletionId(), model)
        val transformed = LineTransformStream(
            source = conn.inputStream,
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
        return AggregatedChatCall(status, aggregated)
    }

    /**
     * 建连并判定状态码，[openChat] 与 [generateImage] 共用。
     *
     * 成功时返回**仍打开**的 [UpstreamStream.conn]（调用方负责读响应并 disconnect）；
     * 连接失败、读状态码失败、非 2xx 一律返回 conn = null，连接已断开。
     */
    private fun connectStream(credential: Credential, body: String): UpstreamStream {
        val conn = try {
            open("$chatBase/$API_VERSION:streamGenerateContent?alt=sse", credential.accessToken, body)
        } catch (e: Exception) {
            return UpstreamStream(null, 0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return UpstreamStream(null, 0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return UpstreamStream(null, status, errorBody)
        }
        return UpstreamStream(conn, status, "")
    }

    private class UpstreamStream(val conn: HttpURLConnection?, val status: Int, val errorBody: String)

    // ------------------------------------------------------------------ 图像

    override val supportsImageGeneration: Boolean = true

    /** 音频能否用，取决于当前账号的模型目录里有没有声明音频输入；见 [transcribeAudio]。 */
    override val supportsAudioTranscription: Boolean = true

    /**
     * 语音转写：走 Gemini 多模态通道，把音频当 `inlineData` 喂给模型并让它逐字转写。
     *
     * 这不是 ASR 引擎，是对话模型「听懂后复述」：长音频会漏字、专名易错，
     * 所以只作为没有原生转写端点时的备用通道，不能宣称为 whisper 等价。
     * 凭证策略与 [openChat] 一致：内部绝不主动刷 Token。
     */
    override fun transcribeAudio(
        account: ProviderAccount,
        request: AudioTranscriptionRequest,
    ): MediaCall<AudioTranscriptionResult> {
        val credential = parse(account) ?: return MediaCall.Failure(401, "凭证无法解析")
        if (credential.projectId.isEmpty()) {
            return MediaCall.Failure(400, "该账号缺少 project id，请重新登录以获取")
        }
        val model = audioModelOf(request.model, account)
            ?: return MediaCall.Failure(
                400,
                "该账号的模型目录里没有声明音频输入的模型，无法用对话通道转写（可改用支持 /audio/transcriptions 的自定义供应商）",
            )
        val instruction = transcriptionInstruction(request.prompt, request.language)
        val openAiBody = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", true)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "input_audio")
                            add("input_audio", JsonObject().apply {
                                addProperty("data", Base64.getEncoder().encodeToString(request.audio))
                                addProperty("format", mimeOrFormat(request.mimeType))
                            })
                        })
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", instruction)
                        })
                    })
                })
            })
        }.toString()
        // 上一行的 parts 由 OpenAiGemini 统一翻译：input_audio → inlineData，图片 → 图片
        val geminiRequest = OpenAiGemini.toGeminiRequest(openAiBody, model)
        geminiRequest.objOrNull("generationConfig")?.remove("maxOutputTokens")
        val body = OpenAiGemini.envelope(credential.projectId, model, geminiRequest, OpenAiGemini.newRequestId())
        hooks.onVerbose?.invoke("Antigravity 发送内容", "$chatBase/$API_VERSION:streamGenerateContent?alt=sse\n$body")

        val session = connectStream(credential, body)
        val conn = session.conn ?: return MediaCall.Failure(session.status, session.errorBody)
        val text = try {
            readTranscription(conn.inputStream, model)
        } catch (e: Exception) {
            return MediaCall.Failure(0, e.message ?: "读取上游响应失败")
        } finally {
            conn.disconnect()
        }
        if (text.isBlank()) return MediaCall.Failure(502, "上游未返回转写文本")
        return MediaCall.Success(AudioTranscriptionResult(text = text.trim(), language = request.language))
    }

    /**
     * 选用于转写的模型。
     *
     * 只在**模型目录真的声明了音频输入**时才用对话通道：凭空挑一个文本模型喂音频，
     * 上游会把音频丢掉并回一段与录音无关的回答，那就是对客造假。
     */
    private fun audioModelOf(requested: String, account: ProviderAccount): String? {
        val listed = runCatching { listModels(account).models }.getOrDefault(emptyList())
        val audioCapable = listed.filter { it.extra[AUDIO_CAPABILITY] == "1" }
        val model = requested.trim().removePrefix("models/").trim()
        if (model.isNotEmpty()) {
            val exact = audioCapable.firstOrNull { it.id.equals(model, ignoreCase = true) }
            if (exact != null) return exact.id
        }
        return audioCapable.firstOrNull()?.id
    }

    /**
     * 上游模型元数据里是否真的声明了音频输入。
     *
     * `fetchAvailableModels` 各家实现口径不一，这里只认几种明确字段
     * （`inputModalities` / `supportedInputModalities` / `input_modalities` 含 `audio`），
     * 都不存在就当不支持：宁可报“没有音频模型”，也不能拿一个文本模型去编转写结果。
     */
    private fun declaredAudio(meta: JsonObject): Boolean {
        val keys = listOf("inputModalities", "supportedInputModalities", "input_modalities", "supported_input_modalities")
        for (key in keys) {
            val list = meta.arrayOrNull(key) ?: continue
            if (list.any { it.isJsonPrimitive && it.asString.equals("audio", ignoreCase = true) }) return true
        }
        return false
    }

    /** 逐帧读 SSE 并拼出 `delta.content`；`reasoning_content` 不算转写正文。 */
    private fun readTranscription(stream: java.io.InputStream, model: String): String {
        val translator = OpenAiGemini.SseTranslator(newCompletionId(), model)
        val text = StringBuilder()
        stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                for (frame in translator.translate(line)) {
                    if (frame == SSE_DONE) continue
                    val payload = frame.removePrefix("data:").trim()
                    val chunk = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
                    chunk.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
                        ?.objOrNull("delta")?.stringOrNull("content")?.let { text.append(it) }
                }
            }
        }
        return text.toString()
    }

    /** 转写指令：要求只输出正文，并把调用方的 prompt / language 作为上下文。 */
    private fun transcriptionInstruction(prompt: String, language: String): String = buildString {
        append("把这段录音逐字转写成文本，只输出转写正文，不要解释、不要加标题或引言。")
        if (language.isNotEmpty()) append(" 录音语言是 $language。")
        if (prompt.isNotEmpty()) append(" 上下文提示：$prompt")
    }

    /** OpenAI 的 `format` 是裸格式名（wav/mp3），这边拿到的可能是 MIME，统一取裸格式。 */
    private fun mimeOrFormat(mimeType: String): String =
        mimeType.substringAfter('/', mimeType).ifEmpty { "wav" }

    /**
     * 图像生成：与对话共用 `streamGenerateContent` 通道（模型名含 image 时
     * [OpenAiGemini.envelope] 会把 requestType 设为 image_gen）。
     *
     * 上游回图是 parts[].inlineData，[OpenAiGemini.SseTranslator] 已把它翻成 OpenAI 的
     * `delta.images[].image_url.url`（data URL，含 mime）；这里逐帧收集并拆回 base64。
     * 凭证策略与 [openChat] 完全一致：内部绝不主动刷新 Token。
     */
    override fun generateImage(account: ProviderAccount, request: ImageGenerationRequest): MediaCall<ImageGenerationResult> {
        val credential = parse(account) ?: return MediaCall.Failure(401, "凭证无法解析")
        if (credential.projectId.isEmpty()) {
            return MediaCall.Failure(400, "该账号缺少 project id，请重新登录以获取")
        }
        val model = imageModelOf(request.model, account)
        val openAiBody = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", false)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", request.prompt)
                })
            })
        }.toString()
        val geminiRequest = OpenAiGemini.toGeminiRequest(openAiBody, model)
        // 与 openChat 同一处理：上游 gemini 系不接受 maxOutputTokens
        geminiRequest.objOrNull("generationConfig")?.remove("maxOutputTokens")
        // image_gen 的 requestId 是上游专用格式 image_gen/<毫秒>/<uuid>/12（CLIProxyAPI 同款）
        val requestId = "image_gen/${nowMillis()}/${UUID.randomUUID()}/12"
        val body = OpenAiGemini.envelope(credential.projectId, model, geminiRequest, requestId)
        hooks.onVerbose?.invoke("Antigravity 发送内容", "$chatBase/$API_VERSION:streamGenerateContent?alt=sse\n$body")

        val session = connectStream(credential, body)
        val conn = session.conn ?: return MediaCall.Failure(session.status, session.errorBody)
        val images = try {
            readImages(conn.inputStream, model)
        } catch (e: Exception) {
            return MediaCall.Failure(0, e.message ?: "读取上游响应失败")
        } finally {
            conn.disconnect()
        }
        if (images.isEmpty()) return MediaCall.Failure(502, "上游未返回图片")
        return MediaCall.Success(ImageGenerationResult(images))
    }

    /**
     * 解析图像模型名。
     *
     * 上游真实的图像模型都叫 `gemini-*-image`，按原样下传（不能走 [resolveModel]：那里的
     * flash/pro 子串规则会把 `gemini-3.6-flash-image` 改写成文本模型，requestType 也就退化成 agent）。
     * 客户端给的 `dall-e-3` / `gpt-image-*` / 空串是别家的通用名，先从上游目录里找一个真实存在的
     * 图像模型；目录里也没有就交给 [resolveModel] 原样下传，让上游判定——绝不凭空编造模型名。
     */
    private fun imageModelOf(requested: String, account: ProviderAccount): String {
        val model = requested.trim().removePrefix("models/").trim()
        if (model.contains("gemini", ignoreCase = true) && model.contains("image", ignoreCase = true)) {
            return MODEL_ALIASES[model.lowercase()] ?: model
        }
        val listed = runCatching { listModels(account).models }.getOrDefault(emptyList())
        listed.firstOrNull { it.id.contains("image", ignoreCase = true) }?.let { return it.id }
        // 空模型名不能交给 resolveModel：那里会回退到目录里的第一个文本模型（等于凭空编造），
        // 空串留给上游直接拒绝，错误原文比“生成了一张文本回复”诚实
        return if (model.isEmpty()) model else resolveModel(model)
    }

    /** 逐帧读 SSE，把翻译后的 `delta.images[].image_url.url`（data URL）拆成 base64 + 原 URL。 */
    private fun readImages(stream: InputStream, model: String): List<GeneratedImage> {
        val translator = OpenAiGemini.SseTranslator(newCompletionId(), model)
        val images = ArrayList<GeneratedImage>()
        stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                for (frame in translator.translate(line)) {
                    if (frame == SSE_DONE) continue
                    val payload = frame.removePrefix("data:").trim()
                    val chunk = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
                    images += imagesOf(chunk)
                }
            }
        }
        return images
    }

    private fun imagesOf(chunk: JsonObject): List<GeneratedImage> {
        val choice = chunk.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull() ?: return emptyList()
        val container = choice.objOrNull("delta") ?: choice.objOrNull("message") ?: return emptyList()
        val images = container.arrayOrNull("images") ?: return emptyList()
        return images.mapNotNull { element -> imageOf(element.asObjectOrNull() ?: return@mapNotNull null) }
    }

    private fun imageOf(item: JsonObject): GeneratedImage? {
        val url = item.objOrNull("image_url")?.stringOrNull("url").orEmpty()
            .ifEmpty { item.stringOrNull("url").orEmpty() }
        if (url.isEmpty()) return null
        val match = DATA_URL.matchEntire(url) ?: return GeneratedImage(url = url)
        // mime 只在 data URL 里（GeneratedImage 没有独立字段），两个字段都带出去供网关挑选
        return GeneratedImage(b64Json = match.groupValues[2], url = url)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body).ifEmpty { "上游 HTTP $status" + bodySnippet(body) }
        val lower = message.lowercase()
        val kind = when {
            // 与 0.1.39 语义一致：凭证失效明确报出需要重新登录，不隐藏。
            // 注意不要把 401/403 降级成短冷却——那会导致反复触发 OAuth 刷新，
            // 加速 Google 风控吊销 refresh_token（「越修越频繁失效」的根因）。
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            // 图像通道用 status=0 表示连不上/读响应失败（与 ChatCall 的口径一致）
            status <= 0 -> ErrorKind.NETWORK
            status == 404 -> ErrorKind.CLIENT // 模型不存在属于客户端入参错误，不连累账号被禁用
            status == 429 && (lower.contains("quota") || lower.contains("capacity")) -> ErrorKind.QUOTA
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    /**
     * 额度信息：先读 `loadCodeAssist` 里的订阅层级（paidTier，Gemini 会员 = Google AI Pro）
     * 与 Google One 积分；没有积分条目时回退到 `retrieveUserQuotaSummary` 的模型组配额，
     * detail 里始终标注订阅层级，避免把会员配额误认为免费额度。
     * 凭证策略：不主动刷新（避免 OAuth 高频刷新触发风控），额度刷新失败时只报状态。
     */
    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = parse(account) ?: return null
        val (paidStatus, paidBody) = try {
            loadPaidCredits(credential.accessToken)
        } catch (e: Exception) {
            // 积分端点失败不代表不能查配额，先降级继续
            return quotaCreditInfo(credential, "订阅未知", prefix = "查询积分失败：${e.message}；")
        }
        when {
            // 额度查询与对话路径的策略不同：这里的 403 常见于权限/订阅分流
            // （如 free-tier 访问部分 RPC 会回 SUBSCRIPTION_REQUIRED），报成「登录已失效」
            // 会让用户白白重登一遍；只有 401 才是凭证真的无效。
            paidStatus == 401 ->
                return CreditInfo(0, known = false, detail = "登录状态已失效，需要重新登录")
            paidStatus !in 200..299 ->
                return quotaCreditInfo(
                    credential,
                    "订阅未知",
                    prefix = "查询积分失败（HTTP $paidStatus）：${paidBody.take(120)}；",
                )
        }
        val assist = parsePaidAssist(paidBody)
        if (assist?.credits != null) {
            return CreditInfo(assist.credits, known = true, detail = "${assist.tierLabel} · ${assist.creditsDetail}")
        }
        return quotaCreditInfo(credential, assist?.tierLabel ?: "订阅未知")
    }

    override fun creditPacks(account: ProviderAccount): List<QuotaPack> {
        val credential = parse(account) ?: return emptyList()
        val assist = try {
            val (status, body) = loadPaidCredits(credential.accessToken)
            if (status in 200..299) parsePaidAssist(body) else null
        } catch (_: Exception) {
            null
        }
        val tierLabel = assist?.tierLabel ?: "订阅未知"
        val packs = mutableListOf<QuotaPack>()
        // 模型组配额是 best-effort：这层失败不能拖垮整个额度包列表（积分仍要展示）
        runCatching { loadQuotaSummary(credential.accessToken, credential.projectId) }
            .getOrNull()
            ?.takeIf { it.first in 200..299 }
            ?.let { (_, body) -> packs += quotaPacks(body, tierLabel) }
        // Google One 积分：仅订阅用户有
        if (assist?.credits != null) {
            packs += QuotaPack(name = "Google One AI 积分", group = tierLabel, remain = assist.credits)
        }
        return packs
    }

    // ------------------------------------------------------------------ 凭证

    private val refreshLock = Any()

    /**
     * 最近一次成功刷新的凭证，按账号 uid 记录。
     *
     * 用 uid 而不是 refresh_token 作键：Google 会轮换 refresh_token，旧值随即作废，
     * 以旧值为键会查不到刚写入的缓存而重复刷新。
     */
    @Volatile
    private var lastRefreshed: Map<String, Credential> = emptyMap()

    /** 各账号最近一次刷新失败的秒级时间戳（按 uid），用于失败冷却。 */
    @Volatile
    private var lastRefreshFailedAt: Map<String, Long> = emptyMap()

    /**
     * refresh_token 被上游吊销（`invalid_grant`）的账号。
     *
     * 这种失败**重试无用**：再用同一 refresh_token 打只会持续撞墙，并可能加重风控。
     * 必须让账号降到不可用并提示重新登录——与 CLIProxyAPI 对 invalid_grant 按 401 处理
     * 同一语义（其 issue #4130：「cooldown Antigravity credentials on invalid_grant
     * instead of silent retry loop」）。
     */
    @Volatile
    private var revokedAccounts: Set<String> = emptySet()

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: throw IllegalStateException("凭证无法解析，请重新登录")
        if (account.uid in revokedAccounts) {
            throw IllegalStateException("凭证已被上游吊销（refresh_token 无效），请重新登录")
        }
        val nowSec = nowMillis() / 1000
        val hasExpiry = credential.expiresAt > 0
        val isExpired = hasExpiry && credential.expiresAt <= nowSec
        val window = effectiveRefreshWindow(skewSeconds)
        val isNearExpiry = hasExpiry && (credential.expiresAt - nowSec <= window)

        if (!isExpired && !isNearExpiry) return null

        val uid = account.uid

        synchronized(refreshLock) {
            // 锁内复查：并发请求跨入同一临期窗口时，复用别的线程刚刷出的凭证，绝不重复打 OAuth。
            // 必须返回新凭证——返回 null 会让调用方拿旧 token 去请求，直接 401。
            val cached = lastRefreshed[uid]
            if (cached != null && cached.expiresAt - nowSec > window) {
                return toProviderAccount(cached, account)
            }
            // 刷新失败冷却：失败后短时间内不再重打 OAuth，否则 token 被吊销后会按请求频率轰炸端点。
            val failedAt = lastRefreshFailedAt[uid]
            if (failedAt != null && nowSec - failedAt < REFRESH_COOLDOWN_SECONDS) {
                if (isExpired) throw IllegalStateException("凭证已过期且刷新失败，稍后重试或重新登录")
                return null
            }
            val refreshed = refreshAccessToken(credential)
            if (refreshed != null) {
                lastRefreshed = lastRefreshed + (uid to refreshed)
                if (lastRefreshFailedAt.containsKey(uid)) lastRefreshFailedAt = lastRefreshFailedAt - uid
                val updated = toProviderAccount(refreshed, account)
                hooks.onAccountUpdated(updated)
                return updated
            }
            lastRefreshFailedAt = lastRefreshFailedAt + (uid to nowSec)
            if (lastRefreshWasRevoked) {
                revokedAccounts = revokedAccounts + uid
                throw IllegalStateException("refresh_token 已被上游吊销（invalid_grant），请重新登录")
            }
        }
        if (isExpired) {
            throw IllegalStateException("凭证已过期且刷新失败，稍后重试或重新登录")
        }
        return null
    }

    /**
     * 实际生效的临期窗口：尊重全局配置的 skew，但夹到 [REQUEST_SAFETY_WINDOW_SECONDS] 以内。
     *
     * 直接采用过大的 skew（如旧的 24h 默认）会把临期判断退化成「每次请求都刷新」，
     * 反而把 OAuth 频率推到风控红线。
     */
    private fun effectiveRefreshWindow(skewSeconds: Long): Long =
        if (skewSeconds <= 0) REQUEST_SAFETY_WINDOW_SECONDS
        else minOf(skewSeconds, REQUEST_SAFETY_WINDOW_SECONDS)

    /** 丢弃某账号的刷新缓存与失败冷却（凭证被外部替换时调用）。 */
    private fun forgetRefreshState(uid: String) {
        synchronized(refreshLock) {
            if (lastRefreshed.containsKey(uid)) lastRefreshed = lastRefreshed - uid
            if (lastRefreshFailedAt.containsKey(uid)) lastRefreshFailedAt = lastRefreshFailedAt - uid
            if (uid in revokedAccounts) revokedAccounts = revokedAccounts - uid
        }
    }

    // ------------------------------------------------------------------ 登录

    override fun buildAuthUrl(): String {
        val state = UUID.randomUUID().toString().replace("-", "")
        return buildString {
            append(AUTH_ENDPOINT)
            append("?client_id=").append(encode(CLIENT_ID))
            append("&redirect_uri=").append(encode(redirectUri()))
            append("&response_type=code")
            append("&scope=").append(encode(SCOPES.joinToString(" ")))
            append("&access_type=offline")
            append("&prompt=consent")
            append("&state=").append(state)
        }
    }

    override fun exchangeCode(code: String): ProviderAccount {
        val tokens = exchangeCodeForTokens(code)
        val email = fetchEmail(tokens.accessToken)
        val projectId = fetchProjectId(tokens.accessToken)
        val credential = Credential(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAt,
            email = email,
            projectId = projectId,
        )
        val account = toProviderAccount(credential)
        // 重新登录拿到的是全新凭证：必须清掉该账号的刷新缓存与失败冷却，
        // 否则重登前在途的请求可能命中旧缓存，把已作废的旧 refresh_token 回写进账号池。
        forgetRefreshState(account.uid)
        return account
    }

    // ------------------------------------------------------------------ HTTP

    private fun exchangeCodeForTokens(code: String): Tokens {
        val form = mapOf(
            "code" to code,
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "redirect_uri" to redirectUri(),
            "grant_type" to "authorization_code",
        )
        val (status, body) = postForm(tokenEndpoint, form)
        if (status !in 200..299) throw IllegalStateException("换取 token 失败（HTTP $status）：${body.take(200)}")
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("token 响应不是合法 JSON")
        val access = obj.get("access_token")?.asString.orEmpty()
        if (access.isEmpty()) throw IllegalStateException("token 响应缺少 access_token")
        val expiresIn = obj.get("expires_in")?.asLong ?: 3600L
        return Tokens(
            accessToken = access,
            refreshToken = obj.get("refresh_token")?.asString.orEmpty(),
            expiresAt = nowMillis() / 1000 + expiresIn,
        )
    }

    /** 本次刷新的失败是否属于 refresh_token 被吊销（供调用方决定是否标记账号）。 */
    @Volatile
    private var lastRefreshWasRevoked = false

    private fun refreshAccessToken(credential: Credential): Credential? {
        lastRefreshWasRevoked = false
        if (credential.refreshToken.isEmpty()) return null
        val form = mapOf(
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "refresh_token" to credential.refreshToken,
            "grant_type" to "refresh_token",
        )
        val (status, body) = try {
            postForm(tokenEndpoint, form)
        } catch (e: Exception) {
            hooks.onLog("Antigravity 刷新 Token 网络异常：${e.message}")
            return null
        }
        if (status !in 200..299) {
            // invalid_grant = refresh_token 已被上游吊销/失效：重试无用，需重新登录。
            // 与 CLIProxyAPI issue #4130 的处理一致（按 401 语义降温，不静默重试）。
            if (body.contains("invalid_grant")) {
                lastRefreshWasRevoked = true
                hooks.onLog("Antigravity refresh_token 已被吊销（invalid_grant），需要重新登录")
            } else {
                hooks.onLog("Antigravity 刷新 Token 失败（HTTP $status）：${body.take(160)}")
            }
            return null
        }
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val access = obj.get("access_token")?.asString.orEmpty()
        if (access.isEmpty()) return null
        val expiresIn = obj.get("expires_in")?.asLong ?: 3600L
        // Google 可能轮换 refresh_token（此时旧值作废），不回存会导致下次刷新 invalid_grant
        val nextRefresh = obj.get("refresh_token")?.asString?.takeIf { it.isNotEmpty() }
        return credential.copy(
            accessToken = access,
            refreshToken = nextRefresh ?: credential.refreshToken,
            expiresAt = nowMillis() / 1000 + expiresIn,
        )
    }

    private fun fetchEmail(accessToken: String): String {
        val (status, body) = try {
            get("https://www.googleapis.com/oauth2/v2/userinfo?alt=json", accessToken)
        } catch (_: Exception) {
            return ""
        }
        if (status !in 200..299) return ""
        return runCatching { JsonParser.parseString(body).asJsonObject.get("email")?.asString.orEmpty() }
            .getOrDefault("")
    }

    /** `loadCodeAssist` 取 project id；字段名以响应为准，做宽容提取。 */
    private fun fetchProjectId(accessToken: String): String {
        val payload = JsonObject().apply { add("metadata", loadCodeAssistMetadata()) }.toString()
        val (status, body) = try {
            postJson("$apiBase/$API_VERSION:loadCodeAssist", accessToken, payload)
        } catch (_: Exception) {
            return ""
        }
        if (status !in 200..299) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        for (key in listOf("cloudaicompanionProject", "project", "projectId")) {
            obj.get(key)?.let { value ->
                when {
                    value.isJsonPrimitive -> value.asString.takeIf { it.isNotEmpty() }?.let { return it }
                    value.isJsonObject -> value.asJsonObject.get("id")?.asString
                        ?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        return ""
    }

    /** 当前生效的 User-Agent：版本随官方清单更新，不再写死。 */
    private fun userAgent(): String = "antigravity/hub/${clientVersion()} darwin/arm64"

    /**
     * 额度/订阅查询（完全对接 CLIProxyAPI 的做法）。
     *
     * 对齐 `internal/runtime/executor/antigravity_executor_credits.go` 与
     * `antigravity_executor_request.go`（MIT, router-for-me）：
     * - 只发 Authorization / Content-Type / User-Agent 三个头，不发任何指纹头；
     * - UA 用裸 hub 形态 `antigravity/hub/<ver> darwin/arm64`；
     * - base 固定 `daily-cloudcode-pa`；
     * - loadCodeAssist 的 metadata **只带 ideType=ANTIGRAVITY**（不带 platform/pluginType）。
     */
    private fun loadPaidCredits(accessToken: String): Pair<Int, String> {
        val payload = JsonObject().apply {
            add("metadata", JsonObject().apply { addProperty("ideType", "ANTIGRAVITY") })
        }.toString()
        return postJson(
            "$quotaBase/$API_VERSION:loadCodeAssist",
            accessToken,
            payload,
            userAgent = userAgent(),
        )
    }

    private fun open(url: String, accessToken: String, body: String?): HttpURLConnection {
        val headers = buildMap {
            put("Authorization", "Bearer $accessToken")
            put("User-Agent", userAgent())
            if (body != null) put("Content-Type", "application/json")
        }
        return HttpSupport.open(
            url = url,
            method = if (body == null) "GET" else "POST",
            headers = headers,
            body = body,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
    }

    private fun get(url: String, accessToken: String): Pair<Int, String> {
        val conn = open(url, accessToken, null)
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(
        url: String,
        accessToken: String,
        body: String,
        connectTimeout: Int = CONNECT_TIMEOUT_MS,
        readTimeout: Int = READ_TIMEOUT_MS,
        userAgent: String = userAgent(),
    ): Pair<Int, String> {
        val conn = HttpSupport.open(
            url = url,
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $accessToken",
                "User-Agent" to userAgent,
                "Content-Type" to "application/json",
            ),
            body = body,
            connectTimeoutMs = connectTimeout,
            readTimeoutMs = readTimeout,
        )
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(url: String, form: Map<String, String>): Pair<Int, String> {
        val encoded = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Host", "oauth2.googleapis.com")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "Go-http-client/2.0")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val body = if (status in 200..299) HttpSupport.readLimited(conn.inputStream) else HttpSupport.readLimited(conn.errorStream)
            status to body
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        val access = obj.get("accessToken")?.asString.orEmpty()
        if (access.isEmpty()) return null
        return Credential(
            accessToken = access,
            refreshToken = obj.get("refreshToken")?.asString.orEmpty(),
            expiresAt = obj.get("expiresAt")?.asLong ?: 0L,
            email = obj.get("email")?.asString.orEmpty(),
            projectId = obj.get("projectId")?.asString.orEmpty(),
        )
    }

    private fun toProviderAccount(credential: Credential, existingAccount: ProviderAccount? = null): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("accessToken", credential.accessToken)
            addProperty("refreshToken", credential.refreshToken)
            addProperty("expiresAt", credential.expiresAt)
            addProperty("email", credential.email)
            addProperty("projectId", credential.projectId)
        }.toString()
        // 稳定 UID：已有账号严格沿用旧 UID，绝不因 Token 刷新而漂移；新账号优先 email，无 email 用稳定 refreshToken
        val uid = existingAccount?.uid?.takeIf { it.isNotEmpty() }
            ?: credential.email.takeIf { it.isNotEmpty() }
            ?: ("antigravity-" + credential.refreshToken.hashCode().toUInt().toString(16))
        val nickname = credential.email.takeIf { it.isNotEmpty() }
            ?: existingAccount?.nickname.orEmpty().ifEmpty { uid }
        return ProviderAccount(id, uid, nickname, secret)
    }

    private fun redirectUri(): String = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("error")?.let { error ->
            error.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        obj.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun requestedModel(body: String): String =
        runCatching { JsonParser.parseString(body).asJsonObject.get("model")?.asString.orEmpty() }
            .getOrDefault("")

    /** 登录与额度查询共用的 loadCodeAssist 客户端身份（CLIProxyAPI 同款：只带 ideType）。 */
    private fun loadCodeAssistMetadata(): JsonObject = JsonObject().apply {
        addProperty("ideType", "ANTIGRAVITY")
    }

    /**
     * 模型组配额（桌面 App 展示的「Weekly Limit / Five Hour Limit」）。
     *
     * ⚠️ **不能把第一个 200 当最终结果**（2026-10 上游变更，多个项目同踩）：
     * `daily-cloudcode-pa` 会返回**残缺的 200**——groups 只剩部分组/窗口（典型是只剩
     * `Claude and GPT models` 或只剩周窗口），而完整的 5h + weekly 只在 production
     * `cloudcode-pa` 上才有。所以：
     * 1. production 排第一位（见 [QUOTA_FALLBACK_BASES]）；
     * 2. 拿到 200 后检查是否包含期望的共享池（bucketId 带 `gemini-` / `3p-`），缺就继续问下一个；
     * 3. 多个端点的 groups 合并（按组名 + bucketId 去重，先到先得），避免残缺响应掩盖完整数据。
     *
     * 仍然只把 401 当登录失效，403/5xx 继续下一个端点。
     */
    private fun loadQuotaSummary(accessToken: String, projectId: String): Pair<Int, String> {
        // 只有用默认域名时才走完整端点链；显式传了 base（单测的假上游）就只打它
        val bases = if (quotaBase == QUOTA_BASE) listOf(quotaBase) + QUOTA_FALLBACK_BASES else listOf(quotaBase)
        // 两种请求体都试：带 project 的完整形态，与空体（#3501 常见于 project 授权校验失败）
        val payloads = LinkedHashSet<String>().apply {
            if (projectId.isNotEmpty()) {
                add(JsonObject().apply { addProperty("project", projectId) }.toString())
            }
            add("{}")
        }
        var last: Pair<Int, String> = 0 to ""
        // 已收集到的 groups：组名 → buckets（同组同 bucketId 只保留第一份）
        val merged = LinkedHashMap<String, MutableList<JsonObject>>()
        var sawOk = false
        for (base in bases) {
            for (payload in payloads) {
                val url = "$base/$API_VERSION:retrieveUserQuotaSummary"
                val result = postJson(url, accessToken, payload, userAgent = userAgent())
                if (result.first == 401) return result
                if (result.first !in 200..299) {
                    last = result
                    continue
                }
                sawOk = true
                val before = merged.values.sumOf { it.size }
                collectQuotaGroups(result.second, merged)
                // 这个端点没给出任何 bucket：不能当成功，否则会把「口径变了」掩盖成空结果
                if (merged.values.sumOf { it.size } == before) {
                    last = result
                    continue
                }
                // 已拿到含共享池的响应就不再往下试（prod 正常一次就够）
                if (hasSharedPool(merged)) return 200 to buildMergedQuotaBody(merged)
            }
        }
        // 全部端点都没给出完整结果：有多少用多少，一个都没有才回退到最后一次失败
        return if (sawOk && merged.isNotEmpty()) 200 to buildMergedQuotaBody(merged) else last
    }

    /** 把一次响应的 groups 合进 [merged]；响应包在 `response` 里（OAuth 形态）也认。 */
    private fun collectQuotaGroups(body: String, merged: LinkedHashMap<String, MutableList<JsonObject>>) {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return
        val root = obj.objOrNull("response") ?: obj
        val groups = root.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (element in groups.asJsonArray) {
            val group = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = group.get("displayName")?.asString?.takeIf { it.isNotEmpty() } ?: "Models"
            val buckets = group.get("buckets")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val target = merged.getOrPut(name) { mutableListOf() }
            val seen = target.mapNotNull { it.get("bucketId")?.asString }.toHashSet()
            for (bucketElement in buckets.asJsonArray) {
                val bucket = bucketElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = bucket.get("bucketId")?.asString.orEmpty()
                if (id.isNotEmpty() && !seen.add(id)) continue
                target.add(bucket)
            }
        }
    }

    /**
     * 合并结果是否已经可用：**Gemini 共享池**至少有一个窗口就算。
     *
     * 实测的残缺响应正是「只剩 Claude and GPT models」（Gemini 组整个消失），所以判据看 Gemini
     * 组是否到位；不再要求 Claude+GPT 也到位——有些账号本来就没有第三方池，要求两组都有
     * 会让这类账号白白多打两轮端点。
     */
    private fun hasSharedPool(merged: Map<String, List<JsonObject>>): Boolean =
        merged.values.flatten().any { it.get("bucketId")?.asString.orEmpty().startsWith("gemini-") }

    /** 把合并结果拼回 `{groups:[...]}`，供 [parseQuotaBuckets] 照旧解析。 */
    private fun buildMergedQuotaBody(merged: Map<String, List<JsonObject>>): String {
        val groups = JsonArray()
        for ((name, buckets) in merged) {
            groups.add(JsonObject().apply {
                addProperty("displayName", name)
                add("buckets", JsonArray().apply { buckets.forEach { add(it) } })
            })
        }
        return JsonObject().apply { add("groups", groups) }.toString()
    }

    /** 无积分可显示时，账号卡片余额取最紧窗口的剩余百分比，detail 标注订阅层级。 */
    private fun quotaCreditInfo(credential: Credential, tierLabel: String, prefix: String = ""): CreditInfo {
        val (status, body) = try {
            loadQuotaSummary(credential.accessToken, credential.projectId)
        } catch (e: Exception) {
            return CreditInfo(0, known = false, detail = "${prefix}查询配额失败：${e.message}")
        }
        if (status == 401) {
            return CreditInfo(0, known = false, detail = "登录状态已失效，需要重新登录")
        }
        if (status !in 200..299) {
            // 403 在额度端点是权限/订阅校验失败（#3501），不是凭证失效
            val hint = if (status == 403) "（非登录失效，是上游对客户端/账号的授权校验）" else ""
            return CreditInfo(
                0,
                known = false,
                detail = "$prefix$tierLabel · 查询配额失败（HTTP $status）$hint：${body.take(300)}",
            )
        }
        val buckets = parseQuotaBuckets(body)
        if (buckets.isEmpty()) {
            return CreditInfo(0, known = false, detail = "$tierLabel · 上游没有返回模型组配额：${body.take(200)}")
        }
        // 账号级额度只算共享池；模型专属池（Gemini 4 Argon 等）耗尽不代表账号不能用
        val accountLevel = buckets.filter { !it.modelOnly }
        val tightest = (accountLevel.ifEmpty { buckets }).minBy { it.remainingPct }
        val pct = tightest.remainingPct.roundToLong()
        // 周额度 0% 但 5 小时池还有额度时，账号其实**能用**（实测：官方客户端里 5 小时额度正常生效）。
        // 所以不能只报最紧窗口的 0%，否则界面与选号都会误以为账号已耗尽。
        val stillUsable = accountLevel.any { it.remainingPct > 0.0 }
        val windows = buckets.joinToString(" · ") { bucket ->
            "${bucket.groupName} ${bucket.windowName} ${bucket.remainingPct.roundToLong()}%"
        }
        // 模型专属池单独报一句：它是新加的额度限制，用户需要知道是哪块先耗尽
        val modelOnly = buckets.filter { it.modelOnly }
            .joinToString("；") { bucket ->
                "${bucket.groupName} ${bucket.windowName} 剩 ${bucket.remainingPct.roundToLong()}%（${bucket.scopeLabel}）"
            }
        return CreditInfo(
            pct,
            known = true,
            // 这是配额剩余百分比，不是积分：不带单位就会被当成「87 点」与别家相加
            unit = CreditUnit.PERCENT,
            detail = buildString {
                append("$tierLabel · 组配额剩余 $pct%（最紧：${tightest.groupName} ${tightest.windowName}）")
                if (stillUsable && pct == 0L) {
                    // 最紧的窗口见底，但同一账号还有别的窗口有余量：明确说出来，避免被当成账号耗尽
                    append("；仍有可用窗口")
                }
                if (modelOnly.isNotEmpty()) append("；独立配额：$modelOnly")
                append("；全部窗口：$windows")
            },
        )
    }

    private fun quotaPacks(body: String, tierLabel: String): List<QuotaPack> =
        parseQuotaBuckets(body).map { bucket ->
            QuotaPack(
                name = bucket.groupName,
                group = "$tierLabel · ${bucket.windowName}",
                limit = 100,
                used = bucket.usedPct.roundToLong(),
                remain = bucket.remainingPct.roundToLong(),
                expireAt = bucket.resetAtSeconds,
                scope = bucket.scopeLabel,
            )
        }

    /** `loadCodeAssist` 解析结果：订阅层级 + Google One 积分（可能为空）。 */
    private data class PaidAssist(
        val tierLabel: String,
        val credits: Long?,
        val creditsDetail: String,
    )

    /**
     * 解析订阅层级与积分：`paidTier` 是 Google One 订阅（Gemini 会员 = Google AI Pro），
     * 缺失时用 `currentTier`（consumer 恒为 free-tier）。积分只认 GOOGLE_ONE_AI 条目，
     * 字符串/数字都按浮点解析；余额低于 minimumCreditAmountForUsage 记为不可用。
     */
    private fun parsePaidAssist(body: String): PaidAssist? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val tierLabel = tierLabelOf(obj)
        val credits = obj.objOrNull("paidTier")
            ?.get("availableCredits")?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.firstNotNullOfOrNull { element ->
                val credit = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstNotNullOfOrNull null
                if (!credit.get("creditType")?.asString.orEmpty().equals("GOOGLE_ONE_AI", ignoreCase = true)) {
                    return@firstNotNullOfOrNull null
                }
                val amount = runCatching { credit.get("creditAmount")?.asString?.trim()?.toFloat() }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                val minimum = runCatching { credit.get("minimumCreditAmountForUsage")?.asString?.trim()?.toFloat() }
                    .getOrNull() ?: 0f
                if (amount >= minimum) {
                    amount.toLong().coerceAtLeast(0) to "Google One AI 积分"
                } else {
                    0L to "Google One AI 积分（余额 $amount 已低于可用下限 $minimum）"
                }
            }
        return PaidAssist(tierLabel, credits?.first, credits?.second ?: "")
    }

    /** 订阅层级展示名（Cli-Proxy-API-Management-Center #318 同款映射）。 */
    private fun tierLabelOf(obj: JsonObject): String {
        val paidTier = obj.objOrNull("paidTier")
        val paidId = paidTier?.get("id")?.asString?.trim().orEmpty()
        val paidName = paidTier?.get("name")?.asString?.trim().orEmpty()
        if (paidId.isNotEmpty() || paidName.isNotEmpty()) {
            return when (paidId) {
                "g1-pro-tier" -> "Google AI Pro"
                "g1-ultra-tier" -> "Google AI Ultra"
                "g1-ultra-lite-tier" -> "Google AI Ultra Lite"
                else -> paidName.ifEmpty { paidId }
            }
        }
        val currentId = obj.objOrNull("currentTier")?.get("id")?.asString?.trim().orEmpty()
        val currentName = obj.objOrNull("currentTier")?.get("name")?.asString?.trim().orEmpty()
        return when {
            currentId.equals("free-tier", ignoreCase = true) -> "免费版"
            currentId.isNotEmpty() -> currentName.ifEmpty { currentId }
            else -> "订阅未知"
        }
    }

    private data class QuotaBucket(
        val groupName: String,
        val windowName: String,
        val remainingPct: Double,
        val usedPct: Double,
        val resetAtSeconds: Long,
        /** 模型专属配额（如 Gemini 4 Argon 独立周池），耗尽只影响对应模型。 */
        val modelOnly: Boolean = false,
        /** 模型专属配额的适用范围文案（仅 [modelOnly] 时非空）。 */
        val scopeLabel: String = "",
    )

    /**
     * 解析 `retrieveUserQuotaSummary`：`groups[]` 每组（如 Gemini Models）带若干窗口
     * bucket（周/5 小时），`remainingFraction` 0-1（1 = 未用），`resetTime` ISO 时间。
     *
     * 两个易踩的上游行为：
     * - **`disabled` 不等于「没额度」**：实测（用户核对官方客户端）周额度耗尽时，上游会给
     *   5 小时桶打上 `disabled: true`，但同时给 `remainingFraction: 1`——而客户端里 5 小时额度
     *   **依然能正常使用**。也就是说这个 `disabled` 不表示「不可用」，`remainingFraction` 才是真实
     *   剩余量。从前这里直接 `continue` 丢掉整个桶，用户就只看到周额度、看不到可用的 5 小时池
     *   （issue：geminiplus 同样有 5 小时用量，网关里却只能看到周额度）。
     *   现在按 `remainingFraction` 如实展示，`disabled` 只当作附注。
     * - Gemini 4 Argon 等新模型有**自己的**额外配额池（只有周窗口），它不是账号总额度，
     *   耗尽时不能把账号判成没额度，故单独标 [QuotaBucket.modelOnly]。
     */
    private fun parseQuotaBuckets(body: String): List<QuotaBucket> {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val groups = obj.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val result = mutableListOf<QuotaBucket>()
        for (groupElement in groups.asJsonArray) {
            val group = groupElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val groupName = group.get("displayName")?.asString?.takeIf { it.isNotEmpty() } ?: "Models"
            val buckets = group.get("buckets")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (bucketElement in buckets.asJsonArray) {
                val bucket = bucketElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                // proto3 JSON 会**省略零值**：一个真正耗尽的桶（remainingFraction = 0）可能根本不带该字段。
                // 所以不能「缺字段就当占位丢掉」——有 bucketId 或 resetTime 的桶一律收下，缺值按 0 处理；
                // 真正该丢的只有「什么都没有」的空壳条目（否则耗尽的那组会整个消失）。
                val rawFraction = runCatching { bucket.get("remainingFraction")?.asString?.trim()?.toDouble() }
                    .getOrNull()
                val bucketId = bucket.get("bucketId")?.asString.orEmpty()
                val resetTime = bucket.get("resetTime")?.asString.orEmpty()
                if (rawFraction == null && bucketId.isEmpty() && resetTime.isEmpty()) continue
                val fraction = rawFraction ?: 0.0
                val windowName = bucket.get("displayName")?.asString?.takeIf { it.isNotEmpty() }
                    ?: bucketId.takeIf { it.isNotEmpty() }
                    ?: "Limit"
                val bounded = fraction.coerceIn(0.0, 1.0)
                // 组名没写明模型时，再看 bucketId（如 gemini-argon-wtu-weekly / gemini-4-argon）
                val scope = modelScopeOf(groupName) ?: modelScopeOf(bucketId)
                result += QuotaBucket(
                    // 模型专属池用统一展示名，避免同一模型因 bucketId 拼写差异被拆成两行
                    groupName = if (scope != null) "Gemini 4 Argon" else groupName,
                    windowName = windowName,
                    remainingPct = bounded * 100.0,
                    usedPct = (1.0 - bounded) * 100.0,
                    resetAtSeconds = parseIsoSeconds(bucket.get("resetTime")?.asString),
                    modelOnly = scope != null,
                    scopeLabel = scope?.let { "仅 $it" }.orEmpty(),
                )
            }
        }
        return result
    }

    /** 识别「模型专属配额池」并返回其模型名；共享池（Gemini Models / Claude and GPT models）返回 null。
     *
     * 只认明确带模型名的池子：Argon 是目前唯一有独立配额的模型，且只有周窗口。
     */
    private fun modelScopeOf(text: String): String? =
        if (text.contains("argon", ignoreCase = true)) "gemini-4-argon" else null

    private fun parseIsoSeconds(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { OffsetDateTime.parse(value.trim()).toEpochSecond() }.getOrDefault(0L)
    }

    private fun isStreaming(body: String): Boolean =
        runCatching { JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean ?: false }
            .getOrDefault(false)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    private data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val email: String,
        val projectId: String,
    )

    companion object {
        const val ID = "antigravity"

        const val CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        // 这是 Google 为「已安装应用」（installed-app）OAuth 客户端下发的公开 client_secret：
        // 按 OAuth 2.0 规范，这类客户端的 secret 无法保密、Google 也不当它作凭证，公开在客户端里是正常做法。
        // 不要因为它长得像密钥就删掉——删了 OAuth 换 token 会直接失败。
        const val CLIENT_SECRET = "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"
        const val CALLBACK_PORT = 51121

        /** Google 客户端注册的 redirect_uri 固定路径，本地回调服务按它接收。 */
        const val CALLBACK_PATH = "/oauth-callback"

        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /** 与 CLIProxyAPI antigravityRequestTokenSafetyWindow 一致：临期 5 分钟才刷新。 */
        const val REQUEST_SAFETY_WINDOW_SECONDS = 5L * 60

        /** 刷新失败后的冷却时长：token 被吊销时避免按请求频率反复打 OAuth。 */
        private const val REFRESH_COOLDOWN_SECONDS = 60L
        const val API_BASE = "https://cloudcode-pa.googleapis.com"
        const val API_VERSION = "v1internal"

        /**
         * 对话域名：daily。
         *
         * prod（[API_BASE]）对消费级账号的对话请求会回 429 RESOURCE_EXHAUSTED（与额度无关），
         * 所以对话与模型列表不能跟配额查询共用 base。
         */
        const val CHAT_BASE = "https://daily-cloudcode-pa.googleapis.com"

        /**
         * 配额查询首选域名：production。
         *
         * 与 [CHAT_BASE] 相反：配额查询只有 prod 给完整数据。
         */
        const val QUOTA_BASE = "https://cloudcode-pa.googleapis.com"

        /**
         * 配额查询的备用域名（**顺序很重要**）：production 排第一。
         *
         * 2026-10 起 `daily-` 会返回残缺的 200（只剩部分组/窗口），完整的 5h + weekly 只在
         * production 上；把 daily 放前面会让 5 小时窗口彻底消失（多个项目同踩此坑）。
         */
        val QUOTA_FALLBACK_BASES = listOf(
            "https://daily-cloudcode-pa.googleapis.com",
            "https://daily-cloudcode-pa.sandbox.googleapis.com",
        )

        /** 上游保留的内部/实验模型，不对外暴露（清单与 CLIProxyAPI fetch_antigravity_models 一致）。 */
        private val EXCLUDED_MODELS = setOf(
            "chat_20706", "chat_23310", "tab_flash_lite_preview", "tab_jump_flash_lite_preview",
            "gemini-2.5-flash-thinking", "gemini-2.5-pro",
        )

        private const val MODEL_CACHE_TTL_MILLIS = 10L * 60 * 1000

        /** 模型目录 `extra` 里的音频能力位（只在上游明确声明时写入）。 */
        const val AUDIO_CAPABILITY = "audio"

        /** 上游回图的 data URL：`data:<mime>;base64,<data>`。mime 必须保留，网关/客户端据此存盘。 */
        private val DATA_URL = Regex("^data:([^;,]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL)

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )

        /** 智能别名映射：允许客户端请求简写（如 gemini-3.8-flash 映射到 gemini-3.8-flash-high）。 */
        val MODEL_ALIASES: Map<String, String> = mapOf(
            "gemini-3.8-flash" to "gemini-3.8-flash-high",
            "gemini-3.7-flash" to "gemini-3.7-flash-high",
            "gemini-3.6-flash" to "gemini-3.6-flash-high",
            "gemini-3.5-flash" to "gemini-3.5-flash-lite",
            "gemini-3.1-flash" to "gemini-3.1-flash-lite",
            "gemini-3.1-pro" to "gemini-pro-agent",
            "gemini-3-pro" to "gemini-pro-agent",
            "gemini-pro" to "gemini-pro-agent",
            "gemini-2.5-pro" to "gemini-pro-agent",
            "gemini-2.0-pro" to "gemini-pro-agent",
            "gemini-2.5-flash" to "gemini-3.8-flash-high",
            "gemini-2.5-flash-preview" to "gemini-3.8-flash-high",
            "gemini-2.0-flash" to "gemini-3.8-flash-high",
            "gemini-2.0-flash-exp" to "gemini-3.8-flash-high",
            "gemini-1.5-flash" to "gemini-3.8-flash-high",
            "gemini-1.5-flash-latest" to "gemini-3.8-flash-high",
            "gemini-flash" to "gemini-3.8-flash-high",
            "gemini-flash-1.5" to "gemini-3.8-flash-high",
            "gemini-flash-2.0" to "gemini-3.8-flash-high",
            "claude-opus" to "claude-opus-4-6-thinking",
            "claude-3-opus" to "claude-opus-4-6-thinking",
            "claude-sonnet" to "claude-sonnet-4-6",
            "claude-3-7-sonnet" to "claude-sonnet-4-6",
            "claude-3-7-sonnet-20250219" to "claude-sonnet-4-6",
            "claude-3-5-sonnet" to "claude-sonnet-4-6",
            "claude-3-5-sonnet-20241022" to "claude-sonnet-4-6",
            "claude-3-5-sonnet-latest" to "claude-sonnet-4-6",
            "claude-3-5-haiku" to "gemini-3.5-flash-lite",
            "claude-3-haiku" to "gemini-3.5-flash-lite",
            "gpt-oss" to "gpt-oss-120b-medium",
        )

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
    }
}
