package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 多账号并发与连接占用的回归测试。
 *
 * 线上症状：同时连上来的请求一多，网关和 agent 客户端一起超时。
 * 根因是「并发闸门按账号串行 + 选号不感知在途」，多个请求会全部挤到优先级最高的那一个账号上排队，
 * 其它账号完全闲着；再加上 HTTP 线程数很少且空闲 keep-alive 连接也占着线程，整体就卡死了。
 */
class MultiAccountConcurrencyTest {

    /** openChat 会阻塞到 [release] 被放行，用来模拟一条正在跑的上游流。 */
    private class BlockingProvider(
        override val id: String,
        private val release: CountDownLatch,
        private val started: MutableList<Long>,
    ) : Provider {
        override val displayName = "阻塞上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            started.add(System.currentTimeMillis())
            // 上游请求生命周期：等测试放行
            release.await(20, TimeUnit.SECONDS)
            return AggregatedChatCall(
                200,
                """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}""",
            )
        }
    }

    private fun post(port: Int, model: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Connection", "close")
        val req = """{"model":"$model","messages":[{"role":"user","content":"hi"}]}"""
        conn.outputStream.write(req.toByteArray(Charsets.UTF_8))
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        return status to stream?.bufferedReader()?.readText().orEmpty()
    }

    @Test
    fun `两个账号上的两个请求应并行发出，而不是都挤在首选账号上排队`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val release = CountDownLatch(1)
        val started = Collections.synchronizedList(ArrayList<Long>())
        // 同一供应商两个账号、优先级相同：旧实现会把两个请求都派给第一个账号并串行
        engine.registry.register(BlockingProvider("p", release, started))
        engine.pool.upsert(ProviderAccount("p", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p", "u2", "账号2", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val t0 = System.currentTimeMillis()
            val results = (1..2).map {
                pool.submit<Pair<Int, String>> { post(server.listeningPort, "p/m") }
            }
            // 两个请求都必须在 3 秒内真正打到上游；旧实现第二个要等第一个流结束（闸门排队 120s）
            val deadline = System.currentTimeMillis() + 3_000
            while (started.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(2, started.size, "两个账号应各跑一条请求，实际只有 ${started.size} 条发出")
            assertTrue(started.max() - t0 < 3_000, "第二条请求不应等待第一条结束")

            release.countDown()
            results.forEach { assertEquals(200, it.get(15, TimeUnit.SECONDS).first) }
        } finally {
            release.countDown()
            pool.shutdownNow()
            server.stop()
        }
    }

    @Test
    fun `同一账号只有一条在途时后续请求有界等待后返回 429`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val release = CountDownLatch(1)
        val started = Collections.synchronizedList(ArrayList<Long>())
        engine.registry.register(BlockingProvider("p", release, started))
        engine.pool.upsert(ProviderAccount("p", "u1", "账号1", "{}"))
        // 把满载排队时间缩到 1 秒，验证它是有界的：等不到就回 429，而不是一直挂着
        engine.updateFailoverSettings(
            dev.aigw.core.failover.ModelFailoverSettings(
                concurrency = dev.aigw.core.failover.ConcurrencyConfig(maxPerAccount = 1, queueTimeoutMs = 1_000),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            // 先塞一条在途请求占满唯一的账号
            val first = Thread { runCatching { post(server.listeningPort, "p/m") } }.apply { start() }
            val deadline = System.currentTimeMillis() + 3_000
            while (started.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(1, started.size)

            // 第二个请求最多等到排队上限就返回 429，不能无限占用 HTTP 线程
            val t0 = System.currentTimeMillis()
            val (status, body) = post(server.listeningPort, "p/m")
            val elapsed = System.currentTimeMillis() - t0
            assertEquals(429, status, "满载应在排队上限后返回 429，实际 $status：$body")
            assertTrue(elapsed < 15_000, "排队必须有界，实际 ${elapsed}ms")

            release.countDown()
            first.join(15_000)
        } finally {
            release.countDown()
            server.stop()
        }
    }

    @Test
    fun `非流式响应要求关闭连接，空闲 keep-alive 连接不占 HTTP 线程`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val response = Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().write(
                    "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.UTF_8),
                )
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            // 空闲连接会一直占着 NanoHTTPD 的工作线程（读到 60s 超时才放），连接数一多就整体卡死
            assertTrue(
                response.lineSequence().any { it.equals("Connection: close", ignoreCase = true) },
                "非流式响应应主动关闭连接以释放线程：$response",
            )
        } finally {
            server.stop()
        }
    }
}
