package dev.aigw.core.gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * 会话 → (供应商, 账号) 亲和表。
 *
 * 为什么需要：上游按账号给提示词缓存分桶，同一会话每轮落到不同账号会让前缀缓存反复作废，
 * 长上下文下表现为重复计费与首字变慢。这里只记「上一轮是谁答的」，选号时优先复用它，
 * 不可用时才允许漂移——**绝不因为亲和而让请求失败**。
 *
 * 线程安全：网关线程并发读写，用 [ConcurrentHashMap] 承载。
 * 有界性：条目数超过 [capacity] 时按「最久未被 bind」淘汰到容量的 90%，
 * 一次淘汰多清一点，避免在容量边界上每插入一条就全表扫描一次。
 *
 * 注意：本类只提供事实（谁答过什么），不参与任何路由决策，也不做 IO；
 * 「亲和账号不可用时换谁」由调用方决定。
 */
class SessionAffinity(
    private val capacity: Int = 2048,
    private val ttlMillis: Long = 30 * 60 * 1000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * 一次成功应答的归属。
     *
     * [boundAtMillis] 既是 TTL 计时起点，也是淘汰排序的「最近使用」依据
     * （bind 即代表该会话最近用过这个账号）。
     */
    data class Binding(val providerId: String, val uid: String, val boundAtMillis: Long) {
        fun isExpired(now: Long, ttl: Long): Boolean = now - boundAtMillis >= ttl
    }

    private val bindings = ConcurrentHashMap<String, Binding>()

    /** 当前绑定条数（含尚未被清理的过期条目，仅用于观测/测试）。 */
    val size: Int get() = bindings.size

    /** 记录一次成功应答的归属。 */
    fun bind(sessionKey: String, providerId: String, uid: String) {
        // 空 key 表示「没识别出会话」（如无 session 头的裸调用），记下来只会污染表
        if (sessionKey.isEmpty()) return
        bindings[sessionKey] = Binding(providerId, uid, nowMillis())
        if (bindings.size > capacity) evict()
    }

    /** 查亲和；过期或不存在返回 null（并顺手清理）。 */
    fun lookup(sessionKey: String): Binding? {
        if (sessionKey.isEmpty()) return null
        val binding = bindings[sessionKey] ?: return null
        if (binding.isExpired(nowMillis(), ttlMillis)) {
            // 顺手摘掉：过期条目留着只会占容量并拖慢淘汰
            bindings.remove(sessionKey, binding)
            return null
        }
        return binding
    }

    /**
     * 账号不可用（凭证失效/被删/被禁用）时主动摘掉所有指向它的绑定。
     *
     * 为什么要主动摘而不是等 TTL：失效账号在池子里可能立刻被替换掉，
     * 留着绑定只会让下一轮先试一个必然失败的账号，白等一次失败。
     */
    fun invalidateAccount(providerId: String, uid: String) {
        bindings.entries.removeIf { it.value.providerId == providerId && it.value.uid == uid }
    }

    fun clear() {
        bindings.clear()
    }

    /**
     * 淘汰到容量的 90%（向下取整，至少留 1 条）。
     *
     * 按 boundAtMillis 升序取最旧的若干条删除——即「最久未用」。
     * 排序发生在快照上，删不存在的条目时 remove(k, v) 会失败，不影响并发正确性。
     */
    private fun evict() {
        val target = maxOf((capacity * 9) / 10, 1)
        val excess = bindings.size - target
        if (excess <= 0) return
        bindings.entries
            .sortedBy { it.value.boundAtMillis }
            .take(excess)
            .forEach { bindings.remove(it.key, it.value) }
    }
}
