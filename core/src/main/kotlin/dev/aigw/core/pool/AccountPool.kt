package dev.aigw.core.pool

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.objOrNull
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

    fun healthy(): Boolean = !disabled && enabled
}

/**
 * 多 provider 账号池：单实例内部按 `providerId` 分片。
 *
 * 选号与额度都按 provider 独立，互不影响；新增供应商不需要改这个类。
 * 挑选策略：该 provider 内可用账号中取余额最高者；同一请求内换号重试用 [pick] 的 exclude 排除。
 *
 * 不做本地冷却：上游每次报什么就是什么，由调用方（换号重试/跨供应商 Failover）应对，
 * 避免本地状态把明明可用的账号挡在门外。
 */
class AccountPool(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<String, Entry>()

    /** 按 provider 分组的条目索引：选号只需扫该供应商的账号，不必遍历整个池。 */
    private val byProvider = HashMap<String, MutableList<Entry>>()

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
            // 重新登录/刷新凭证意味着用户提供了新凭证：清除硬禁用状态。
            // 否则会出现「重新登录后依然 503」——旧凭证的失效状态沾在新凭证上。
            existing.disabled = false
            existing.reason = ""
        } else {
            val entry = Entry(account)
            entries[key] = entry
            byProvider.getOrPut(account.providerId) { ArrayList() }.add(entry)
        }
        persistAccount(account)
        persistState(account.providerId)
    }

    fun remove(providerId: String, uid: String): Boolean = lock.write {
        val entry = entries.remove(keyOf(providerId, uid)) ?: return@write false
        val group = byProvider[providerId]
        group?.remove(entry)
        if (group != null && group.isEmpty()) byProvider.remove(providerId)
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
        else byProvider[providerId]?.size ?: 0
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
        } else if (entry.reason.isNotEmpty() && !entry.disabled) {
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

    /** 更新额度。 */
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
        val group = byProvider[providerId] ?: return@read null
        var best: Entry? = null
        for (entry in group) {
            if (entry.account.uid in exclude) continue
            if (!entry.healthy()) continue
            if (best == null || entry.credits > best!!.credits) best = entry
        }
        best?.account
    }

    /** 供界面展示的摘要；[providerId] 为空时统计全部。 */
    fun summary(providerId: String? = null): PoolSummary = lock.read {
        var total = 0
        var usable = 0
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
    )

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
                }
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
