package dev.aigw.core.provider.trae

/**
 * 上游模型目录。
 *
 * 优先向 `get_detail_param` 拉取；成功后缓存 [TTL_MILLIS]，失败做 [FAIL_TTL_MILLIS] 负缓存，
 * 避免每次进「模型」页都打上游。拉不到时回退 [FALLBACK]，UI 会提示是内置快照。
 */
class TraeModelCatalog(
    private val client: TraeChatClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var cached: List<TraeModel>? = null
    private var fetchedAt = 0L
    private var lastFailureAt = 0L

    /** 当前展示的清单是否来自内置回退（而非上游实时拉取）。 */
    var fromFallback: Boolean = false
        private set

    /** 某个具体错误（如 4001）导致拉取失败时留给界面展示。 */
    var lastError: String = ""
        private set

    /**
     * 取模型清单。没有可用账号时直接返回内置回退，不发请求。
     */
    fun models(account: TraeAccount?): List<TraeModel> {
        val now = nowMillis()
        cached?.let { if (now - fetchedAt < TTL_MILLIS) return it }

        if (account == null) {
            fromFallback = true
            return FALLBACK
        }
        if (lastFailureAt != 0L && now - lastFailureAt < FAIL_TTL_MILLIS) {
            fromFallback = true
            return cached ?: FALLBACK
        }

        return try {
            val fetched = client.fetchModels(account)
            if (fetched.isEmpty()) {
                lastFailureAt = now
                lastError = "上游返回空模型列表"
                fromFallback = true
                cached ?: FALLBACK
            } else {
                cached = fetched
                fetchedAt = now
                lastFailureAt = 0L
                lastError = ""
                fromFallback = false
                fetched
            }
        } catch (e: Exception) {
            lastFailureAt = now
            lastError = e.message.orEmpty()
            fromFallback = true
            cached ?: FALLBACK
        }
    }

    /** 强制下次重新拉取（界面的刷新按钮用）。 */
    fun invalidate() {
        fetchedAt = 0L
        lastFailureAt = 0L
    }

    companion object {
        const val TTL_MILLIS = 60L * 60 * 1000
        const val FAIL_TTL_MILLIS = 5L * 60 * 1000

        /** 非对话用途的内部条目，开启「只看可用模型」时隐藏。 */
        private val INTERNAL_IDS = setOf(
            "summary",
            "browser_use_subagent",
            "explore_sub_agent_v2",
            "explore_sub_agent_v13",
            "custom_model_placeholder",
        )

        fun isInternal(id: String): Boolean = id in INTERNAL_IDS

        /**
         * 内置回退清单：2026-08 从上游 `get_detail_param` 抓到的 config_name 快照，
         * 已剔除内部子 agent 条目。仅在上游拉取失败时用于兜底，界面上会标注为「内置快照」。
         */
        val FALLBACK: List<TraeModel> = listOf(
            "Doubao-Seed-2.1-Pro",
            "Doubao-Seed-2.1-Turbo",
            "Doubao-Seed-2.0-Code",
            "seed-code-pro-0430",
            "DeepSeek-V4-Pro",
            "DeepSeek-V4-Flash",
            "DeepSeek-V4-Flash-Official",
            "glm-5.3",
            "glm-5.2",
            "glm-5-turbo",
            "glm-5",
            "kimi-k3",
            "kimi-k2.7-code",
            "kimi-k2.6",
            "minimax-m3",
            "qwen-3.7-plus",
            "sagitta",
            "aquila",
            "custom_model_gemini",
            "custom_model_kimi",
            "custom_model_claude",
            "custom_model_gpt-5",
            "custom_model_deepseek_v4",
        ).map { TraeModel(id = it, name = it) }
    }
}
