package dev.aigw.core.failover

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewayHttpServer
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.usage.CallStatus
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FailoverIntegrationTest {

    private inner class ConfigurableProvider(
        override val id: String,
        private val supportedModels: List<String>,
        var behavior: (account: ProviderAccount, body: String) -> ChatCall,
    ) : Provider {
        override val displayName = "Provider-$id"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            models = supportedModels.map { ProviderModel(it, it) },
            fromFallback = false,
            error = "",
        )
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String): UpstreamError = when (status) {
            400 -> UpstreamError(ErrorKind.CLIENT, "invalid_request_error")
            401 -> UpstreamError(ErrorKind.SESSION_DEAD, "unauthorized")
            429 -> UpstreamError(ErrorKind.SOFT_RATE, "rate limit")
            else -> UpstreamError(ErrorKind.SERVER, "upstream error $status")
        }
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            behavior(account, openAiBody)
    }

    private fun post(serverPort: Int, model: String, bodyContent: String = "hi"): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$serverPort/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val req = """{"model":"$model","messages":[{"role":"user","content":"$bodyContent"}]}"""
        conn.outputStream.write(req.toByteArray(Charsets.UTF_8))
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return status to text
    }

    @Test
    fun `显式配置多候选优先级且成功透明归一化输出`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        // p1: 优先级 100（首选），但返回 500
        val p1 = ConfigurableProvider("p1", listOf("glm-5.3-upstream-p1")) { _, _ ->
            p1Calls++
            FailedChatCall(500, "{\"error\": \"p1 temporary down\"}")
        }
        // p2: 优先级 80（备选），返回 200
        val p2 = ConfigurableProvider("p2", listOf("glm-5.3-upstream-p2")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, """{"id":"c2","object":"chat.completion","model":"glm-5.3-upstream-p2","choices":[{"index":0,"message":{"role":"assistant","content":"来自备用供应商P2的回复"}}]}""")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("p1", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p2", "u2", "账号2", "{}"))

        // 配置路由表：将逻辑模型 glm-5.3-flash 映射到 p1 (优先 100) 与 p2 (优先 80)
        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                retry = RetryConfig(maxAttempts = 2, initialBackoffMs = 10L),
                routes = mapOf(
                    "glm-5.3-flash" to listOf(
                        FailoverCandidate("p1", "glm-5.3-upstream-p1", priority = 100),
                        FailoverCandidate("p2", "glm-5.3-upstream-p2", priority = 80),
                    ),
                ),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, resp) = post(server.listeningPort, "glm-5.3-flash")
            assertEquals(200, status, "应在故障转移至 P2 后成功返回 200")
            assertTrue(resp.contains("来自备用供应商P2的回复"), "内容应来自 P2: $resp")
            // 对调用方透明：返回报文中的 model 字段必须是客户端请求的逻辑模型 "glm-5.3-flash"
            assertTrue(resp.contains("\"model\":\"glm-5.3-flash\""), "必须透明归一化为客户端请求的模型名: $resp")
            // p1 尝试了 2 次重试耗尽，随后切换到 p2 尝试 1 次成功
            assertEquals(2, p1Calls)
            assertEquals(1, p2Calls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `401 凭证失效错误立即切换下一个供应商且不浪费重试次数`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        // p1: 401 鉴权失效
        val p1 = ConfigurableProvider("p1", listOf("m")) { _, _ ->
            p1Calls++
            FailedChatCall(401, "{\"error\": \"invalid_token\"}")
        }
        // p2: 正常返回 200
        val p2 = ConfigurableProvider("p2", listOf("m")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"p2 ok"}}]}""")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("p1", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p2", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                retry = RetryConfig(maxAttempts = 3, initialBackoffMs = 10L),
                routes = mapOf(
                    "my-model" to listOf(
                        FailoverCandidate("p1", "m", priority = 100),
                        FailoverCandidate("p2", "m", priority = 50),
                    ),
                ),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, resp) = post(server.listeningPort, "my-model")
            assertEquals(200, status)
            assertTrue(resp.contains("p2 ok"))
            // p1 遇到 401 应判定为 SWITCH_NEXT_PROVIDER，只调用 1 次即刻跳过后续 2 次重试
            assertEquals(1, p1Calls, "401 不可恢复错误不应在当前供应商内重复重试")
            assertEquals(1, p2Calls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `客户端 400 参数或审核错误立即向客户端报错绝不换供应商`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        // p1: 报 400 参数错误 (CLIENT)
        val p1 = ConfigurableProvider("p1", listOf("m")) { _, _ ->
            p1Calls++
            FailedChatCall(400, "{\"error\":{\"code\":\"invalid_request_error\",\"message\":\"max_tokens too large\"}}")
        }
        val p2 = ConfigurableProvider("p2", listOf("m")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, "{}")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("p1", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p2", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                routes = mapOf(
                    "my-model" to listOf(
                        FailoverCandidate("p1", "m", priority = 100),
                        FailoverCandidate("p2", "m", priority = 50),
                    ),
                ),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, resp) = post(server.listeningPort, "my-model")
            assertEquals(400, status, "客户端参数错必须向客户端返回 400")
            assertTrue(resp.contains("upstream_rejected"))
            assertEquals(1, p1Calls)
            assertEquals(0, p2Calls, "客户端错误绝不能污染备选供应商")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `客户端错误不计入供应商熔断，健康供应商不被误隔离`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        // p1 对客户端参数错误稳定回 400（CLIENT）：这不是 p1 的故障
        val p1 = ConfigurableProvider("p1", listOf("m")) { _, _ ->
            p1Calls++
            FailedChatCall(400, "{\"error\":{\"code\":\"invalid_request_error\",\"message\":\"bad model\"}}")
        }
        val p2 = ConfigurableProvider("p2", listOf("m")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, "{}")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("p1", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p2", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                // 阈值设 3：若客户端错误被误计入，第 3 次就会熔断 p1
                circuitBreaker = CircuitBreakerConfig(failureThreshold = 3, openDurationMs = 60_000L),
                routes = mapOf(
                    "my-model" to listOf(
                        FailoverCandidate("p1", "m", priority = 100),
                        FailoverCandidate("p2", "m", priority = 50),
                    ),
                ),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            repeat(4) {
                val (status, _) = post(server.listeningPort, "my-model")
                assertEquals(400, status)
            }
            assertEquals(4, p1Calls, "客户端错误不应导致 p1 被熔断跳过")
            assertEquals(0, p2Calls, "客户端错误绝不能污染备选供应商")
            assertEquals(
                BreakerState.CLOSED,
                engine.circuitBreakerOf("p1").currentState(),
                "连续 4 次客户端 400 不应把健康供应商打成熔断",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `上游侧错误仍计入熔断`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val p1 = ConfigurableProvider("p1", listOf("m")) { _, _ -> FailedChatCall(500, "p1 down") }
        val p2 = ConfigurableProvider("p2", listOf("m")) { _, _ -> AggregatedChatCall(200, "{}") }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("p1", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p2", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                retry = RetryConfig(maxAttempts = 1, initialBackoffMs = 10L),
                circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, openDurationMs = 60_000L),
                routes = mapOf(
                    "my-model" to listOf(
                        FailoverCandidate("p1", "m", priority = 100),
                        FailoverCandidate("p2", "m", priority = 50),
                    ),
                ),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            post(server.listeningPort, "my-model")
            post(server.listeningPort, "my-model")
            assertEquals(
                BreakerState.OPEN,
                engine.circuitBreakerOf("p1").currentState(),
                "上游 5xx 必须计入熔断",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `上游失败时调用记录带真实 HTTP 状态而非硬编码 200`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val p1 = ConfigurableProvider("p1", listOf("m")) { _, _ -> FailedChatCall(500, "p1 down") }
        val p2 = ConfigurableProvider("p2", listOf("m")) { _, _ -> FailedChatCall(502, "p2 bad gateway") }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("p1", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p2", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                retry = RetryConfig(maxAttempts = 1, initialBackoffMs = 10L),
                routes = mapOf(
                    "my-model" to listOf(
                        FailoverCandidate("p1", "m", priority = 100),
                        FailoverCandidate("p2", "m", priority = 50),
                    ),
                ),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, _) = post(server.listeningPort, "my-model")
            assertEquals(503, status)
            val record = engine.callLogStore.list().firstOrNull()
            assertTrue(record != null, "全候选失败也应留下一条调用记录")
            assertEquals(502, record.httpStatus, "应记录最后一次上游的真实状态")
            assertEquals(CallStatus.FAILED, record.status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `熔断开启的供应商直接被跳过`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        val p1 = ConfigurableProvider("p1", listOf("m")) { _, _ ->
            p1Calls++
            AggregatedChatCall(200, "{}")
        }
        val p2 = ConfigurableProvider("p2", listOf("m")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"p2 ok"}}]}""")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("p1", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p2", "u2", "账号2", "{}"))

        // 手动将 p1 打入熔断状态
        val cb1 = engine.circuitBreakerOf("p1")
        repeat(5) { cb1.recordFailure() }
        assertEquals(BreakerState.OPEN, cb1.currentState())

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                circuitBreaker = CircuitBreakerConfig(failureThreshold = 3, openDurationMs = 60_000L),
                routes = mapOf(
                    "my-model" to listOf(
                        FailoverCandidate("p1", "m", priority = 100),
                        FailoverCandidate("p2", "m", priority = 50),
                    ),
                ),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, resp) = post(server.listeningPort, "my-model")
            assertEquals(200, status)
            assertTrue(resp.contains("p2 ok"))
            assertEquals(0, p1Calls, "处于熔断状态的 p1 应直接被跳过，不发起任何物理网络调用")
            assertEquals(1, p2Calls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `所有候选供应商均耗尽时聚合报错返回 503`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val p1 = ConfigurableProvider("p1", listOf("m")) { _, _ -> FailedChatCall(500, "p1 down") }
        val p2 = ConfigurableProvider("p2", listOf("m")) { _, _ -> FailedChatCall(504, "p2 timeout") }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("p1", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("p2", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                retry = RetryConfig(maxAttempts = 1, initialBackoffMs = 10L),
                routes = mapOf(
                    "my-model" to listOf(
                        FailoverCandidate("p1", "m", priority = 100),
                        FailoverCandidate("p2", "m", priority = 50),
                    ),
                ),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, resp) = post(server.listeningPort, "my-model")
            assertEquals(503, status)
            assertTrue(resp.contains("所有候选供应商均不可用"))
            assertTrue(resp.contains("p1"))
            assertTrue(resp.contains("p2"))
        } finally {
            server.stop()
        }
    }
}
