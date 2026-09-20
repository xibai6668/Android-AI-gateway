package dev.aigw.core.gateway

import com.google.gson.JsonParser
import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.util.objOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/** 对话流内「本次积分消耗」的解析：字段清单照官方 Web 客户端的 turnPoints 读取顺序。 */
class PointsConsumedTest {

    private fun consumed(json: String): Long {
        val chunk = JsonParser.parseString(json).asJsonObject
        val server = GatewayHttpServer(GatewayEngine(InMemoryKeyValueStore()), "127.0.0.1", 0)
        return server.pointsConsumedOf(chunk, chunk.objOrNull("usage"))
    }

    @Test
    fun `顶层字段优先于 usage`() {
        assertEquals(5L, consumed("""{"choices":[],"points_consumed":5,"usage":{"points_consumed":9}}"""))
    }

    @Test
    fun `usage 里的字段也能取到`() {
        assertEquals(3L, consumed("""{"usage":{"points_consumed":3}}"""))
        assertEquals(7L, consumed("""{"usage":{"total_points":7}}"""))
        assertEquals(2L, consumed("""{"usage":{"cost_points":2}}"""))
    }

    @Test
    fun `没有积分字段时返回 0`() {
        assertEquals(0L, consumed("""{"choices":[{"delta":{"content":"hi"}}]}"""))
        assertEquals(0L, consumed("""{"usage":null}"""))
    }
}
