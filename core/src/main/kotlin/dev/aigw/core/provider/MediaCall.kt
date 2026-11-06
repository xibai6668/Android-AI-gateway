package dev.aigw.core.provider

/**
 * 媒体类调用（图片生成 / 语音转写）的统一返回。
 *
 * 与 [ChatCall] 的三态不同，这两类端点都是「一次请求换一份成品」，没有流式语义，
 * 所以只需成功 / 失败两态；失败带上游状态码与原文，由网关复用 [Provider.classify] 分类。
 */
sealed class MediaCall<out T> {
    class Success<T>(val value: T) : MediaCall<T>()
    class Failure(val status: Int, val errorBody: String) : MediaCall<Nothing>()
}

/** 取成功值；失败返回 null。 */
fun <T> MediaCall<T>.valueOrNull(): T? = (this as? MediaCall.Success)?.value

/** 取失败；成功返回 null。 */
fun <T> MediaCall<T>.failureOrNull(): MediaCall.Failure? = this as? MediaCall.Failure
