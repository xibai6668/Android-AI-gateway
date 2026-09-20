package dev.aigw.core.pool

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.objOrNull
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** 冷却类型，决定冷却时长由调用方传多少。 */
enum class CoolKind {
    /** 额度/积分不足：长冷却。 */
    QUOTA,

    /** 限流：短冷却，不累计错误次数。 */
    SOFT,

    /** 连续错误：中冷却。 */
    ERROR,
}

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
    val cooling: Boolean,
    val untilMillis: Long,
    val reason: String,
    /** 凭证失效导致的硬禁用，需要重新登录。 */
    val disabled: Boolean,
    /** 用户手动软开关。 */
    val enabled: Boolean,
    val errorCount: Int,
    /** 当前冷却的类型（额度/限流/连续错误）；未冷却时为 null。 */
    val coolKind: CoolKind?,
) {
    val usable: Boolean get() = !disabled && enabled && !cooling
}

/** 账号池摘要（可整体或按 provider 统计）。 */
data class PoolSummary(
    val total: Int,
    val usable: Int,
    val cooling: Int,
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
    var untilMillis: Long = 0
    var errorCount: Int = 0
    var kind: CoolKind? = null

    fun healthy(now: Long): Boolean = !disabled && enabled && now >= untilMillis
}

/**
 * 多 provider 账号池：单实例内部按 `providerId` 分片。
 *
 * 选号、冷却、额度都按 provider 独立，互不影响；新增供应商不需要改这个类。
 * 挑选策略：该 provider 内可用账号中取余额最高者；同一请求内换号重试用 [pick] 的 exclude 排除。
 */
class AccountPool(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<String, Entry>()

    /** 各 provider 的状态快照缓存，避免每个账号都重复读同一个文件。 */
    private val stateCache = HashMap<String, JsonObject>()

    init {
        load()
    }

    // ---------------------------------------------------------------- 账号

    fun upsert(account: ProviderAccount) = lock.write {
        val key = keyOf(account.providerId, account.uid)
        val existing = entries[key]
        if (existing != null) {
            existing.account = account
        } else {
            entries[key] = Entry(account)
        }
        persistAccount(account)
        persistState(account.providerId)
    }

    fun remove(providerId: String, uid: String): Boolean = lock.write {
        if (entries.remove(keyOf(providerId, uid)) == null) return@write false
        store.delete(accountKey(providerId, uid))
        persistState(providerId)
        true
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
        entries.entries
            .filter { providerId == null || it.value.account.providerId == providerId }
            .sortedBy { it.key }
            .map { statusOf(it.value) }
    }

    fun status(providerId: String, uid: String): AccountStatus? =
        lock.read { entries[keyOf(providerId, uid)]?.let { statusOf(it) } }

    fun size(providerId: String? = null): Int = lock.read {
        if (providerId == null) entries.size
        else entries.values.count { it.account.providerId == providerId }
    }

    /** 已知 provider 的 id 列表（按首次出现顺序）。 */
    fun providerIds(): List<String> = lock.read {
        entries.values.map { it.account.providerId }.distinct()
    }

    // ---------------------------------------------------------------- 状态

    fun setEnabled(providerId: String, uid: String, enabled: Boolean, reason: String = "") = lock.write {
        val entry = entries[keyOf(providerId, uid)] ?: return@write
        entry.enabled = enabled
        if (!enabled) {
            if (reason.isNotEmpty()) entry.reason = reason
        } else if (entry.reason.isNotEmpty() && !entry.disabled && entry.untilMillis <= nowMillis()) {
            entry.reason = ""
        }
        persistState(providerId)
    }

    /** 凭证失效：硬禁用，只能重新登录恢复。 */
    fun disable(providerId: String, uid: String, reason: String) = lock.write {
        val entry = entries[keyOf(providerId, uid)] ?: return@write
        entry.disabled = true
        entry.reason = reason
        persistState(providerId)
    }

    fun cooldown(providerId: String, uid: String, kind: CoolKind, durationMillis: Long, reason: String) = lock.write {
        val entry = entries[keyOf(providerId, uid)] ?: return@write
        entry.untilMillis = nowMillis() + durationMillis
        entry.reason = reason
        entry.kind = kind
        entry.errorCount = 0
        persistState(providerId)
    }

    fun noteError(providerId: String, uid: String, threshold: Int, durationMillis: Long) = lock.write {
        val entry = entries[keyOf(providerId, uid)] ?: return@write
        entry.errorCount++
        if (entry.errorCount >= threshold) {
            entry.untilMillis = nowMillis() + durationMillis
            entry.reason = "连续 $threshold 次上游错误"
            entry.kind = CoolKind.ERROR
            entry.errorCount = 0
        }
        persistState(providerId)
    }

    /**
     * 手动解除冷却。
     *
     * 熔断是本地保护策略，不代表上游一定不可用（如刚恢复额度），用户应能立即重试。
     * 不碰 `disabled`：凭证失效是硬状态，必须重新登录，不能靠清冷却绕过。
     */
    fun clearCooldown(providerId: String, uid: String): Boolean = lock.write {
        val entry = entries[keyOf(providerId, uid)] ?: return@write false
        if (entry.untilMillis <= nowMillis()) return@write false
        entry.untilMillis = 0
        entry.reason = ""
        entry.errorCount = 0
        entry.kind = null
        persistState(providerId)
        true
    }

    fun noteSuccess(providerId: String, uid: String) = lock.write {
        val entry = entries[keyOf(providerId, uid)] ?: return@write
        if (entry.errorCount != 0) {
            entry.errorCount = 0
            persistState(providerId)
        }
    }

    /**
     * 更新额度。有余额且未被硬禁用时顺带解除冷却（对应「额度恢复后自动启用」）。
     */
    fun updateCredits(
        providerId: String,
        uid: String,
        credits: Long,
        known: Boolean = true,
        detail: String = "",
    ) = lock.write {
        val entry = entries[keyOf(providerId, uid)] ?: return@write
        entry.credits = credits
        entry.creditsKnown = known
        entry.detail = detail
        if (known && credits > 0 && !entry.disabled) {
            entry.untilMillis = 0
            entry.reason = ""
            entry.errorCount = 0
            entry.kind = null
        }
        persistState(providerId)
    }

    /** 刷新凭证后落盘（refreshToken 会轮换，必须立刻写回）。 */
    fun saveAccount(account: ProviderAccount) = lock.write {
        entries[keyOf(account.providerId, account.uid)]?.account = account
        persistAccount(account)
    }

    // ---------------------------------------------------------------- 选号

    /** 取该 provider 可用账号中余额最高者；[exclude] 用于同一请求内的换号重试。 */
    fun pick(providerId: String, exclude: Set<String> = emptySet()): ProviderAccount? = lock.read {
        val now = nowMillis()
        var best: Entry? = null
        for ((key, entry) in entries) {
            if (entry.account.providerId != providerId) continue
            if (entry.account.uid in exclude) continue
            if (!entry.healthy(now)) continue
            if (best == null || entry.credits > best!!.credits) best = entry
        }
        best?.account
    }

    /** 供界面展示的摘要；[providerId] 为空时统计全部。 */
    fun summary(providerId: String? = null): PoolSummary = lock.read {
        val now = nowMillis()
        var total = 0
        var usable = 0
        var cooling = 0
        var disabled = 0
        var disabledByUser = 0
        var totalCredits = 0L
        var creditsKnown = 0
        for (entry in entries.values) {
            if (providerId != null && entry.account.providerId != providerId) continue
            total++
            when {
                entry.disabled -> disabled++
                !entry.enabled -> disabledByUser++
                now < entry.untilMillis -> cooling++
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
            cooling = cooling,
            disabled = disabled,
            disabledByUser = disabledByUser,
            totalCredits = totalCredits,
            creditsKnown = creditsKnown,
        )
    }

    // ---------------------------------------------------------------- 持久化

    private fun statusOf(entry: Entry): AccountStatus {
        val now = nowMillis()
        return AccountStatus(
            providerId = entry.account.providerId,
            uid = entry.account.uid,
            nickname = entry.account.nickname,
            credits = entry.credits,
            creditsKnown = entry.creditsKnown,
            detail = entry.detail,
            cooling = now < entry.untilMillis,
            untilMillis = entry.untilMillis,
            reason = entry.reason,
            disabled = entry.disabled,
            enabled = entry.enabled,
            errorCount = entry.errorCount,
            coolKind = if (now < entry.untilMillis) entry.kind else null,
        )
    }

    private fun keyOf(providerId: String, uid: String) = "$providerId/$uid"

    private fun accountKey(providerId: String, uid: String) = "$ACCOUNT_PREFIX$providerId/$uid"

    private fun persistAccount(account: ProviderAccount) {
        store.write(accountKey(account.providerId, account.uid), account.toJson().toString())
    }

    /** 按 provider 分片落盘：每个 provider 一个状态文件，互不干扰。 */
    private fun persistState(providerId: String) {
        val accounts = JsonObject()
        for (entry in entries.values) {
            if (entry.account.providerId != providerId) continue
            accounts.add(entry.account.uid, JsonObject().apply {
                addProperty("credits", entry.credits)
                addProperty("creditsKnown", entry.creditsKnown)
                addProperty("detail", entry.detail)
                addProperty("disabled", entry.disabled)
                addProperty("enabled", entry.enabled)
                addProperty("reason", entry.reason)
                addProperty("untilMillis", entry.untilMillis)
                addProperty("errorCount", entry.errorCount)
                entry.kind?.let { addProperty("coolKind", it.name) }
            })
        }
        store.write(stateKey(providerId), JsonObject().apply { add("accounts", accounts) }.toString())
    }

    private fun load() {
        for (key in store.keys(ACCOUNT_PREFIX)) {
            val raw = store.read(key) ?: continue
            val account = runCatching { ProviderAccount.parse(raw) }.getOrNull() ?: continue
            val entry = Entry(account)
            loadState(account.providerId)?.get(account.uid)?.let { saved ->
                if (saved.isJsonObject) {
                    val state = saved.asJsonObject
                    entry.credits = state.get("credits")?.asLong ?: 0L
                    entry.creditsKnown = state.get("creditsKnown")?.asBoolean ?: false
                    entry.detail = state.get("detail")?.asString.orEmpty()
                    entry.disabled = state.get("disabled")?.asBoolean ?: false
                    entry.enabled = state.get("enabled")?.asBoolean ?: true
                    entry.reason = state.get("reason")?.asString.orEmpty()
                    entry.untilMillis = state.get("untilMillis")?.asLong ?: 0L
                    entry.errorCount = (state.get("errorCount")?.asLong ?: 0L).toInt()
                    // 历史数据没有 coolKind 字段，按 null 处理（界面回退到通用文案）
                    entry.kind = state.get("coolKind")?.asString
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { runCatching { CoolKind.valueOf(it) }.getOrNull() }
                }
            }
            entries[keyOf(account.providerId, account.uid)] = entry
        }
    }

    private fun loadState(providerId: String): JsonObject? = stateCache.getOrPut(providerId) {
        val raw = store.read(stateKey(providerId)) ?: return@getOrPut JsonObject()
        runCatching { JsonParser.parseString(raw).asJsonObject.objOrNull("accounts") }.getOrNull()
            ?: JsonObject()
    }

    companion object {
        const val ACCOUNT_PREFIX = "account/"
        const val STATE_PREFIX = "pool/state/"

        /** 某个 provider 的状态文件键（测试与迁移脚本用）。 */
        fun stateKey(providerId: String) = "$STATE_PREFIX$providerId.json"
    }
}
