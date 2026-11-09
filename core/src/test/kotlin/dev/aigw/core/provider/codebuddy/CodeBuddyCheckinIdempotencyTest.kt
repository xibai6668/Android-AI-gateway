package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ACTION_CHECKIN
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 签到幂等回执的回归：上游对「今天已签到」有两种返回形态，都必须算成功。
 *
 * 真实事故（2026-11-09，自动签到试跑现场）：WorkBuddy 国内版返回
 * **HTTP 400 + {"msg":"今天已签到，请明天再来"}**，旧实现在 status 分支直接判失败，
 * 于是自动签到的 2 个账号永远「成功 1、失败 1」→ 不记完成日 → 每小时重试一次，
 * 明天真签到成功也会被这条误判拖住。幂等判定必须在状态检查之前。
 */
class CodeBuddyCheckinIdempotencyTest {

    private class FakeUpstream(private val status: Int, private val body: String) {
        private lateinit var server: HttpServer
        val paths = ArrayList<String>()

        fun start(): String {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                paths.add(exchange.requestURI.path)
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
            return "http://127.0.0.1:${server.address.port}"
        }

        fun stop() = server.stop(0)
    }

    private fun provider(base: String) = CodeBuddyProvider(
        region = { CodeBuddyProvider.REGION_CN },
        endpointFor = {
            CodeBuddyProvider.Companion.Endpoint(
                label = "假上游",
                chatBase = base,
                billingBase = base,
                origin = base,
                modelsPath = "/models",
            )
        },
        pause = {},
    )

    private fun account(p: CodeBuddyProvider) = assertNotNull(
        p.importCredentials("""{"accessToken":"tok","uid":"u1","domain":"www.codebuddy.cn"}"""),
    )

    private fun run(status: Int, body: String, block: (ok: Boolean, message: String, paths: List<String>) -> Unit) {
        val upstream = FakeUpstream(status, body)
        val base = upstream.start()
        try {
            val p = provider(base)
            val result = p.performAction(account(p), ACTION_CHECKIN, JsonObject())
            block(result.ok, result.message, upstream.paths)
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `HTTP 400 已签到 判成功`() {
        run(400, """{"msg":"今天已签到，请明天再来"}""") { ok, message, paths ->
            assertTrue(ok, "4xx + 已签到必须归一为成功，实际：$message")
            assertEquals("今日已签到", message)
            assertTrue(paths.any { it.contains("daily-checkin") }, "应真的打过签到端点：$paths")
        }
    }

    @Test
    fun `HTTP 200 业务码已签到 判成功`() {
        run(200, """{"code":1001,"msg":"已签到"}""") { ok, message, _ ->
            assertTrue(ok, "旧路径也必须保持成功，实际：$message")
            assertEquals("今日已签到", message)
        }
    }

    @Test
    fun `HTTP 200 code 0 判签到成功`() {
        run(200, """{"code":0,"msg":"ok"}""") { ok, message, _ ->
            assertTrue(ok)
            assertEquals("签到成功", message)
        }
    }

    @Test
    fun `HTTP 400 非幂等错误 仍然判失败`() {
        // 负控：不能把所有 4xx 都宽大成成功，否则真故障会被吞掉。
        run(400, """{"msg":"凭证已失效"}""") { ok, message, _ ->
            assertFalse(ok, "非幂等的 400 必须是失败")
            assertTrue(message.contains("HTTP 400"), "失败原因要带状态码：$message")
            assertTrue(message.contains("凭证已失效"), "失败原因要带上游原文：$message")
        }
    }

    @Test
    fun `HTTP 500 已签到文案也判成功`() {
        // 上游把幂等回执放在错误正文里时，状态码并不可靠；文案是唯一稳定判据。
        run(500, """{"msg":"already checked in today"}""") { ok, message, _ ->
            assertTrue(ok, "英文 already 也要命中，实际：$message")
            assertEquals("今日已签到", message)
        }
    }
}
