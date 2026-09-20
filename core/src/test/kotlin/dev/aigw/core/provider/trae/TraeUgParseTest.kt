package dev.aigw.core.provider.trae

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraeUgParseTest {

    private val pack = """
        {"entitlement_base_info":{"quota":{"credits_limit":2000}},"usage":{"credits_amount":1500.4}}
    """.trimIndent()

    @Test
    fun `顶层权益包列表能被聚合`() {
        val usage = TraeUgParse.aggregateEntitlement("""{"user_entitlement_pack_list":[$pack]}""")
        assertNotNull(usage)
        assertEquals(2000L, usage.limit)
        assertEquals(1500L, usage.used)
        assertEquals(500L, usage.remain)
        assertEquals(1, usage.packs)
    }

    @Test
    fun `data 与 result 包裹的结构同样能解析`() {
        val wrappedInData = TraeUgParse.aggregateEntitlement(
            """{"code":0,"data":{"user_entitlement_pack_list":[$pack]}}""",
        )
        assertEquals(500L, wrappedInData?.remain)

        val wrappedInResult = TraeUgParse.aggregateEntitlement(
            """{"Result":{"user_entitlement_pack_list":[$pack]}}""",
        )
        assertEquals(500L, wrappedInResult?.remain)
    }

    @Test
    fun `多个权益包求和，忽略没有额度上限的包`() {
        val raw = """
            {"user_entitlement_pack_list":[
              {"entitlement_base_info":{"quota":{"credits_limit":2000}},"usage":{"credits_amount":500}},
              {"entitlement_base_info":{"quota":{"credits_limit":500}},"usage":{"credits_amount":0}},
              {"entitlement_base_info":{"quota":{"credits_limit":0}}}
            ]}
        """.trimIndent()
        val usage = TraeUgParse.aggregateEntitlement(raw)
        assertEquals(2500L, usage?.limit)
        assertEquals(500L, usage?.used)
        assertEquals(2000L, usage?.remain)
        assertEquals(2, usage?.packs)
    }

    @Test
    fun `没有 usage 字段时按未使用计算`() {
        val raw = """{"user_entitlement_pack_list":[{"entitlement_base_info":{"quota":{"credits_limit":200}}}]}"""
        assertEquals(200L, TraeUgParse.aggregateEntitlement(raw)?.remain)
    }

    @Test
    fun `空列表是合法结果而不是解析失败`() {
        val usage = TraeUgParse.aggregateEntitlement("""{"user_entitlement_pack_list":[]}""")
        assertNotNull(usage, "字段存在但为空应视为真的没有额度")
        assertEquals(0, usage.packs)
    }

    @Test
    fun `完全没有权益包字段时返回 null 以便换下一个接口`() {
        assertNull(TraeUgParse.aggregateEntitlement("""{"code":0,"data":{"foo":1}}"""))
        assertNull(TraeUgParse.aggregateEntitlement("not json"))
    }

    @Test
    fun `额度包明细包含名称、分组与过期时间`() {
        val raw = """
            {"is_credits_billing":true,
             "usage_summary":{"consumed_amount":982.72,"total_amount":5700},
             "user_entitlement_pack_list":[
               {"display_desc":"老用户福利","group_name":"用户福利","expire_time":1789623315,
                "entitlement_base_info":{"quota":{"credits_limit":2000},"end_time":1789623315},
                "usage":{"credits_amount":482.7212}},
               {"display_desc":"签到奖励","group_name":"每日签到","expire_time":1789624063,
                "entitlement_base_info":{"quota":{"credits_limit":200},"end_time":1789624063},
                "usage":{}}
             ]}
        """.trimIndent()
        val usage = TraeUgParse.aggregateEntitlement(raw)
        assertNotNull(usage)
        assertEquals(2, usage.packs)
        assertEquals(2200L, usage.limit)
        assertEquals(482L, usage.used)
        assertEquals(1718L, usage.remain)

        val welfare = usage.details[0]
        assertEquals("老用户福利", welfare.name)
        assertEquals("用户福利", welfare.group)
        assertEquals(2000L, welfare.limit)
        assertEquals(1518L, welfare.remain)
        assertEquals(1789623315L, welfare.expireAt)

        // 刚发的签到奖励没有 usage 字段，按未使用算
        val checkin = usage.details[1]
        assertEquals("每日签到", checkin.group)
        assertEquals(200L, checkin.remain)
        assertEquals(0L, checkin.used)
    }

    @Test
    fun `额度包名称回退到 package_name`() {
        val raw = """
            {"user_entitlement_pack_list":[{
              "entitlement_base_info":{"quota":{"credits_limit":100},
                "product_extra":{"package_extra":{"package_name":"福利积分"}}}}]}
        """.trimIndent()
        assertEquals("福利积分", TraeUgParse.aggregateEntitlement(raw)?.details?.first()?.name)
    }

    @Test
    fun `签到状态兼容顶层与 data 包裹以及 enabled 别名`() {
        val flat = TraeUgParse.parseStatus("""{"checked_in":true,"credits":200,"enable":true}""")
        assertEquals(true, flat?.checkedIn)
        assertEquals(200L, flat?.credits)
        assertEquals(true, flat?.enabled)

        val wrapped = TraeUgParse.parseStatus("""{"code":0,"data":{"checked_in":false,"enabled":true}}""")
        assertEquals(false, wrapped?.checkedIn)
        assertEquals(true, wrapped?.enabled)

        assertNull(TraeUgParse.parseStatus("not json"))
    }

    @Test
    fun `业务成功判定覆盖常见信封`() {
        assertTrue(TraeUgParse.succeeded("""{"code":0}"""))
        assertTrue(TraeUgParse.succeeded("""{"code":200}"""))
        assertTrue(TraeUgParse.succeeded("""{"success":true}"""))
        assertTrue(TraeUgParse.succeeded("""{"status":"success"}"""))
        assertTrue(TraeUgParse.succeeded("""{}"""), "没有任何状态字段时按 HTTP 2xx 成功")
        assertFalse(TraeUgParse.succeeded("""{"code":9074,"message":"当前使用人数太多"}"""))
        assertFalse(TraeUgParse.succeeded("""{"code":1001,"message":"今日已签到"}"""))
    }

    @Test
    fun `已签到判定包括 1001 与中文文案`() {
        assertTrue(TraeUgParse.alreadyCheckedIn("""{"code":1001}"""))
        assertTrue(TraeUgParse.alreadyCheckedIn("""{"code":9074,"message":"今日已签到"}"""))
        assertTrue(TraeUgParse.alreadyCheckedIn("""{"code":1,"msg":"already checked in"}"""))
        assertFalse(TraeUgParse.alreadyCheckedIn("""{"code":9074,"message":"当前使用人数太多"}"""))
    }
}

class NotifyUsageEventTest {

    private fun parseAll(text: String): List<SoloEvent> {
        val parser = SoloSseParser()
        val events = ArrayList<SoloEvent>()
        for (line in text.split('\n')) parser.feed(line)?.let { events.add(it) }
        return events
    }

    @Test
    fun `解析 notify_usage 里的 ide_credits 与 work_credits`() {
        val events = parseAll(
            "event:notify_usage\n" +
                "data:{\"billing_mode\":\"credits\",\"cn_credits_remain_info\":{\"ide_credits\":312,\"work_credits\":2000}}\n\n",
        )
        val notify = events.single() as SoloEvent.NotifyUsage
        assertEquals(312L, notify.ideCredits)
        assertEquals(2000L, notify.workCredits)
    }

    @Test
    fun `缺少积分字段时为 null 而不是 0`() {
        val events = parseAll("event:notify_usage\ndata:{\"billing_mode\":\"credits\"}\n\n")
        val notify = events.single() as SoloEvent.NotifyUsage
        assertNull(notify.ideCredits)
        assertNull(notify.workCredits)
    }

    @Test
    fun `notify_usage 不会被转发成客户端 chunk`() {
        val translator = OpenAiSseTranslator("id1", "m")
        val chunk = translator.translate(SoloEvent.NotifyUsage(ideCredits = 1, workCredits = 2))
        assertTrue(chunk.isEmpty())
    }

    @Test
    fun `计费回报里的原始字段仍可读取`() {
        val raw = JsonParser.parseString(
            """{"cn_credits_remain_info":{"ide_credits":9,"work_credits":8}}""",
        ).asJsonObject
        assertEquals(9L, raw.getAsJsonObject("cn_credits_remain_info").get("ide_credits").asLong)
    }
}
