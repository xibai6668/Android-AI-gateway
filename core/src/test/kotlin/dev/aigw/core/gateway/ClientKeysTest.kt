package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.util.startOfDay
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 客户端密钥存储与用量台账的行为锁定。
 *
 * 台账所有时间判断都靠注入的 `nowMillis`，因此这里用可变 `now` 精确推进窗口边界，
 * 不依赖真实时钟，也不会因测试跑到午夜而抖动。
 */
class ClientKeysTest {

    private val store = InMemoryKeyValueStore()

    /** 注入时钟：测试里改它来跨天、跨周期。 */
    private var now: Long = 0L

    private val keys = ClientKeyStore(store) { now }
    private val ledger = ClientKeyUsageLedger(store) { now }

    private fun exceeded(verdict: LimitVerdict): LimitVerdict.Exceeded =
        verdict as? LimitVerdict.Exceeded ?: fail("期望超额，实际: $verdict")

    private fun plusDays(millis: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    /** 构造「月中、周中」的本地零点，避免跨天恰好跨周/跨月导致断言失真。 */
    private fun midMonthMidWeekMidnight(): Long = Calendar.getInstance().apply {
        set(2024, Calendar.JANUARY, 10, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---- ClientKeyStore 基本读写 ----

    @Test
    fun `add list byId update remove 往返`() {
        now = 1_700_000_000_000L
        val first = keys.add("一号")
        val second = keys.add("二号", secret = "sk-gw-custom-secret-01")

        assertEquals(listOf(first, second), keys.list())
        assertEquals(first, keys.byId(first.id))
        assertEquals(1_700_000_000_000L, first.createdAtMillis, "createdAt 来自注入时钟")

        keys.update(first.copy(name = "改名", enabled = false))
        val updated = assertNotNull(keys.byId(first.id))
        assertEquals("改名", updated.name)
        assertFalse(updated.enabled)
        assertEquals(second, keys.byId(second.id), "更新一条不该影响另一条")

        assertTrue(keys.remove(first.id))
        assertNull(keys.byId(first.id))
        assertEquals(listOf(second), keys.list())
        assertFalse(keys.remove(first.id), "重复删除返回 false")
        assertFalse(keys.remove("ck_不存在的 id"))
    }

    @Test
    fun `update 未知 id 时追加`() {
        val ghost = ClientKey(id = "ck_ghost", name = "外部导入", secret = "sk-gw-ghost-secret")
        keys.update(ghost)
        assertEquals(ghost, keys.byId(ghost.id))
    }

    // ---- secret 生成与校验 ----

    @Test
    fun `不给 secret 时生成 sk-gw 前缀的随机串`() {
        val key = keys.add("自动")
        assertTrue(key.secret.startsWith(ClientKeyStore.SECRET_PREFIX), "实际: ${key.secret}")
        // "sk-gw-" 6 位 + 16 字节 hex 32 位
        assertEquals(38, key.secret.length)
        assertNotEquals(key.secret, keys.add("自动二").secret, "两次生成不应重复")
    }

    @Test
    fun `非法 secret 抛 IllegalArgumentException`() {
        keys.add("已有", secret = "sk-gw-existing-secret")

        assertFailsWith<IllegalArgumentException> { keys.add("太短", secret = "abc1234") }
        assertFailsWith<IllegalArgumentException> { keys.add("含空格", secret = "sk-gw bad secret") }
        assertFailsWith<IllegalArgumentException> { keys.add("重复", secret = "sk-gw-existing-secret") }
        assertFailsWith<IllegalArgumentException> { keys.add("非 ASCII", secret = "sk-gw-密钥密钥") }

        assertEquals(1, keys.list().size, "校验失败不该写入任何条目")
    }

    // ---- 轮换 ----

    @Test
    fun `rotate 只换 secret 其余字段与其它 key 不变`() {
        val first = keys.add(
            "甲",
            secret = "sk-gw-first-secret",
            allowedModels = listOf("trae/*"),
            limits = listOf(KeyLimit(LimitPeriod.DAY, maxTokens = 100)),
        )
        val second = keys.add("乙", secret = "sk-gw-second-secret")

        val rotated = assertNotNull(keys.rotate(first.id))
        assertTrue(rotated.startsWith(ClientKeyStore.SECRET_PREFIX))
        assertNotEquals(first.secret, rotated)

        val after = assertNotNull(keys.byId(first.id))
        assertEquals(first.copy(secret = rotated), after, "id/name/allowedModels/limits/createdAt 全应保持")

        assertEquals("sk-gw-second-secret", assertNotNull(keys.byId(second.id)).secret)

        assertEquals(after, keys.authenticate(rotated), "新 secret 可鉴定")
        assertNull(keys.authenticate(first.secret), "旧 secret 立即失效")
        assertNull(keys.rotate("ck_不存在"))
    }

    @Test
    fun `rotate 指定 secret 时同样校验合法性`() {
        val key = keys.add("甲", secret = "sk-gw-first-secret")
        assertFailsWith<IllegalArgumentException> { keys.rotate(key.id, "短") }
        assertEquals("sk-gw-first-secret", assertNotNull(keys.byId(key.id)).secret, "校验失败不该改动")
    }

    // ---- 鉴定 ----

    @Test
    fun `authenticate 命中禁用与空串的处理`() {
        val active = keys.add("启用", secret = "sk-gw-active-secret")
        val disabled = keys.add("禁用", secret = "sk-gw-disabled-secret")
        keys.update(disabled.copy(enabled = false))

        assertEquals(active, keys.authenticate("sk-gw-active-secret"))
        assertNull(keys.authenticate("sk-gw-wrong-secret"))
        assertNull(keys.authenticate(""), "空串直接返回 null")
        assertNull(keys.authenticate("sk-gw-disabled-secret"), "禁用的 key 鉴定失败")
        assertNull(keys.authenticate("sk-gw-active-secre"), "前缀不算命中")
    }

    // ---- 模型白名单 ----

    @Test
    fun `allows 空白名单放行任意模型`() {
        val key = clientKey(models = emptyList())
        assertTrue(key.allows("trae/glm-5.3"))
        assertTrue(key.allows("group/team-a"))
        assertTrue(key.allows(""))
    }

    @Test
    fun `allows 精确匹配且大小写敏感`() {
        val key = clientKey(models = listOf("trae/glm-5.3"))
        assertTrue(key.allows("trae/glm-5.3"))
        assertFalse(key.allows("trae/glm-5.4"))
        assertFalse(key.allows("GLM-5.3"), "不做大小写折叠")
        assertFalse(key.allows("codebuddy/trae/glm-5.3"))
    }

    @Test
    fun `allows provider 通配只放行该供应商`() {
        val key = clientKey(models = listOf("trae/*"))
        assertTrue(key.allows("trae/glm-5.3"))
        assertTrue(key.allows("trae/gemini-3.8-flash"))
        assertFalse(key.allows("antigravity/gemini-3.8-flash"))
        assertFalse(key.allows("trae/"), "斜杠后必须有内容")
        assertFalse(key.allows("trae"), "通配要求前缀后的斜杠")
    }

    @Test
    fun `allows group 通配放行任意分组`() {
        val key = clientKey(models = listOf("group/*"))
        assertTrue(key.allows("group/team-a"))
        assertTrue(key.allows("group/团队二"))
        assertFalse(key.allows("trae/glm-5.3"))
    }

    @Test
    fun `allows 多条规则任一命中即放行`() {
        val key = clientKey(models = listOf("trae/glm-5.3", "group/*", "antigravity/*"))
        assertTrue(key.allows("trae/glm-5.3"))
        assertTrue(key.allows("group/team-a"))
        assertTrue(key.allows("antigravity/gemini-3.8-flash"))
        assertFalse(key.allows("codebuddy/glm-5.3"))
    }

    private fun clientKey(models: List<String>) = ClientKey(
        id = "ck_test",
        name = "测试",
        secret = "sk-gw-test-secret",
        allowedModels = models,
    )

    // ---- KeyLimit ----

    @Test
    fun `KeyLimit 默认值与序列化往返`() {
        val defaults = KeyLimit(LimitPeriod.DAY)
        assertEquals(1, defaults.n)
        assertEquals(0L, defaults.maxTokens, "0 表示该维度不限")
        assertEquals(0L, defaults.maxCostMicros)

        val limit = KeyLimit(LimitPeriod.HOURS, n = 5, maxTokens = 1000, maxCostMicros = 2000)
        val stored = keys.add("限额", limits = listOf(limit))
        assertEquals(listOf(limit), assertNotNull(keys.byId(stored.id)).limits)
    }

    // ---- 台账：累计 ----

    @Test
    fun `charge 累加累计用量并忽略负值`() {
        val key = keys.add("累计")
        ledger.charge(key.id, 10, 500)
        ledger.charge(key.id, 20, 700)
        assertEquals(KeyUsage(30, 1200), ledger.usageOf(key.id))

        ledger.charge(key.id, -5, -7)
        ledger.charge(key.id, 0, 0)
        assertEquals(KeyUsage(30, 1200), ledger.usageOf(key.id), "负值与零不该反向扣减")
        assertEquals(KeyUsage(), ledger.usageOf("ck_从未记账"))
    }

    // ---- 台账：判定 ----

    @Test
    fun `没有限额的 key 始终放行`() {
        val key = keys.add("无限制")
        ledger.charge(key.id, 10_000_000, 10_000_000)
        assertEquals(LimitVerdict.Allowed, ledger.check(key))
    }

    @Test
    fun `未超额放行 达到上限即超额`() {
        now = startOfDay(1_700_000_000_000L) + 3_600_000L
        val key = keys.add("日限额", limits = listOf(KeyLimit(LimitPeriod.DAY, maxTokens = 100)))
        ledger.charge(key.id, 99, 0)
        assertEquals(LimitVerdict.Allowed, ledger.check(key))

        ledger.charge(key.id, 1, 0)
        val verdict = exceeded(ledger.check(key))
        assertEquals(100L, verdict.usedTokens)
        assertEquals(100L, verdict.limit.maxTokens)
        assertEquals(LimitPeriod.DAY, verdict.limit.period)
        // 日历窗口的台账键布局：clientkey/window/<keyId>/<周期名>
        assertTrue(
            store.keys(ClientKeyUsageLedger.WINDOW_KEY_PREFIX + key.id + "/DAY").isNotEmpty(),
            "日窗口应以周期名作键后缀",
        )
    }

    @Test
    fun `两个维度都为 0 的限额视为不限额`() {
        now = startOfDay(1_700_000_000_000L) + 3_600_000L
        val key = keys.add("不限维度", limits = listOf(KeyLimit(LimitPeriod.DAY)))
        ledger.charge(key.id, 1_000_000, 1_000_000)
        assertEquals(LimitVerdict.Allowed, ledger.check(key), "maxTokens/maxCostMicros 为 0 即该维度不限")
    }

    @Test
    fun `费用超额单独触发`() {
        now = startOfDay(1_700_000_000_000L) + 3_600_000L
        val key = keys.add("费用限额", limits = listOf(KeyLimit(LimitPeriod.DAY, maxCostMicros = 1000)))
        ledger.charge(key.id, 0, 1000)
        val verdict = exceeded(ledger.check(key))
        assertEquals(1000L, verdict.usedCostMicros)
        assertEquals(0L, verdict.limit.maxTokens)
    }

    @Test
    fun `两个窗口都超额时返回重置更晚的那条`() {
        val base = midMonthMidWeekMidnight()
        now = base + 3_600_000L
        val dayLimit = KeyLimit(LimitPeriod.DAY, maxTokens = 10)
        val monthLimit = KeyLimit(LimitPeriod.MONTH, maxTokens = 10)
        val key = keys.add("双限额", limits = listOf(dayLimit, monthLimit))
        ledger.charge(key.id, 10, 0)

        val verdict = exceeded(ledger.check(key))
        assertEquals(LimitPeriod.MONTH, verdict.limit.period, "月窗口重置更晚，应报它")
        assertTrue(
            verdict.resetAtMillis > startOfDay(plusDays(base, 1)),
            "月窗口的重置时刻应晚于次日零点，实际: ${verdict.resetAtMillis}",
        )
    }

    @Test
    fun `日历窗口跨天后用量归零`() {
        val base = midMonthMidWeekMidnight()
        now = base + 3_600_000L
        val key = keys.add("跨天", limits = listOf(KeyLimit(LimitPeriod.DAY, maxTokens = 10)))
        ledger.charge(key.id, 10, 0)
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded)

        now = plusDays(base, 1) + 3_600_000L
        assertEquals(LimitVerdict.Allowed, ledger.check(key), "次日应清零")
        assertEquals(KeyUsage(10, 0), ledger.usageOf(key.id), "累计口径不受窗口影响")

        ledger.charge(key.id, 10, 0)
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded, "新窗口从 0 重新累计")
        assertEquals(KeyUsage(20, 0), ledger.usageOf(key.id))
    }

    @Test
    fun `HOURS 窗口首次使用时锚定起点并在下一周期顺延`() {
        val base = 1_000_000L
        now = base
        val key = keys.add("每小时", limits = listOf(KeyLimit(LimitPeriod.HOURS, n = 1, maxTokens = 50)))
        ledger.charge(key.id, 50, 0)
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded)

        val cycleKey = ClientKeyUsageLedger.CYCLE_KEY_PREFIX + key.id + "/HOURS:1"
        assertEquals(base.toString(), store.read(cycleKey), "首次使用时以当前时刻锚定起点")
        assertTrue(
            store.keys(ClientKeyUsageLedger.WINDOW_KEY_PREFIX + key.id + "/HOURS:1").isNotEmpty(),
            "滚动窗口的键要带 N，与每 5 小时互不相干",
        )

        // 同一周期内：起点不动，用量仍计入
        now = base + 3_599_000L
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded)
        assertEquals(base.toString(), store.read(cycleKey), "同周期内起点不变")

        // 进入下一周期：起点顺延整整一个 span，旧用量不再计入
        now = base + 3_600_000L
        assertEquals(LimitVerdict.Allowed, ledger.check(key))
        assertEquals((base + 3_600_000L).toString(), store.read(cycleKey))

        // 休眠跨多个周期时一步到位
        now = base + 3_600_000L * 4
        assertEquals(LimitVerdict.Allowed, ledger.check(key))
        assertEquals((base + 3_600_000L * 4).toString(), store.read(cycleKey))
    }

    @Test
    fun `DAYS 周期按 N 天滚动`() {
        val base = 1_000_000L
        now = base
        val key = keys.add("每三天", limits = listOf(KeyLimit(LimitPeriod.DAYS, n = 3, maxTokens = 10)))
        ledger.charge(key.id, 10, 0)
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded)

        now = base + 86_400_000L * 2
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded, "第 2 天仍在同一周期")
        now = base + 86_400_000L * 3
        assertEquals(LimitVerdict.Allowed, ledger.check(key))
    }

    @Test
    fun `同周期的重复限额原样保留并按更严的那条判定`() {
        now = startOfDay(1_700_000_000_000L) + 3_600_000L
        // 实现没有做限额合并：add/update 原样落盘，判定时逐条比对，效果取更严的一条。
        val loose = KeyLimit(LimitPeriod.DAY, maxTokens = 100)
        val strict = KeyLimit(LimitPeriod.DAY, maxTokens = 10)
        val key = keys.add("重复限额", limits = listOf(loose, strict))
        assertEquals(listOf(loose, strict), assertNotNull(keys.byId(key.id)).limits)

        ledger.charge(key.id, 10, 0)
        val verdict = exceeded(ledger.check(key))
        assertEquals(10L, verdict.limit.maxTokens, "重复周期取更严的一条拦截")
    }

    @Test
    fun `WEEK 窗口跨周后归零`() {
        // 2024-01-10 是周三（用 Calendar 按本地日历构造，与容器时区无关）
        val base = midMonthMidWeekMidnight()
        now = base + 3_600_000L
        val key = keys.add("周限额", limits = listOf(KeyLimit(LimitPeriod.WEEK, maxTokens = 10)))
        ledger.charge(key.id, 10, 0)
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded)
        assertTrue(
            store.keys(ClientKeyUsageLedger.WINDOW_KEY_PREFIX + key.id + "/WEEK").isNotEmpty(),
            "周窗口应以周期名作键后缀",
        )

        // 同一周内推进一天：仍是同一窗口
        now = plusDays(base, 1) + 3_600_000L
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded)

        // 跨到下一周（周三 → 下周三）：用量归零
        now = plusDays(base, 7) + 3_600_000L
        assertEquals(LimitVerdict.Allowed, ledger.check(key))
    }

    @Test
    fun `reset 清零累计与窗口`() {
        now = startOfDay(1_700_000_000_000L) + 3_600_000L
        val key = keys.add("重置", limits = listOf(KeyLimit(LimitPeriod.DAY, maxTokens = 10)))
        ledger.charge(key.id, 10, 500)
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded)

        ledger.reset(key.id)
        assertEquals(KeyUsage(), ledger.usageOf(key.id))
        assertTrue(store.keys(ClientKeyUsageLedger.WINDOW_KEY_PREFIX + key.id + "/").isEmpty())
        assertEquals(LimitVerdict.Allowed, ledger.check(key))
    }

    @Test
    fun `clear 同时清掉 window 与 cycle 键`() {
        now = 1_000_000L
        val key = keys.add("清理", limits = listOf(KeyLimit(LimitPeriod.HOURS, n = 1, maxTokens = 10)))
        ledger.charge(key.id, 10, 100)
        assertTrue(ledger.check(key) is LimitVerdict.Exceeded)
        assertFalse(store.keys(ClientKeyUsageLedger.WINDOW_KEY_PREFIX + key.id + "/").isEmpty())

        ledger.clear(key.id)
        assertTrue(store.keys(ClientKeyUsageLedger.WINDOW_KEY_PREFIX + key.id + "/").isEmpty())
        assertTrue(store.keys(ClientKeyUsageLedger.CYCLE_KEY_PREFIX + key.id + "/").isEmpty())
        assertEquals(KeyUsage(), ledger.usageOf(key.id))
        assertEquals(LimitVerdict.Allowed, ledger.check(key))
    }

    @Test
    fun `删除密钥后仍可记账但不再进窗口`() {
        now = startOfDay(1_700_000_000_000L) + 3_600_000L
        val key = keys.add("将删除", limits = listOf(KeyLimit(LimitPeriod.DAY, maxTokens = 10)))
        keys.remove(key.id)

        ledger.charge(key.id, 5, 0)
        assertEquals(KeyUsage(5, 0), ledger.usageOf(key.id), "累计照记")
        assertTrue(store.keys(ClientKeyUsageLedger.WINDOW_KEY_PREFIX + key.id + "/").isEmpty())
    }

    // ---- 存储容错 ----

    @Test
    fun `损坏 JSON 时 load 返回空表而不抛异常`() {
        store.write(ClientKeyStore.STORE_KEY, "not json")
        assertEquals(emptyList<ClientKey>(), keys.load())
        assertEquals(emptyList<ClientKey>(), keys.list())
        assertNull(keys.authenticate("sk-gw-anything"))
        assertFalse(keys.remove("ck_x"))

        store.write(ClientKeyStore.STORE_KEY, "")
        assertEquals(emptyList<ClientKey>(), keys.load())

        // 损坏后仍可正常写入
        val fresh = keys.add("重建")
        assertEquals(listOf(fresh), keys.list())
    }
}
