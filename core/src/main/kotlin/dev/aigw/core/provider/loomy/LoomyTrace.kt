package dev.aigw.core.provider.loomy

import java.security.SecureRandom

/**
 * W3C Trace Context 的 `traceparent`。
 *
 * 上游模型服务对缺少该头的请求会一直挂着直到超时（官方客户端源码原话：
 * 「实测 Loomy iModel 缺 traceparent 时 chat/completions 会挂死直到超时」），
 * 所以每个请求都必须带。
 */
object LoomyTrace {

    private val random = SecureRandom()

    fun newTraceparent(): String = "00-${hex(16)}-${hex(8)}-01"

    private fun hex(bytes: Int): String {
        val out = ByteArray(bytes)
        random.nextBytes(out)
        return out.joinToString("") { "%02x".format(it) }
    }
}
