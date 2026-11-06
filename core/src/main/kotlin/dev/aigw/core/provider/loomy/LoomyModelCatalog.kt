package dev.aigw.core.provider.loomy

import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.bool
import dev.aigw.core.util.long
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/**
 * 模型目录：从 `GET /api/v1/models` 拉取（需要 session）。
 *
 * 拉不到时返回本次运行内缓存的旧列表（没有则空列表），并置 [fromFallback] 与 [lastError]，
 * 不展示打包的硬编码快照。
 */
class LoomyModelCatalog(
    private val apiBase: String = LoomyConstants.API_BASE,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 10 * 60 * 1000,
) {
    private var cached: List<LoomyModel>? = null
    private var cachedAt = 0L
    private var cachedSession = ""

    @Volatile
    var fromFallback: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    /**
     * 强制下次重新拉取（界面的刷新按钮用）。
     *
     * 只让缓存过期（[cachedAt] 归零），保留 [cached] 作失败兜底：
     * 本方法只保证不再命中缓存，不保证上游此刻可用。
     */
    fun invalidate() {
        cachedAt = 0L
    }

    /**
     * 取模型列表。
     *
     * [session] 为空（没有可用账号）时返回空列表，不发请求，也不算失败——
     * 登录前本就不该有模型，报错只会在模型页吓人。
     */
    fun models(session: String?): List<LoomyModel> {
        // 标记描述的是「本次返回的清单怎么来的」，每次调用先重置，
        // 否则上一次的失败会被后面命中 TTL 缓存的调用沿用。
        fromFallback = false
        lastError = ""
        if (session.isNullOrEmpty()) {
            return emptyList()
        }
        val now = nowMillis()
        val fresh = cached != null && cachedSession == session && now - cachedAt < ttlMillis
        if (fresh) return cached!!

        return try {
            val list = fetch(session)
            if (list.isEmpty()) {
                fromFallback = true
                lastError = "上游返回了空的模型列表"
                cached ?: emptyList()
            } else {
                cached = list
                cachedAt = now
                cachedSession = session
                fromFallback = false
                lastError = ""
                list
            }
        } catch (e: LoomyApiException) {
            fromFallback = true
            lastError = e.message ?: "拉取模型列表失败"
            cached ?: emptyList()
        } catch (e: Exception) {
            fromFallback = true
            lastError = e.message ?: "拉取模型列表失败"
            cached ?: emptyList()
        }
    }

    private fun fetch(session: String): List<LoomyModel> {
        val headers = mapOf(
            LoomyConstants.TOKEN_HEADER to session,
            LoomyConstants.TRACEPARENT_HEADER to LoomyTrace.newTraceparent(),
            LoomyConstants.VERSION_HEADER to LoomyConstants.CLIENT_VERSION,
        )
        val (status, body) = httpJson("$apiBase${LoomyConstants.PATH_MODELS}", "GET", headers)
        val obj = decodeEnvelope(status, body)
        val array = obj.arrayOrNull("data") ?: return emptyList()
        val result = ArrayList<LoomyModel>()
        for (element in array) {
            val item = runCatching { element.asJsonObject }.getOrNull() ?: continue
            val id = item.str("id").trim()
            if (id.isEmpty()) continue
            val limit = item.objOrNull("limit")
            val context = limit?.long("context") ?: item.long("context_length")
            val output = limit?.long("output") ?: item.long("max_output_tokens")
            val capabilities = item.objOrNull("capabilities")
            val inputModalities = item.objOrNull("modalities")?.arrayOrNull("input")
            val hasImage = inputModalities?.any { it.asString == "image" } == true
            // 音频能力必须显式采集：不取就丢掉，转写端点只能靠猜
            val hasAudio = inputModalities?.any { it.asString == "audio" } == true
            result.add(
                LoomyModel(
                    id = id,
                    name = item.str("name").ifEmpty { item.str("displayName").ifEmpty { id } },
                    contextLength = context,
                    outputLength = output,
                    reasoning = item.bool("reasoning") || item.bool("supports_reasoning"),
                    toolCall = item.bool("tool_call") || capabilities?.bool("function_calling") == true,
                    attachment = item.bool("attachment") || hasImage,
                    audio = hasAudio,
                ),
            )
        }
        return result
    }
}
