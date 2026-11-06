package dev.aigw.core.provider

/**
 * `/v1/images/generations` 的请求，已从 OpenAI JSON 体解析成网关内部语义。
 *
 * [rawBody] 是客户端原始 JSON（未改写）：有原生图像端点的 provider 直接透传，
 * 只有多模态对话通道的 provider 用 [prompt] 自己构造上游请求。
 */
data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val n: Int = 1,
    val size: String = "",
    val rawBody: String = "",
)

/** 一张生成的图：要么是 base64（`b64_json`），要么是可直接访问的 URL。 */
data class GeneratedImage(
    val b64Json: String = "",
    val url: String = "",
    val revisedPrompt: String = "",
) {
    val isUsable: Boolean get() = b64Json.isNotEmpty() || url.isNotEmpty()
}

/** 图像生成结果。`created` 为秒级时间戳。 */
data class ImageGenerationResult(
    val images: List<GeneratedImage>,
    val created: Long = System.currentTimeMillis() / 1000,
)

/**
 * `/v1/audio/transcriptions` 的请求。
 *
 * [rawBody] / [rawContentType] 是客户端原始 multipart 请求体，供有原生转写端点的
 * provider 直接透传（重建 multipart 容易把客户端自定义字段弄坏）。
 */
class AudioTranscriptionRequest(
    val model: String,
    /** 音频字节。 */
    val audio: ByteArray,
    val fileName: String,
    /** 从文件头/扩展名判定出的 MIME（如 `audio/wav`）；判不出时为空。 */
    val mimeType: String,
    /** OpenAI 的 `prompt` 字段（提示上下文），可为空。 */
    val prompt: String,
    /** OpenAI 的 `language` 字段（ISO-639-1），可为空表示自动判定。 */
    val language: String,
    val rawBody: ByteArray,
    val rawContentType: String,
)

/** 语音转写结果。 */
data class AudioTranscriptionResult(
    val text: String,
    /** 上游识别出的语言（无则空）。 */
    val language: String = "",
    /** 上游返回的时长（秒）；0 表示未知。 */
    val durationSeconds: Double = 0.0,
)
