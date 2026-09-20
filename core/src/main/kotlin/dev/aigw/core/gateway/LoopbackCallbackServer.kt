package dev.aigw.core.gateway

import fi.iki.elonen.NanoHTTPD

/**
 * 临时本地回调服务：登录在系统浏览器里完成后，服务端会把浏览器重定向到本机端口，
 * 由它接住回调并交给网关换 token。
 *
 * 之所以不用内置 WebView：登录页在部分设备的 WebView 上渲染异常（实测有整页缩放错乱、
 * 输入框文字镜像），换系统浏览器可彻底绕开设备 WebView 的实现差异；
 * 代价是登录期间临时占用一个本地端口。
 */
internal class LoopbackCallbackServer(
    private val port: Int,
    private val path: String,
    private val onCallback: (callbackUrl: String) -> String,
) : NanoHTTPD("127.0.0.1", port) {

    fun startServer() {
        start(SOCKET_READ_TIMEOUT_MS, false)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        if (uri != path.trimEnd('/')) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
        val query = session.queryParameterString.orEmpty()
        // 用实际监听端口（构造时传 0 表示由系统分配），否则回调链接里会是 0
        val callbackUrl = "http://127.0.0.1:$listeningPort$path" + if (query.isEmpty()) "" else "?$query"
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            onCallback(callbackUrl),
        )
    }

    private companion object {
        const val SOCKET_READ_TIMEOUT_MS = 10_000
    }
}
