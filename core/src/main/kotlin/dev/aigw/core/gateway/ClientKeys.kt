package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.randomHex
import dev.aigw.core.util.startOfDay
import dev.aigw.core.util.stringOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar

/**
 * 限额窗口。DAY/WEEK/MONTH 是本地日历窗口；HOURS/DAYS 是「自设定起每 N 小时/天」的滚动窗口。
 *
 * 两者语义不同：日历窗口是「今天/本周/本月」，跨零点自动清零；滚动窗口从第一次用量的时刻起算，
 * 跑满 N 个时间单位才顺延，适合「每 5 小时」「每 30 天」这类额度。
 */
enum class LimitPeriod { DAY, WEEK, MONTH, HOURS, DAYS }

/** 一条限额：tokens 与费用两个维度，任一超额即拒绝；都为 0 表示该维度不限。 */
data class KeyLimit(
    val period: LimitPeriod,
    /** 仅 HOURS/DAYS 用：窗口长度 N（>=1）。其它周期忽略。 */
    val n: Int = 1,
    val maxTokens: Long = 0L,
    /** 上限金额（微美元，1e-6 USD），0 表示不限。用整数避免浮点累积误差。 */
    val maxCostMicros: Long = 0L,
)

/** 客户端密钥（与上游供应商凭证完全分离）。 */
data class ClientKey(
    val id: String,
    val name: String,
    val secret: String,
    val enabled: Boolean = true,
    /**
     * 模型白名单：`provider/model`、`provider/` 前缀通配（末尾带星号）、`group/<id>`；空表示不限制。
     */
    val allowedModels: List<String> = emptyList(),
    val limits: List<KeyLimit> = emptyList(),
    val createdAtMillis: Long = 0L,
) {
    /**
     * 是否允许使用某个「网关对外模型 id」（形如 `provider/model` 或 `group/<id>`）。
     *
     * 规则按大小写敏感比较：模型 id 本就区分大小写（`GLM` 与 `glm` 是两条上游 id），
     * 这里做大小写折叠会把白名单放得比用户预期宽。
     */
    fun allows(modelFullId: String): Boolean {
        if (allowedModels.isEmpty()) return true
        for (rule in allowedModels) {
            if (rule == modelFullId) return true
            // `provider/*` 与 `group/*` 走同一条通配规则：去掉末尾 `*` 后缀，
            // 剩下 `provider/` 作为前缀，且要求斜杠后至少还有一个字符（`provider/` 本身不算命中）。
            if (rule.endsWith("/*")) {
                val prefix = rule.dropLast(1)
                if (modelFullId.length > prefix.length && modelFullId.startsWith(prefix)) return true
            }
        }
        return false
    }

    /** 只含末尾 4 位的展示形式。日志与界面都不该出现完整密钥。 */
    val maskedSecret: String
        get() = if (secret.length <= 4) "****" else "****" + secret.takeLast(4)
}

/**
 * 客户端密钥存储：整份列表放在单个键里。
 *
 * 密钥条数是个位数级别，单键整体读写比逐条落盘简单得多，也不需要处理条目的增删键；
 * 若将来条数上去，再按 id 拆键（同一个类里换实现即可）。
 */
class ClientKeyStore(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    fun load(): List<ClientKey> = parseKeys(store.read(STORE_KEY))

    fun list(): List<ClientKey> = load()

    /** 新建密钥并返回（secret 由调用方给或内部生成 sk-gw- 前缀的随机串）。 */
    fun add(
        name: String,
        secret: String? = null,
        allowedModels: List<String> = emptyList(),
        limits: List<KeyLimit> = emptyList(),
    ): ClientKey {
        val existing = load()
        val finalSecret = secret ?: generateSecret()
        validateSecret(finalSecret, excludeId = null, existing = existing)
        val key = ClientKey(
            id = newId(existing),
            name = name,
            secret = finalSecret,
            enabled = true,
            allowedModels = allowedModels,
            limits = limits,
            createdAtMillis = nowMillis(),
        )
        save(existing + key)
        return key
    }

    /** 按 id 整条替换；id 不存在时追加（让调用方可以「保存即生效」而不必先判断存在性）。 */
    fun update(updated: ClientKey) {
        val keys = load().toMutableList()
        val index = keys.indexOfFirst { it.id == updated.id }
        if (index >= 0) keys[index] = updated else keys.add(updated)
        save(keys)
    }

    fun remove(id: String): Boolean {
        val keys = load()
        val kept = keys.filterNot { it.id == id }
        if (kept.size == keys.size) return false
        save(kept)
        return true
    }

    /** 轮换密钥：身份、名称、白名单、限额、用量历史全部保留，只换 secret。返回新 secret。 */
    fun rotate(id: String, newSecret: String? = null): String? {
        val keys = load()
        val index = keys.indexOfFirst { it.id == id }
        if (index < 0) return null
        val secret = newSecret ?: generateSecret()
        validateSecret(secret, excludeId = id, existing = keys)
        val updated = keys.toMutableList()
        // 只换 secret：用量台账按 keyId 记录，轮换后历史继续有效，不需要清账。
        updated[index] = updated[index].copy(secret = secret)
        save(updated)
        return secret
    }

    /**
     * 按明文 token 鉴定；禁用或不存在返回 null。
     *
     * 遍历全部 key 而不是命中即返回：提前 return 会让耗时随命中的 key 在列表中的位置变化，
     * 攻击者可据此推断「某个前缀是否命中」。这里与 [MessageDigest.isEqual] 一起，把比较
     * 变成「每条都比一次、长度无关、位置无关」。
     */
    fun authenticate(token: String): ClientKey? {
        if (token.isEmpty()) return null
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
        val keys = load()
        var matched: ClientKey? = null
        for (key in keys) {
            val equal = MessageDigest.isEqual(tokenBytes, key.secret.toByteArray(StandardCharsets.UTF_8))
            // 只做一次赋值、不 break：即便命中也要把余下条目比较完
            if (equal && matched == null) matched = key
        }
        return matched?.takeIf { it.enabled }
    }

    fun byId(id: String): ClientKey? = load().firstOrNull { it.id == id }

    /** 生成 `sk-gw-` + 32 位 hex 的密钥。 */
    fun generateSecret(): String = SECRET_PREFIX + randomHex(16)

    /**
     * 校验调用方给的 secret。
     *
     * 只允许可打印 ASCII（0x21~0x7E）：密钥会进 HTTP 头、URL query 和日志，
     * 空白、控制字符、非 ASCII 在任一处都会被转义或截断，导致「配了却用不了」。
     */
    private fun validateSecret(secret: String, excludeId: String?, existing: List<ClientKey>) {
        require(secret.length in SECRET_MIN_LENGTH..SECRET_MAX_LENGTH) {
            "密钥长度必须在 $SECRET_MIN_LENGTH~$SECRET_MAX_LENGTH 之间"
        }
        require(secret.all { it.code in 0x21..0x7E }) { "密钥只能由可打印 ASCII 组成且不能含空格" }
        // 重复检测是本地配置一致性检查，不是猜密钥，用 == 足够；
        // 时序敏感的路径在 authenticate()。
        require(existing.none { it.id != excludeId && it.secret == secret }) { "密钥与已有密钥重复" }
    }

    private fun newId(existing: List<ClientKey>): String {
        val ids = existing.mapTo(HashSet()) { it.id }
        while (true) {
            val id = "ck_" + randomHex(6)
            if (ids.add(id)) return id
        }
    }

    private fun save(keys: List<ClientKey>) {
        val root = JsonObject()
        root.add("keys", JsonArray().apply { keys.forEach { add(it.toJson()) } })
        store.write(STORE_KEY, root.toString())
    }

    /** 宽容解析：整份损坏退化为空表；单条坏条目（无 id / 无 secret）跳过，不影响其余密钥可用。 */
    private fun parseKeys(raw: String?): List<ClientKey> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.arrayOrNull("keys")
            else -> null
        } ?: return emptyList()
        val result = LinkedHashMap<String, ClientKey>()
        array.forEach { element ->
            val obj = element.asObjectOrNull() ?: return@forEach
            val id = obj.stringOrNull("id").orEmpty()
            val secret = obj.stringOrNull("secret").orEmpty()
            if (id.isEmpty() || secret.isEmpty()) return@forEach
            val allowedModels = ArrayList<String>()
            obj.arrayOrNull("allowedModels")?.forEach { item ->
                val model = item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString.orEmpty()
                if (model.isNotEmpty()) allowedModels.add(model)
            }
            val limits = ArrayList<KeyLimit>()
            obj.arrayOrNull("limits")?.forEach { item ->
                item.asObjectOrNull()?.toLimitOrNull()?.let { limits.add(it) }
            }
            val key = ClientKey(
                id = id,
                name = obj.stringOrNull("name").orEmpty(),
                secret = secret,
                enabled = obj.boolOrNull("enabled") ?: true,
                allowedModels = allowedModels,
                limits = limits,
                createdAtMillis = obj.longOrNull("createdAt") ?: 0L,
            )
            // 同一 id 只保留第一条：损坏/手工编辑的 JSON 里出现重复 id 时，
            // 界面上一行、存储里两行会让「删除」只删掉其中一条。
            if (!result.containsKey(id)) result[id] = key
        }
        return result.values.toList()
    }

    companion object {
        const val STORE_KEY = "settings/client_keys.json"
        const val SECRET_PREFIX = "sk-gw-"

        /** 长度下限：太短的密钥可被暴力枚举；上限：避免把超长串写进 URL/头部。 */
        const val SECRET_MIN_LENGTH = 8
        const val SECRET_MAX_LENGTH = 256
    }
}

/** 单次用量记录（供限额判定）。 */
data class KeyUsage(val tokens: Long = 0L, val costMicros: Long = 0L)

/** 限额判定结果。 */
sealed interface LimitVerdict {
    data object Allowed : LimitVerdict

    /** 超额：给出重置时刻与命中的那条限额，供 429 错误体与 Retry-After 使用。 */
    data class Exceeded(
        val limit: KeyLimit,
        val resetAtMillis: Long,
        val usedTokens: Long,
        val usedCostMicros: Long,
    ) : LimitVerdict
}

/**
 * 密钥用量台账：**从累计计数读**，独立于调用记录开关，重启后仍准确。
 *
 * 不能拿「调用记录」现算：记录有容量淘汰与过期清理，算出来的永远是最近几条。
 *
 * 键布局：
 * - `clientkey/usage/<keyId>`：累计用量（展示与重置用）
 * - `clientkey/window/<keyId>/<periodKey>`：某窗口快照（含窗口起点，起点不符即视为 0）
 * - `clientkey/cycle/<keyId>/<periodKey>`：滚动窗口的当前周期起点
 */
class ClientKeyUsageLedger(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 只读地拿限额配置；台账不负责密钥的增删改，密钥列表仍由 [ClientKeyStore] 落盘。 */
    private val keyStore = ClientKeyStore(store, nowMillis)

    /** 记一笔用量：累计计数与每条限额所在窗口同步累加。 */
    fun charge(keyId: String, tokens: Long, costMicros: Long) {
        if (keyId.isEmpty()) return
        // 负数按 0：上游偶尔回报负的 token/费用（扣减语义），当成「减少用量」会打穿限额。
        val addTokens = tokens.coerceAtLeast(0)
        val addCost = costMicros.coerceAtLeast(0)
        if (addTokens == 0L && addCost == 0L) return

        val cumulative = readUsage(USAGE_KEY_PREFIX + keyId)
        writeUsage(
            USAGE_KEY_PREFIX + keyId,
            KeyUsage(cumulative.tokens + addTokens, cumulative.costMicros + addCost),
        )

        // 窗口累加需要知道这个密钥配了哪些限额；配置读不到就只记累计（密钥可能刚被删除）。
        val key = keyStore.byId(keyId) ?: return
        val now = nowMillis()
        for (limit in key.limits.distinctBy { periodKeyOf(it) }) {
            val window = windowOf(keyId, limit, now)
            chargeWindow(keyId, periodKeyOf(limit), window, addTokens, addCost)
        }
    }

    /** 累计用量（展示口径）；从未记过账时为全零。 */
    fun usageOf(keyId: String): KeyUsage = readUsage(USAGE_KEY_PREFIX + keyId)

    /**
     * 某条限额的当前窗口边界（毫秒）：供 `/v1/limit` 与界面展示「何时重置」。
     *
     * 返回 `start to end`：调用方只需展示，不参与判定（判定一律走 [check]）。
     */
    fun windowBounds(key: ClientKey, limit: KeyLimit): Pair<Long, Long> {
        val window = windowOf(key.id, limit, nowMillis())
        return window.start to window.end
    }

    /**
     * 判定该密钥此刻是否超额（所有限额都要有余量才放行）。
     *
     * 多个限额同时超额时选「重置最晚」的那条：客户端等最久才真正恢复，
     * 报它比报「1 分钟后重置」的那条更能解释为什么现在被拒。
     */
    fun check(key: ClientKey): LimitVerdict {
        if (key.limits.isEmpty()) return LimitVerdict.Allowed
        val now = nowMillis()
        var worst: LimitVerdict.Exceeded? = null
        for (limit in key.limits) {
            val window = windowOf(key.id, limit, now)
            val used = readWindow(key.id, periodKeyOf(limit), window.start)
            // 达到上限即视为没有余量：检查发生在扣费之前，剩 0 的额度不能再用一次。
            val overTokens = limit.maxTokens > 0 && used.tokens >= limit.maxTokens
            val overCost = limit.maxCostMicros > 0 && used.costMicros >= limit.maxCostMicros
            if (!overTokens && !overCost) continue
            val exceeded = LimitVerdict.Exceeded(
                limit = limit,
                resetAtMillis = window.end,
                usedTokens = used.tokens,
                usedCostMicros = used.costMicros,
            )
            if (worst == null || exceeded.resetAtMillis > worst.resetAtMillis) worst = exceeded
        }
        return worst ?: LimitVerdict.Allowed
    }

    /** 把某个密钥的用量清零（对应「重置用量」），从此刻重新计。 */
    fun reset(keyId: String) {
        // 滚动窗口的周期起点也一起清掉：用户点「重置用量」的语义是「从现在重新算」，
        // 若保留旧起点，重置后可能几秒就再跨进下一周期。
        deleteLedger(keyId)
    }

    /** 删除密钥时清理其台账。 */
    fun clear(keyId: String) {
        deleteLedger(keyId)
    }

    private fun deleteLedger(keyId: String) {
        store.delete(USAGE_KEY_PREFIX + keyId)
        for (key in store.keys(WINDOW_KEY_PREFIX + keyId + "/")) store.delete(key)
        for (key in store.keys(CYCLE_KEY_PREFIX + keyId + "/")) store.delete(key)
    }

    // ---- 窗口计算 ----

    /** 一个具体窗口的边界。 */
    private data class Window(val start: Long, val end: Long)

    /**
     * 窗口的周期键：同一条限额永远对应同一个键。
     *
     * HOURS/DAYS 要带 N——「每 1 小时」和「每 5 小时」是两条互不相干的窗口；
     * 日历窗口只带周期名，靠快照里的 start 区分是哪一天/哪一周。
     */
    private fun periodKeyOf(limit: KeyLimit): String = when (limit.period) {
        LimitPeriod.HOURS, LimitPeriod.DAYS -> "${limit.period.name}:${limit.n.coerceAtLeast(1)}"
        else -> limit.period.name
    }

    private fun windowOf(keyId: String, limit: KeyLimit, now: Long): Window = when (limit.period) {
        LimitPeriod.DAY -> {
            val start = startOfDay(now)
            Window(start, plusDays(start, 1))
        }
        LimitPeriod.WEEK -> {
            val start = startOfWeek(now)
            Window(start, plusDays(start, 7))
        }
        LimitPeriod.MONTH -> {
            val start = startOfMonth(now)
            Window(start, plusMonths(start, 1))
        }
        LimitPeriod.HOURS -> rollingWindow(keyId, periodKeyOf(limit), now, limit.n.coerceAtLeast(1) * 3_600_000L)
        LimitPeriod.DAYS -> rollingWindow(keyId, periodKeyOf(limit), now, limit.n.coerceAtLeast(1) * 86_400_000L)
    }

    /**
     * 滚动窗口：起点存在 `clientkey/cycle/...` 里，首次需要时以当前时刻为起点。
     *
     * 顺延按整数个周期一次推进（`now - start` 整除 span），休眠/关机跨过多个周期也能一步到位；
     * 逐周期 while 循环在长时间离线后会白跑成千上万次。
     */
    private fun rollingWindow(keyId: String, periodKey: String, now: Long, spanMillis: Long): Window {
        val cycleKey = CYCLE_KEY_PREFIX + keyId + "/" + periodKey
        val stored = store.read(cycleKey)?.trim()?.toLongOrNull()
        var start = stored ?: now
        if (stored == null) store.write(cycleKey, start.toString())
        when {
            now >= start + spanMillis -> {
                start += ((now - start) / spanMillis) * spanMillis
                store.write(cycleKey, start.toString())
            }
            // 系统时间回拨：旧起点在未来会让窗口「永不重置」，以当前时刻重开。
            now < start -> {
                start = now
                store.write(cycleKey, start.toString())
            }
        }
        return Window(start, start + spanMillis)
    }

    private fun startOfWeek(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        clearTimeOfDay()
        // Calendar 里 SUNDAY=1、MONDAY=2…；换算成「距本周一几天」再回退，
        // 周日属于上一周（中国习惯周一为一周之始）。
        add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
    }.timeInMillis

    private fun startOfMonth(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        clearTimeOfDay()
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    /** 用 Calendar 做日/月加减而不是固定毫秒数：跨夏令时的一天不是 86400000 毫秒。 */
    private fun plusDays(millis: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    private fun plusMonths(millis: Long, months: Int): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        add(Calendar.MONTH, months)
    }.timeInMillis

    private fun Calendar.clearTimeOfDay() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // ---- 台账读写 ----

    private fun chargeWindow(
        keyId: String,
        periodKey: String,
        window: Window,
        tokens: Long,
        costMicros: Long,
    ) {
        val storageKey = WINDOW_KEY_PREFIX + keyId + "/" + periodKey
        val raw = store.read(storageKey)
        val obj = raw?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
            ?.takeIf { it.isJsonObject }?.asJsonObject
        val sameWindow = obj != null && (obj.longOrNull("start") ?: Long.MIN_VALUE) == window.start
        val baseTokens = if (sameWindow) obj!!.longOrNull("tokens") ?: 0L else 0L
        val baseCost = if (sameWindow) obj!!.longOrNull("costMicros") ?: 0L else 0L
        writeWindow(storageKey, window.start, baseTokens + tokens, baseCost + costMicros)
    }

    private fun readWindow(keyId: String, periodKey: String, expectedStart: Long): KeyUsage {
        val raw = store.read(WINDOW_KEY_PREFIX + keyId + "/" + periodKey) ?: return KeyUsage()
        val obj = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return KeyUsage()
        // 起点不符说明已经跨窗口（或窗口被重置过），旧快照作废——不迁移旧值，新窗口从 0 算。
        val start = obj.longOrNull("start") ?: return KeyUsage()
        if (start != expectedStart) return KeyUsage()
        return KeyUsage(
            tokens = (obj.longOrNull("tokens") ?: 0L).coerceAtLeast(0),
            costMicros = (obj.longOrNull("costMicros") ?: 0L).coerceAtLeast(0),
        )
    }

    private fun readUsage(key: String): KeyUsage {
        val raw = store.read(key) ?: return KeyUsage()
        val obj = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return KeyUsage()
        return KeyUsage(
            tokens = (obj.longOrNull("tokens") ?: 0L).coerceAtLeast(0),
            costMicros = (obj.longOrNull("costMicros") ?: 0L).coerceAtLeast(0),
        )
    }

    private fun writeUsage(key: String, usage: KeyUsage) {
        store.write(
            key,
            JsonObject().apply {
                addProperty("tokens", usage.tokens)
                addProperty("costMicros", usage.costMicros)
            }.toString(),
        )
    }

    private fun writeWindow(key: String, start: Long, tokens: Long, costMicros: Long) {
        store.write(
            key,
            JsonObject().apply {
                addProperty("start", start)
                addProperty("tokens", tokens)
                addProperty("costMicros", costMicros)
            }.toString(),
        )
    }

    companion object {
        const val USAGE_KEY_PREFIX = "clientkey/usage/"
        const val WINDOW_KEY_PREFIX = "clientkey/window/"
        const val CYCLE_KEY_PREFIX = "clientkey/cycle/"
    }
}

// ---- 序列化 ----

private fun ClientKey.toJson(): JsonObject = JsonObject().apply {
    addProperty("id", id)
    addProperty("name", name)
    addProperty("secret", secret)
    addProperty("enabled", enabled)
    add("allowedModels", JsonArray().apply { allowedModels.forEach { add(it) } })
    add("limits", JsonArray().apply { limits.forEach { add(it.toJson()) } })
    addProperty("createdAt", createdAtMillis)
}

private fun KeyLimit.toJson(): JsonObject = JsonObject().apply {
    addProperty("period", period.name)
    addProperty("n", n)
    addProperty("maxTokens", maxTokens)
    addProperty("maxCostMicros", maxCostMicros)
}

/** 周期名不认识（老版本/手改）时整条限额跳过：宁可放宽，也不要拿一个错窗口去误拦。 */
private fun JsonObject.toLimitOrNull(): KeyLimit? {
    val period = stringOrNull("period")
        ?.let { name -> LimitPeriod.entries.firstOrNull { it.name == name } }
        ?: return null
    return KeyLimit(
        period = period,
        n = (longOrNull("n") ?: 1L).toInt().coerceAtLeast(1),
        maxTokens = (longOrNull("maxTokens") ?: 0L).coerceAtLeast(0),
        maxCostMicros = (longOrNull("maxCostMicros") ?: 0L).coerceAtLeast(0),
    )
}
