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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 并发连接压力下的存活测试。
 *
 * 线上症状：连接数一多，网关和 agent 客户端一起超时。除了「请求都挤在一个账号上排队」，
 * 还有一个独立原因：NanoHTTPD 的工作线程会被**空闲 keep-alive 连接**一直占住，
 * 直到 socket 读超时才释放；这些空转连接把线程池吃光后，真正要转发的请求连进都进不来。
 */
class GatewayConcurrencyPressureTest {

    private class QuickProvider : Provider {
        override val id = "quick"
        override val displayName = "快上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = AggregatedChatCall(
            200,
            """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}""",
        )
    }

    private fun chat(port: Int): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Connection", "close")
        conn.outputStream.write("""{"model":"quick/m","messages":[{"role":"user","content":"hi"}]}""".toByteArray())
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        return status to stream?.bufferedReader()?.readText().orEmpty()
    }

    @Test
    fun `大量空闲 keep-alive 连接不得让后续请求超时`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(QuickProvider())
        engine.pool.upsert(ProviderAccount("quick", "u1", "账号1", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }

        val idle = ArrayList<Socket>()
        try {
            // 模拟 agent 客户端留下的空闲复用连接：发一个请求，读完后不关闭连接
            repeat(40) {
                val socket = Socket("127.0.0.1", server.listeningPort)
                socket.soTimeout = 10_000
                socket.getOutputStream().write(
                    "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.UTF_8),
                )
                socket.getOutputStream().flush()
                // 读到响应头就停在连接上等下一个请求，跟真实 keep-alive 客户端一致
                val reader = socket.getInputStream()
                val head = ByteArray(16)
                reader.read(head)
                idle.add(socket)
            }
            // 空闲连接占着线程时，新的对话请求仍必须在合理时间内完成
            val t0 = System.currentTimeMillis()
            val (status, body) = chat(server.listeningPort)
            val elapsed = System.currentTimeMillis() - t0
            assertEquals(200, status, "空闲连接占满线程后，新请求不应失败：$body")
            assertTrue(elapsed < 10_000, "新请求不应被空闲连接饿死，实际 ${elapsed}ms")
        } finally {
            idle.forEach { runCatching { it.close() } }
            server.stop()
        }
    }

    @Test
    fun `32 个并发对话请求全部完成且不出现整批超时`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(QuickProvider())
        // 放开闸门，专门验证 HTTP 层承载能力
        engine.updateFailoverSettings(
            dev.aigw.core.failover.ModelFailoverSettings(
                concurrency = dev.aigw.core.failover.ConcurrencyConfig(maxPerAccount = 0, maxGlobal = 0),
            ),
        )
        repeat(8) { engine.pool.upsert(ProviderAccount("quick", "u$it", "账号$it", "{}")) }
        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }

        val ok = AtomicInteger()
        val failed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(32)
        try {
            val start = CountDownLatch(1)
            val futures = (1..32).map {
                pool.submit {
                    start.await()
                    val (status, _) = chat(server.listeningPort)
                    if (status == 200) ok.incrementAndGet() else failed.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            assertEquals(32, ok.get(), "全部并发请求都应成功，失败 ${failed.get()} 个")
        } finally {
            pool.shutdownNow()
            server.stop()
        }
    }
}
