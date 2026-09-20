package dev.aigw.core.provider.trae

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 上游异常的用户可读文案。
 *
 * 回归：`TraeHttpException` 的 message 曾经是
 * `upstream http 200 kind=CLIENT body=活动暂不可用` 这种调试格式，
 * 而 GatewayEngine 会把它直接当提示弹给用户——上游那句人话被埋进了 `body=` 里。
 * 现在 message 必须是能直接展示的句子，调试信息改由 [TraeHttpException.debugSummary] 提供。
 */
class TraeErrorTextTest {

    @Test
    fun `优先展示上游原话而不是调试格式`() {
        val body = """{"code":123,"message":"活动暂不可用"}"""
        val e = TraeHttpException(200, TraeErrorKind.CLIENT, body, 123L)

        assertEquals("活动暂不可用", e.message)
        // 不能出现任何调试格式的痕迹
        assertFalse(e.message!!.contains("kind="), "不应把 kind= 暴露给用户")
        assertFalse(e.message!!.contains("upstream"), "不应把 upstream 暴露给用户")
        assertFalse(e.message!!.contains("http 200"), "不应把原始状态码格式暴露给用户")
    }

    @Test
    fun `msg 字段也能取到上游原话`() {
        val e = TraeHttpException(200, TraeErrorKind.CLIENT, """{"code":9,"msg":"参数不合法"}""")
        assertEquals("参数不合法", e.message)
    }

    @Test
    fun `嵌套在 data 里的消息也能取到`() {
        val e = TraeHttpException(200, TraeErrorKind.CLIENT, """{"data":{"code":7,"message":"资源不存在"}}""")
        assertEquals("资源不存在", e.message)
    }

    @Test
    fun `取不到上游文案时按错误码给中文说明`() {
        assertEquals(
            "凭证已失效，需要重新登录",
            TraeHttpException(401, TraeErrorKind.SESSION_DEAD, "", 1001L).message,
        )
        assertEquals(
            "额度已耗尽，等每日重置或签到",
            TraeHttpException(200, TraeErrorKind.PLAN_LIMIT, """{"code":4008}""", 4008L).message,
        )
        assertEquals(
            "请求过于频繁，已被上游限流",
            TraeHttpException(429, TraeErrorKind.SOFT_RATE, "").message,
        )
    }

    @Test
    fun `再取不到就按分类兜底，仍包含状态码便于定位`() {
        assertEquals(
            "上游服务异常（HTTP 502）",
            TraeHttpException(502, TraeErrorKind.SERVER, "").message,
        )
        assertEquals(
            "上游拒绝了本次请求（HTTP 400）",
            TraeHttpException(400, TraeErrorKind.CLIENT, "").message,
        )
    }

    @Test
    fun `没有 message 字段时不会把原始 JSON 弹给用户`() {
        // extractMessage 找不到 message/msg 时会回退返回原始 body（给日志用的），
        // 直接展示就会出现「上游拒绝了本次请求」变成 {"code":4008} 这种事
        val e = TraeHttpException(200, TraeErrorKind.PLAN_LIMIT, """{"code":4008}""", 4008L)
        assertEquals("额度已耗尽，等每日重置或签到", e.message)
        assertFalse(e.message!!.startsWith("{"), "不能把 JSON 报文当文案")
    }

    @Test
    fun `没有 message 字段且码未知时回退到分类说明`() {
        val e = TraeHttpException(200, TraeErrorKind.CLIENT, """{"code":9999}""", 9999L)
        assertEquals("上游拒绝了本次请求（HTTP 200）", e.message)
    }

    @Test
    fun `HTML 错误页不会原样展示给用户`() {
        val html = "<html><body><h1>502 Bad Gateway</h1></body></html>"
        val e = TraeHttpException(502, TraeErrorKind.SERVER, html)
        assertFalse(e.message!!.contains("<html>"), "HTML 不能直接弹给用户")
        assertEquals("上游服务异常（HTTP 502）", e.message)
    }

    @Test
    fun `超长上游文案不直接展示`() {
        val long = "x".repeat(500)
        val e = TraeHttpException(500, TraeErrorKind.SERVER, """{"message":"$long"}""")
        assertFalse(e.message!!.contains(long), "超长文案应回退到按分类的说明")
        assertEquals("上游服务异常（HTTP 500）", e.message)
    }

    @Test
    fun `调试信息仍完整保留在 debugSummary 与字段里`() {
        val body = """{"code":123,"message":"活动暂不可用"}"""
        val e = TraeHttpException(200, TraeErrorKind.CLIENT, body, 123L)

        assertTrue(e.debugSummary().contains("upstream http 200"))
        assertTrue(e.debugSummary().contains("kind=CLIENT"))
        assertTrue(e.debugSummary().contains("code=123"))
        assertTrue(e.debugSummary().contains("活动暂不可用"))
        // 原始字段仍可直接读，供日志与重试逻辑使用
        assertEquals(200, e.status)
        assertEquals(TraeErrorKind.CLIENT, e.kind)
        assertEquals(123L, e.upstreamCode)
        assertEquals(body, e.body)
    }
}
