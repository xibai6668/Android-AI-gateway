package dev.aigw.core.gateway

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingGroupsTest {

    private fun millisAt(dayOfWeekOffset: Int = 0, hour: Int, minute: Int): Long {
        // 固定到 2026-01-05（周一）再加偏移，避免依赖「今天」
        val base = LocalDateTime.of(2026, 1, 5, hour, minute)
            .plusDays(dayOfWeekOffset.toLong())
        return base.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    // ---- matches ---------------------------------------------------------------

    @Test
    fun `minPromptTokens 只在达到阈值时命中`() {
        val rule = GroupRule(targetProviderId = "trae", targetModel = "glm", minPromptTokens = 1000L)
        assertTrue(RoutingGroupResolver.matches(rule, RuleContext(promptTokens = 1000L)))
        assertFalse(RoutingGroupResolver.matches(rule, RuleContext(promptTokens = 999L)))
        // 0 表示不限
        val unlimited = GroupRule(targetProviderId = "trae", targetModel = "glm")
        assertTrue(RoutingGroupResolver.matches(unlimited, RuleContext(promptTokens = 0L)))
    }

    @Test
    fun `requireImage 与 compactOnly 是硬条件`() {
        val imageRule = GroupRule(targetProviderId = "trae", targetModel = "vision", requireImage = true)
        assertTrue(RoutingGroupResolver.matches(imageRule, RuleContext(hasImage = true)))
        assertFalse(RoutingGroupResolver.matches(imageRule, RuleContext(hasImage = false)))

        val compactRule = GroupRule(targetProviderId = "trae", targetModel = "fast", compactOnly = true)
        assertTrue(RoutingGroupResolver.matches(compactRule, RuleContext(compactRequest = true)))
        assertFalse(RoutingGroupResolver.matches(compactRule, RuleContext(compactRequest = false)))
    }

    @Test
    fun `folders 按路径段边界前缀匹配且不区分大小写`() {
        val rule = GroupRule(
            targetProviderId = "trae",
            targetModel = "glm",
            folders = listOf("~/work/acme"),
        )
        assertTrue(RoutingGroupResolver.matches(rule, RuleContext(folder = "~/work/acme/web")))
        assertTrue(RoutingGroupResolver.matches(rule, RuleContext(folder = "~/work/acme")))
        assertTrue(RoutingGroupResolver.matches(rule, RuleContext(folder = "~/Work/ACME/Web")))
        // 只是字符串前缀、没落在路径段边界上
        assertFalse(RoutingGroupResolver.matches(rule, RuleContext(folder = "~/work/acme-old")))
        assertFalse(RoutingGroupResolver.matches(rule, RuleContext(folder = "")))

        // 上下文给绝对路径、规则写 ~ 时退化成尾部匹配
        assertTrue(RoutingGroupResolver.matches(rule, RuleContext(folder = "/home/xibai/work/acme/web")))

        // 多个候选目录命中其一二
        val multi = rule.copy(folders = listOf("~/other", "~/work/acme"))
        assertTrue(RoutingGroupResolver.matches(multi, RuleContext(folder = "~/work/acme/web")))
        assertFalse(RoutingGroupResolver.matches(multi, RuleContext(folder = "~/work/zzz")))
    }

    @Test
    fun `时段含跨午夜与星期判定`() {
        val workHour = GroupRule(
            targetProviderId = "trae",
            targetModel = "glm",
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 18 * 60,
        )
        assertTrue(RoutingGroupResolver.matches(workHour, RuleContext(nowMillis = millisAt(hour = 9, minute = 0))))
        assertTrue(RoutingGroupResolver.matches(workHour, RuleContext(nowMillis = millisAt(hour = 18, minute = 0))))
        assertFalse(RoutingGroupResolver.matches(workHour, RuleContext(nowMillis = millisAt(hour = 18, minute = 1))))
        assertFalse(RoutingGroupResolver.matches(workHour, RuleContext(nowMillis = millisAt(hour = 8, minute = 59))))

        // 跨午夜：22:00 - 06:00
        val overnight = workHour.copy(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        assertTrue(RoutingGroupResolver.matches(overnight, RuleContext(nowMillis = millisAt(hour = 23, minute = 30))))
        assertTrue(RoutingGroupResolver.matches(overnight, RuleContext(nowMillis = millisAt(hour = 3, minute = 0))))
        assertFalse(RoutingGroupResolver.matches(overnight, RuleContext(nowMillis = millisAt(hour = 12, minute = 0))))

        // 星期：1=周一 … 7=周日
        val weekend = workHour.copy(startMinuteOfDay = -1, endMinuteOfDay = -1, daysOfWeek = setOf(6, 7))
        assertFalse(RoutingGroupResolver.matches(weekend, RuleContext(nowMillis = millisAt(hour = 12, minute = 0))))
        assertTrue(
            RoutingGroupResolver.matches(weekend, RuleContext(nowMillis = millisAt(dayOfWeekOffset = 5, hour = 12, minute = 0))),
        )
    }

    @Test
    fun `多条件必须同时满足`() {
        val rule = GroupRule(
            targetProviderId = "trae",
            targetModel = "glm",
            minPromptTokens = 500L,
            requireImage = true,
            folders = listOf("~/work/acme"),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 18 * 60,
        )
        val hit = RuleContext(
            promptTokens = 800L,
            hasImage = true,
            folder = "~/work/acme/web",
            nowMillis = millisAt(hour = 10, minute = 0),
        )
        assertTrue(RoutingGroupResolver.matches(rule, hit))
        assertFalse(RoutingGroupResolver.matches(rule, hit.copy(promptTokens = 100L)))
        assertFalse(RoutingGroupResolver.matches(rule, hit.copy(hasImage = false)))
        assertFalse(RoutingGroupResolver.matches(rule, hit.copy(folder = "~/work/other")))
        assertFalse(RoutingGroupResolver.matches(rule, hit.copy(nowMillis = millisAt(hour = 20, minute = 0))))
    }

    // ---- orderMembers ----------------------------------------------------------

    @Test
    fun `命中规则的成员排在最前且按规则声明顺序`() {
        val group = RoutingGroup(
            id = "g",
            strategy = GroupStrategy.ORDER,
            members = listOf(
                GroupMember("trae", "a", priority = 100),
                GroupMember("codebuddy", "b", priority = 90),
                GroupMember("antigravity", "c", priority = 80),
            ),
            rules = listOf(
                GroupRule(targetProviderId = "antigravity", targetModel = "c"),
                GroupRule(targetProviderId = "codebuddy", targetModel = "b"),
            ),
        )
        val ordered = RoutingGroupResolver.orderMembers(group, RuleContext(), Random(1))
        assertEquals(listOf("c", "b", "a"), ordered.map { it.model })
    }

    @Test
    fun `未命中规则时按 priority 降序 priority 相同则按 weight 排序`() {
        val group = RoutingGroup(
            id = "g",
            strategy = GroupStrategy.ORDER,
            members = listOf(
                GroupMember("p1", "low", priority = 10, weight = 5),
                GroupMember("p2", "high", priority = 100, weight = 1),
                // weight 0 视为不参与加权，落在同档末尾
                GroupMember("p3", "zero", priority = 10, weight = 0),
                GroupMember("p4", "mid", priority = 50, weight = 1),
            ),
            rules = listOf(GroupRule(targetProviderId = "p9", targetModel = "nope")),
        )
        val ordered = RoutingGroupResolver.orderMembers(group, RuleContext(), Random(3))
        assertEquals("high", ordered.first().model)
        assertEquals("zero", ordered.last().model)
        assertTrue(ordered.map { it.model }.indexOf("low") < ordered.map { it.model }.indexOf("zero"))
    }

    @Test
    fun `ORDER 策略下同 priority 的成员按权重被抽到前面`() {
        val group = RoutingGroup(
            id = "g",
            strategy = GroupStrategy.ORDER,
            members = listOf(
                GroupMember("p1", "heavy", priority = 100, weight = 1000),
                GroupMember("p2", "light", priority = 100, weight = 1),
            ),
        )
        // 权重悬殊，固定种子下几乎必然重权重在前；只断言排序结果仍是这两项且不含丢项
        val ordered = RoutingGroupResolver.orderMembers(group, RuleContext(), Random(7))
        assertEquals(setOf("heavy", "light"), ordered.map { it.model }.toSet())
        assertEquals("heavy", ordered.first().model)
    }

    @Test
    fun `WEIGHT 策略忽略 priority`() {
        val group = RoutingGroup(
            id = "g",
            strategy = GroupStrategy.WEIGHT,
            members = listOf(
                GroupMember("p1", "lowPriorityHeavy", priority = 1, weight = 1000),
                GroupMember("p2", "highPriorityLight", priority = 999, weight = 1),
            ),
        )
        val ordered = RoutingGroupResolver.orderMembers(group, RuleContext(), Random(11))
        assertEquals("lowPriorityHeavy", ordered.first().model)
    }

    @Test
    fun `成员按 providerId 加 model 去重并保留首次出现的位置`() {
        val group = RoutingGroup(
            id = "g",
            strategy = GroupStrategy.ORDER,
            members = listOf(
                GroupMember("trae", "a", priority = 100),
                GroupMember("trae", "a", priority = 1),
                GroupMember("trae", "b", priority = 50),
            ),
        )
        val ordered = RoutingGroupResolver.orderMembers(group, RuleContext(), Random(1))
        assertEquals(2, ordered.size)
        assertEquals("a", ordered.first().model)
        assertEquals(100, ordered.first().priority)
    }

    @Test
    fun `停用的组不应用规则但仍返回候选`() {
        val group = RoutingGroup(
            id = "g",
            enabled = false,
            members = listOf(
                GroupMember("trae", "a", priority = 100),
                GroupMember("trae", "b", priority = 10),
            ),
            rules = listOf(GroupRule(targetProviderId = "trae", targetModel = "b")),
        )
        val ordered = RoutingGroupResolver.orderMembers(group, RuleContext(), Random(1))
        assertEquals(listOf("a", "b"), ordered.map { it.model })
    }

    // ---- 请求体解析 -------------------------------------------------------------

    @Test
    fun `estimatePromptTokens 按字符数四舍五入后向上取整`() {
        val body = """
            {"messages":[
              {"role":"system","content":"abcd"},
              {"role":"user","content":[{"type":"text","text":"efghij"}]}
            ]}
        """.trimIndent()
        // 4 + 6 = 10 字符 → 向上取整 3
        assertEquals(3L, RoutingGroupResolver.estimatePromptTokens(body))
        // 空/无效请求体至少 1
        assertEquals(1L, RoutingGroupResolver.estimatePromptTokens("{}"))
        assertEquals(1L, RoutingGroupResolver.estimatePromptTokens("not json"))
    }

    @Test
    fun `bodyHasImage 识别多模态图片`() {
        val withImage = """
            {"messages":[{"role":"user","content":[
              {"type":"text","text":"看图"},
              {"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}}
            ]}]}
        """.trimIndent()
        assertTrue(RoutingGroupResolver.bodyHasImage(withImage))

        val textOnly = """
            {"messages":[{"role":"user","content":"纯文本"}]}
        """.trimIndent()
        assertFalse(RoutingGroupResolver.bodyHasImage(textOnly))
        assertFalse(RoutingGroupResolver.bodyHasImage("not json"))
    }

    @Test
    fun `isCompactRequest 识别压缩请求`() {
        val compact = """{"messages":[{"role":"user","content":"/compact"}]}"""
        assertTrue(RoutingGroupResolver.isCompactRequest(compact))

        val summarize = """{"messages":[{"role":"user","content":"Please summarize the conversation so far."}]}"""
        assertTrue(RoutingGroupResolver.isCompactRequest(summarize))

        val normal = """{"messages":[{"role":"user","content":"写个快排"}]}"""
        assertFalse(RoutingGroupResolver.isCompactRequest(normal))
        assertFalse(RoutingGroupResolver.isCompactRequest("{}"))
    }

    @Test
    fun `detectFolder 从请求体提取工作目录`() {
        val claude = """{"messages":[{"role":"user","content":"Working directory: /home/xibai/work/acme/web\nDo it"}]}"""
        assertEquals("/home/xibai/work/acme/web", RoutingGroupResolver.detectFolder(claude))

        val codex = """{"messages":[{"role":"user","content":"<cwd>/root/proj</cwd>"}]}"""
        assertEquals("/root/proj", RoutingGroupResolver.detectFolder(codex))

        val chinese = """{"messages":[{"role":"system","content":"当前目录：~/work/demo"}]}"""
        assertEquals("~/work/demo", RoutingGroupResolver.detectFolder(chinese))

        assertEquals("", RoutingGroupResolver.detectFolder("""{"messages":[]}"""))
        assertEquals("", RoutingGroupResolver.detectFolder("not json"))
    }

    // ---- 设置序列化 -------------------------------------------------------------

    @Test
    fun `toJson 与 fromJson 往返保持字段`() {
        val original = RoutingGroupSettings(
            groups = listOf(
                RoutingGroup(
                    id = "work",
                    name = "工作",
                    strategy = GroupStrategy.WEIGHT,
                    members = listOf(GroupMember("trae", "glm", priority = 120, weight = 3)),
                    rules = listOf(
                        GroupRule(
                            targetProviderId = "codebuddy",
                            targetModel = "sonnet",
                            minPromptTokens = 4096L,
                            requireImage = true,
                            compactOnly = true,
                            folders = listOf("~/work/acme"),
                            startMinuteOfDay = 540,
                            endMinuteOfDay = 1080,
                            daysOfWeek = setOf(1, 3, 5),
                        ),
                    ),
                ),
            ),
        )
        val parsed = RoutingGroupSettings.fromJson(original.toJson().toString())
        assertEquals(1, parsed.groups.size)
        val group = parsed.find("work")
        assertEquals("工作", group?.name)
        assertEquals(GroupStrategy.WEIGHT, group?.strategy)
        assertEquals(GroupMember("trae", "glm", 120, 3), group?.members?.single())
        val rule = group?.rules?.single()
        assertEquals("codebuddy", rule?.targetProviderId)
        assertEquals(4096L, rule?.minPromptTokens)
        assertEquals(true, rule?.requireImage)
        assertEquals(true, rule?.compactOnly)
        assertEquals(listOf("~/work/acme"), rule?.folders)
        assertEquals(540, rule?.startMinuteOfDay)
        assertEquals(1080, rule?.endMinuteOfDay)
        assertEquals(setOf(1, 3, 5), rule?.daysOfWeek)
    }

    @Test
    fun `损坏或空 JSON 返回空配置且未知 strategy 落回 ORDER`() {
        assertEquals(0, RoutingGroupSettings.fromJson(null).groups.size)
        assertEquals(0, RoutingGroupSettings.fromJson("").groups.size)
        assertEquals(0, RoutingGroupSettings.fromJson("{ broken").groups.size)
        assertEquals(0, RoutingGroupSettings.fromJson("""{"groups":"nope"}""").groups.size)

        val unknownStrategy = RoutingGroupSettings.fromJson(
            """{"groups":[{"id":"g","strategy":"ROUND_ROBIN","members":[{"providerId":"trae","model":"m"}]}]}""",
        )
        assertEquals(GroupStrategy.ORDER, unknownStrategy.find("g")?.strategy)
        // 缺 priority/weight 用默认值
        assertEquals(100, unknownStrategy.find("g")?.members?.single()?.priority)
        assertEquals(1, unknownStrategy.find("g")?.members?.single()?.weight)

        // 缺 id 的组整体跳过；缺 providerId 的成员跳过
        val partial = RoutingGroupSettings.fromJson(
            """{"groups":[{"name":"no id"},{"id":"ok","members":[{"model":"m"}]}]}""",
        )
        assertEquals(1, partial.groups.size)
        assertEquals(0, partial.find("ok")?.members?.size)
    }

    @Test
    fun `groupIdOf 识别 group 前缀与裸 id`() {
        val settings = RoutingGroupSettings(groups = listOf(RoutingGroup(id = "work", name = "工作")))
        assertEquals("work", RoutingGroupSettings.groupIdOf("group/work", settings))
        assertEquals("work", RoutingGroupSettings.groupIdOf("work", settings))
        // 不存在的组一律当普通模型名
        assertNull(RoutingGroupSettings.groupIdOf("group/missing", settings))
        assertNull(RoutingGroupSettings.groupIdOf("gpt-4o", settings))
        assertNull(RoutingGroupSettings.groupIdOf("group/", settings))
        assertNull(RoutingGroupSettings.groupIdOf("", settings))
    }
}
