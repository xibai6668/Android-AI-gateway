package dev.aigw.core.pool

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.objOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** 账号对外暴露的状态（不含凭证）。 */
data class AccountStatus(
    val providerId: String,
    val uid: String,
    val nickname: String,
    /** 权益包/积分余额；[creditsKnown] 为 false 时该值无意义。 */
    val credits: Long,
    val creditsKnown: Boolean,
    /** provider 给出的额度补充说明（如「来源=团队，当日剩余=…」）。 */
    val detail: String,
    /** 上次失败原因（凭证失效或手动停用的说明）；空串表示一切正常。 */
    val reason: String,
    /** 凭证失效导致的硬禁用，需要重新登录。 */
    val disabled: Boolean,
    /** 用户手动软开关。 */
    val enabled: Boolean,
    /** 使用优先级：数值越大越先用；同级仍按余额。默认 0。 */
    val priority: Int = 0,
    /**
     * 是否被手动移到了默认（uid 字典序）位置之上。
     *
     * 不能直接用 `priority > 0` 判定：↑/↓ 是交换两个账号的优先级，退回原顺序时高值会落到
     * 另一个账号身上，「优先」标就会带着跑。这里比的是「实际位置 vs 默认位置」。
     */
    val raised: Boolean = false,
) {
    val usable: Boolean get() = !disabled && enabled
}

/** 账号池摘要（可整体或按 provider 统计）。 */
data class PoolSummary(
    val total: Int,
    val usable: Int,
    val disabled: Int,
    val disabledByUser: Int,
    val totalCredits: Long,
    /** 有多少个账号的额度是已知的。 */
    val creditsKnown: Int,
)

private class Entry(var account: ProviderAccount) {
    var credits: Long = 0
    var creditsKnown: Boolean = false
    var detail: String = ""
    var disabled: Boolean = false
    var enabled: Boolean = true
    var reason: String = ""
    var priority: Int = 0

    fun healthy(): Boolean = !disabled && enabled
}

/**
 * 手动上移/下移后的位置是否真的高于默认位置。
 *
 * 拿它算「优先」标，而不是直接看 priority：↑/↓ 是交换两个账号的优先级，
 * 退回原顺序时高值会跑到另一个账号身上，直接看 priority 会让标记带着跑。
 */
private fun raisedAboveDefault(providerId: String, uid: String, priority: Int, sorted: List<Entry>): Boolean {
    if (priority <= 0) return false
    val actual = sorted.indexOfFirst { it.account.uid == uid }
    val defaultIndex = sorted.map { it.account.uid }.sorted().indexOf(uid)
    return actual in 0 until defaultIndex
}

/**
 * 多 provider 账号池：单实例内部按 `providerId` 分片。
 *
 * 选号与额度都按 provider 独立，互不影响；新增供应商不需要改这个类。
 * 挑选策略：该 provider 内可用账号中取余额最高者；同一请求内换号重试用 [pick] 的 exclude 排除。
 *
 * 本地冷却（[coolDown]）只解决一个具体问题：**刚被上游报错过的账号不要立刻再被选中**。
 * 它不影响禁用/启用语义，到期自动恢复，也不落盘（重启即清零，与熔断/冷却表一致）。
 */
class AccountPool(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<String, Entry>()

    /** 按 provider 分组的条目索引：选号只需扫该供应商的账号，不必遍历整个池。 */
    private val byProvider = HashMap<String, MutableList<Entry>>()

    /** 每个账号一把落盘串行锁（键 = providerId/uid）：同一账号的凭证/状态落盘与删除在这里互斥。
     *
     * 内存状态在 [lock] 写锁内更新后，落盘被移到锁外执行 `store.write`：Android 侧一次 write
     * 就是整文件的加密 commit，持写锁落盘会把整个账号池的读（含选号 [pick]）全堵住，批量并发时
     * 线程越多等锁越久，拿不到线性收益。
     *
     * 锁外写会引入「同一账号多次落盘相互乱序」的风险（旧值覆盖新值）。这里用「同账号串行 + 落盘
     * 前重取最新内存状态」消除它：同账号写盘不会并行交错；每次写盘重新读当前内存里的状态/凭证，
     * 而不是用调用时捕获的旧快照，故最终写出去的一定是最新值（详见 [persistState]）。
     */
    private val persistLocks = ConcurrentHashMap<String, Any>()

    /** 本地冷却表：`providerId/uid` → 冷却截止毫秒（仅内存）。 */
    private val cooldowns = ConcurrentHashMap<String, Long>()

    init {
        load()
    }

    private fun accountIoLock(providerId: String, uid: String): Any =
        persistLocks.computeIfAbsent(keyOf(providerId, uid)) { Any() }

    // ---------------------------------------------------------------- 账号

    fun upsert(account: ProviderAccount) {
        // 锁内只改内存，落盘移到锁外（见 [persistLocks] 的说明）。
        lock.write {
            val key = keyOf(account.providerId, account.uid)
            val existing = entries[key]
            if (existing != null) {
                existing.account = account
                // 重新登录/刷新凭证意味着用户提供了新凭证：清除硬禁用状态。
                // 否则会出现「重新登录后依然 503」——旧凭证的失效状态沾在新凭证上。
                existing.disabled = false
                existing.reason = ""
                // 新凭证也要清掉旧凭证留下的本地冷却，否则刚换好的账号会被晾一会儿
                cooldowns.remove(keyOf(account.providerId, account.uid))
            } else {
                val entry = Entry(account)
                entries[key] = entry
                byProvider.getOrPut(account.providerId) { ArrayList() }.add(entry)
            }
        }
        persistAccount(account)
        persistState(account.providerId, account.uid)
    }

    fun remove(providerId: String, uid: String): Boolean {
        val removed = lock.write {
            val entry = entries.remove(keyOf(providerId, uid)) ?: return@write false
            val group = byProvider[providerId]
            group?.remove(entry)
            if (group != null && group.isEmpty()) byProvider.remove(providerId)
            true
        }
        if (!removed) return false
        cooldowns.remove(keyOf(providerId, uid))
        // 删除同样放到锁外，并与该账号的落盘串行：[persistState] 落盘前会重读内存，若删除先完成
        // 它会读到「条目已不在」而直接跳过，不会把刚删掉的键又写回来。
        synchronized(accountIoLock(providerId, uid)) {
            store.delete(accountKey(providerId, uid))
            store.delete(stateKey(providerId, uid))
            // 同 uid 重新登录时若旧格式文件还留着该账号状态，会与新建的默认状态冲突，故一并摘除。
            purgeLegacyState(providerId, uid)
        }
        return true
    }

    fun account(providerId: String, uid: String): ProviderAccount? =
        lock.read { entries[keyOf(providerId, uid)]?.account }

    /** [providerId] 为空时返回全部 provider 的账号。 */
    fun accounts(providerId: String? = null): List<ProviderAccount> = lock.read {
        entries.values
            .filter { providerId == null || it.account.providerId == providerId }
            .map { it.account }
    }

    fun statuses(providerId: String? = null): List<AccountStatus> = lock.read {
        val matched = entries.entries
            .filter { providerId == null || it.value.account.providerId == providerId }
        // 每个 provider 内部的默认顺序（uid 字典序）与当前顺序，用来判断哪些号真的被提上去了
        val byProvider = matched.groupBy { it.value.account.providerId }
        matched
            .sortedWith(
                compareByDescending<MutableMap.MutableEntry<String, Entry>> { it.value.priority }
                    .thenBy { it.key },
            )
            .map { entry ->
                val provider = entry.value.account.providerId
                val group = byProvider[provider].orEmpty()
                val status = statusOf(entry.value)
                status.copy(
                    raised = raisedAboveDefault(
                        providerId = provider,
                        uid = entry.value.account.uid,
                        priority = entry.value.priority,
                        sorted = group.sortedWith(
                            compareByDescending<MutableMap.MutableEntry<String, Entry>> { it.value.priority }
                                .thenBy { it.key },
                        ).map { it.value },
                    ),
                )
            }
    }

    fun status(providerId: String, uid: String): AccountStatus? =
        lock.read { entries[keyOf(providerId, uid)]?.let { statusOf(it) } }

    fun size(providerId: String? = null): Int = lock.read {
        if (providerId == null) entries.size
        else byProvider[providerId]?.size ?: 0
    }

    // ---------------------------------------------------------------- 状态

    fun setEnabled(providerId: String, uid: String, enabled: Boolean, reason: String = "") {
        val found = lock.write {
            val entry = entries[keyOf(providerId, uid)] ?: return@write false
            entry.enabled = enabled
            if (!enabled) {
                if (reason.isNotEmpty()) entry.reason = reason
            } else if (entry.reason.isNotEmpty() && !entry.disabled) {
                entry.reason = ""
            }
            true
        }
        if (found) persistState(providerId, uid)
    }

    /** 凭证失效：硬禁用，只能重新登录恢复。 */
    fun disable(providerId: String, uid: String, reason: String) {
        val found = lock.write {
            val entry = entries[keyOf(providerId, uid)] ?: return@write false
            entry.disabled = true
            entry.reason = reason
            true
        }
        if (found) persistState(providerId, uid)
    }

    /** 更新额度。 */
    fun updateCredits(
        providerId: String,
        uid: String,
        credits: Long,
        known: Boolean = true,
        detail: String = "",
    ) {
        val found = lock.write {
            val entry = entries[keyOf(providerId, uid)] ?: return@write false
            entry.credits = credits
            entry.creditsKnown = known
            entry.detail = detail
            true
        }
        if (found) persistState(providerId, uid)
    }

    /** 刷新凭证后落盘（refreshToken 会轮换，必须立刻写回）。 */
    fun saveAccount(account: ProviderAccount) {
        lock.write {
            entries[keyOf(account.providerId, account.uid)]?.account = account
        }
        persistAccount(account)
    }

    /**
     * 迁移账号标识：把 [oldUid] 的条目换成 [newAccount]（uid 变了，凭证与状态跟着走）。
     *
     * 用在 uid 口径变更后的自愈（如小浣熊曾把 JWT 的 iss 当 uid 导致多账号共用一个 key）。
     * 先登记新 key 再删旧 key：中途失败也不会把账号弄丢，最坏是两条并存，用户可手动删。
     *
     * @return true 表示真的搬了 key。
     */
    fun renameAccount(providerId: String, oldUid: String, newAccount: ProviderAccount): Boolean {
        if (oldUid == newAccount.uid) {
            upsert(newAccount)
            return false
        }
        val before = status(providerId, oldUid)
        upsert(newAccount)
        if (before != null) {
            if (!before.enabled) setEnabled(providerId, newAccount.uid, false, before.reason)
            if (before.creditsKnown) updateCredits(providerId, newAccount.uid, before.credits, true, before.detail)
        }
        remove(providerId, oldUid)
        return true
    }

    // ---------------------------------------------------------------- 选号

    /** 取该 provider 可用账号中优先级最高者，同级取余额最高者；[exclude] 用于同一请求内的换号重试。 */
    fun pick(providerId: String, exclude: Set<String> = emptySet()): ProviderAccount? = lock.read {
        val group = byProvider[providerId] ?: return@read null
        var best: Entry? = null
        for (entry in group) {
            if (entry.account.uid in exclude) continue
            if (!entry.healthy()) continue
            if (isCooling(providerId, entry.account.uid)) continue
            // 先比优先级（用户手动排序，最高优先），同级再比余额：
            // 用户把某个号拖到最上面，就是希望它先被用，不该被余额更高的号盖掉。
            val better = best == null || entry.priority > best.priority ||
                (entry.priority == best.priority && entry.credits > best.credits)
            if (better) best = entry
        }
        best?.account
    }

    /**
     * 把某个账号临时冷却一段时间（[durationMs] <= 0 时忽略）。
     *
     * 用在上游刚给过一次失败之后：失败本身已由熔断/换号处理，这里只防「下一个请求又选中它」
     * 导致的连续失败。与禁用不同：不改变 enabled/disabled，界面上仍显示为可用。
     */
    fun coolDown(providerId: String, uid: String, durationMs: Long) {
        if (durationMs <= 0) return
        cooldowns["$providerId/$uid"] = nowMillis() + durationMs
    }

    /** 该账号是否在本地冷却中；到期项顺手清掉。 */
    fun isCooling(providerId: String, uid: String): Boolean {
        val key = "$providerId/$uid"
        val deadline = cooldowns[key] ?: return false
        if (nowMillis() >= deadline) {
            cooldowns.remove(key, deadline)
            return false
        }
        return true
    }

    /** 清掉该账号的本地冷却（成功一次或换了新凭证都该解除）。 */
    fun clearCooldown(providerId: String, uid: String) {
        cooldowns.remove("$providerId/$uid")
    }

    /** 当前仍在冷却中的账号：`providerId/uid` → 剩余毫秒。 */
    fun cooldownSnapshot(): Map<String, Long> {
        val now = nowMillis()
        val out = LinkedHashMap<String, Long>()
        for ((key, deadline) in cooldowns) {
            val remaining = deadline - now
            if (remaining > 0) out[key] = remaining else cooldowns.remove(key, deadline)
        }
        return out
    }

    /** 供界面展示的摘要；[providerId] 为空时统计全部。 */
    fun summary(providerId: String? = null): PoolSummary = lock.read {
        // 指定 provider 时直接复用 [byProvider] 分片索引（O(N) 而非遍历整池再过滤的 O(P×N)），
        // 该索引与 entries 同步维护，两者内容一致，结果不变。
        val source: Collection<Entry> = if (providerId == null) entries.values else byProvider[providerId].orEmpty()
        var total = 0
        var usable = 0
        var disabled = 0
        var disabledByUser = 0
        var totalCredits = 0L
        var creditsKnown = 0
        for (entry in source) {
            total++
            when {
                entry.disabled -> disabled++
                !entry.enabled -> disabledByUser++
                else -> usable++
            }
            if (entry.creditsKnown) {
                totalCredits += entry.credits
                creditsKnown++
            }
        }
        PoolSummary(
            total = total,
            usable = usable,
            disabled = disabled,
            disabledByUser = disabledByUser,
            totalCredits = totalCredits,
            creditsKnown = creditsKnown,
        )
    }

    // ---------------------------------------------------------------- 排序

    /**
     * 调整账号优先级：上移/下移一位（与相邻账号交换；同级时抬一档）。
     *
     * 两个账号的优先级都要落盘，且按账号逐个写（[persistState] 是 per-uid 的）：
     * 锁内只改内存，落盘在锁外，避免持写锁做整文件加密提交。
     */
    fun movePriority(providerId: String, uid: String, up: Boolean): Boolean {
        val changed = lock.write {
            val group = byProvider[providerId] ?: return@write emptyList<String>()
            val sorted = group.sortedWith(compareByDescending<Entry> { it.priority }.thenBy { it.account.uid })
            val idx = sorted.indexOfFirst { it.account.uid == uid }
            if (idx < 0) return@write emptyList<String>()
            val target = idx + (if (up) -1 else 1)
            if (target !in sorted.indices) return@write emptyList<String>()
            val a = sorted[idx]
            val b = sorted[target]
            if (a.priority == b.priority) {
                a.priority = b.priority + (if (up) 1 else -1)
            } else {
                val tmp = a.priority
                a.priority = b.priority
                b.priority = tmp
            }
            listOf(a.account.uid, b.account.uid)
        }
        if (changed.isEmpty()) return false
        changed.forEach { persistState(providerId, it) }
        return true
    }

    /** 直接设置账号优先级（数值越大越先用）。 */
    fun setPriority(providerId: String, uid: String, priority: Int): Boolean {
        val found = lock.write {
            val entry = entries[keyOf(providerId, uid)] ?: return@write false
            entry.priority = priority
            true
        }
        if (found) persistState(providerId, uid)
        return found
    }

    // ---------------------------------------------------------------- 持久化

    private fun statusOf(entry: Entry): AccountStatus = AccountStatus(
        providerId = entry.account.providerId,
        uid = entry.account.uid,
        nickname = entry.account.nickname,
        credits = entry.credits,
        creditsKnown = entry.creditsKnown,
        detail = entry.detail,
        reason = entry.reason,
        disabled = entry.disabled,
        enabled = entry.enabled,
        priority = entry.priority,
    )

    private fun keyOf(providerId: String, uid: String) = "$providerId/$uid"

    private fun accountKey(providerId: String, uid: String) = "$ACCOUNT_PREFIX$providerId/$uid"

    private fun persistAccount(account: ProviderAccount) {
        synchronized(accountIoLock(account.providerId, account.uid)) {
            // 重取当前内存里的最新凭证，避免并发写回时旧凭证覆盖新凭证（refreshToken 轮换最怕这个）。
            val latest = lock.read { entries[keyOf(account.providerId, account.uid)]?.account } ?: account
            store.write(accountKey(account.providerId, account.uid), latest.toJson().toString())
        }
    }

    /**
     * 按账号落盘：一个账号一个状态键。
     * 只重写单个账号意味着「改一个账号」的写放大从整包变成一条记录——
     * Android 侧每次 write 都是一整次文件提交，这里必须精确到 uid。
     */
    private fun persistState(providerId: String, uid: String) {
        // 同账号落盘串行（[accountIoLock]），并在此刻重读内存状态而非用调用时的旧快照：
        // 于是「排在后面的落盘」读到的必定是当前最新状态，绝不会把更旧的值覆盖到新值上——
        // 因为最后一次落盘执行时，所有内存更新都已完成（每个更新都会紧随一次落盘）。
        synchronized(accountIoLock(providerId, uid)) {
            val json = lock.read { entries[keyOf(providerId, uid)]?.let { stateJson(it).toString() } } ?: return
            store.write(stateKey(providerId, uid), json)
        }
    }

    private fun stateJson(entry: Entry): JsonObject = JsonObject().apply {
        addProperty("credits", entry.credits)
        addProperty("creditsKnown", entry.creditsKnown)
        addProperty("detail", entry.detail)
        addProperty("disabled", entry.disabled)
        addProperty("enabled", entry.enabled)
        addProperty("reason", entry.reason)
        addProperty("priority", entry.priority)
    }

    private fun load() {
        for (key in store.keys(ACCOUNT_PREFIX)) {
            val raw = store.read(key) ?: continue
            val account = runCatching { ProviderAccount.parse(raw) }.getOrNull() ?: continue
            val entry = Entry(account)
            readState(account.providerId, account.uid)?.let { state ->
                entry.credits = state.get("credits")?.asLong ?: 0L
                entry.creditsKnown = state.get("creditsKnown")?.asBoolean ?: false
                entry.detail = state.get("detail")?.asString.orEmpty()
                entry.disabled = state.get("disabled")?.asBoolean ?: false
                entry.enabled = state.get("enabled")?.asBoolean ?: true
                entry.reason = state.get("reason")?.asString.orEmpty()
                entry.priority = state.get("priority")?.asInt ?: 0
            }
            entries[keyOf(account.providerId, account.uid)] = entry
        }
        rebuildIndex()
    }

    private fun rebuildIndex() {
        byProvider.clear()
        for (entry in entries.values) {
            byProvider.getOrPut(entry.account.providerId) { ArrayList() }.add(entry)
        }
    }

    /**
     * 读单个账号的状态；新键缺失时惰性迁移旧格式（provider 级整包文件）。
     *
     * 迁移策略：命中旧文件里的该 uid 后，先把状态写进新键、再把该 uid 从旧文件摘除，
     * 旧文件清空即删除。选「摘 uid」而不是「迁移完全部 uid 再删旧文件」，是因为：
     * 1) 迁移进度就存在旧文件自身（哪些 uid 还在就是还没迁），无需额外记账，天然可重复执行；
     * 2) 全程不存在「旧键仍持有数据而新键已写」的重叠窗口，新键一旦写入旧副本即被移除，
     *    读路径永远以新键为准，不会出现旧副本把状态改回去；
     * 3) 某 provider 只有部分账号有凭证时（其余 uid 无凭证、load 不会遍历到），旧文件里剩下的 uid
     *    会保留到其账号下次出现，避免一次性丢状态。
     */
    private fun readState(providerId: String, uid: String): JsonObject? {
        val raw = store.read(stateKey(providerId, uid))
        if (raw != null) {
            return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        }
        return migrateLegacyState(providerId, uid)
    }

    private fun migrateLegacyState(providerId: String, uid: String): JsonObject? {
        val legacy = readLegacyAccounts(providerId) ?: return null
        val saved = legacy.get(uid)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        // 先落新键再从旧文件摘除：两步之间即使崩溃，也只是旧文件里留了一份冗余副本，
        // 下次读取因新键已存在而直接命中新键，不依赖这次迁移是否完成。
        store.write(stateKey(providerId, uid), saved.toString())
        dropLegacyUid(providerId, legacy, uid)
        return saved
    }

    /** 删除账号时摘除其遗留的旧格式状态，避免同 uid 重新登录时旧状态被迁移回来。 */
    private fun purgeLegacyState(providerId: String, uid: String) {
        val legacy = readLegacyAccounts(providerId) ?: return
        if (legacy.get(uid) == null) return
        dropLegacyUid(providerId, legacy, uid)
    }

    private fun readLegacyAccounts(providerId: String): JsonObject? {
        val raw = store.read(legacyStateKey(providerId)) ?: return null
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.objOrNull("accounts")
        }.getOrNull()
    }

    private fun dropLegacyUid(providerId: String, legacy: JsonObject, uid: String) {
        legacy.remove(uid)
        if (legacy.size() == 0) {
            store.delete(legacyStateKey(providerId))
        } else {
            store.write(legacyStateKey(providerId), JsonObject().apply { add("accounts", legacy) }.toString())
        }
    }

    companion object {
        const val ACCOUNT_PREFIX = "account/"
        const val STATE_PREFIX = "pool/state/"

        /** 某个账号的状态文件键（测试与迁移脚本用）。 */
        fun stateKey(providerId: String, uid: String) = "$STATE_PREFIX$providerId/$uid.json"

        /** 旧版 provider 级状态文件键：整包格式 `{"accounts":{uid:{...}}}`，仅用于惰性迁移。 */
        fun legacyStateKey(providerId: String) = "$STATE_PREFIX$providerId.json"
    }
}
