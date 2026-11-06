package dev.aigw.core.provider.loomy

/** 上游模型条目（字段取自官方客户端 `normalizeFetchedModelMetadata`）。 */
data class LoomyModel(
    val id: String,
    val name: String,
    val contextLength: Long = 0L,
    val outputLength: Long = 0L,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val attachment: Boolean = false,
    /** 输入模态里含音频（`modalities.input` 含 `audio`）；转写端点据此判定能否直接喂音频。 */
    val audio: Boolean = false,
)
