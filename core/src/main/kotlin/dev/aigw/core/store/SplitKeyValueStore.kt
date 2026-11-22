package dev.aigw.core.store

/**
 * 按键前缀把写入路由到不同存储实现。
 *
 * 起因是加密存储的物理约束：Android 侧的实现把所有键放在同一个文件里，每次提交
 * 都要整份重写。调用记录是体积大头（每条含请求/响应原文），把它和凭证、设置放在
 * 同一个文件里，会让「改一条设置」也背上重写几 MB 日志的代价。
 *
 * 这里按前缀拆成两个文件后：主文件回到百 KB 量级，日志文件独自增长；
 * 两者的提交互不阻塞，体量增长也不再拖慢凭证读写。
 *
 * [keys] 会把各路由与兜底的结果合并，因此上层（存储统计、日志页）无需知道自己
 * 面对的是拆分后的存储。
 */
class SplitKeyValueStore(
    /** 前缀 → 目标存储；按最长前缀匹配。 */
    private val routes: Map<String, KeyValueStore>,
    /** 未命中任何路由时的存储。 */
    private val fallback: KeyValueStore,
) : KeyValueStore {

    private val orderedRoutes: List<Pair<String, KeyValueStore>> = routes.entries
        .sortedByDescending { it.key.length }
        .map { it.key to it.value }

    override fun read(key: String): String? = storeFor(key).read(key)

    override fun write(key: String, value: String) = storeFor(key).write(key, value)

    override fun delete(key: String) = storeFor(key).delete(key)

    override fun keys(prefix: String): List<String> {
        // 前缀可能横跨多个路由（如 `logs/` 与 `logs/calls/` 都命中同一目标，或查询空前缀），
        // 所以不能只看 storeFor(prefix)，要逐个目标取并集。
        val merged = LinkedHashSet<String>()
        for (store in allStores()) {
            for (key in store.keys(prefix)) {
                if (key.startsWith(prefix)) merged.add(key)
            }
        }
        return merged.toList()
    }

    override fun flush() {
        for (store in allStores()) store.flush()
    }

    /**
     * 把旧版本留在 [fallback] 里的、命中路由前缀的数据搬到对应存储。
     *
     * 拆文件之前，日志与用量都写在主文件里；不搬的话升级后「调用记录与用量全空」。
     * 搬完在 [markerKey] 写一个标记，后续启动只多一次读取。搬运本身幂等：
     * 写入目标成功后才从源删除，中途被杀下次重来不会丢数据。
     */
    fun migrateLegacy(markerKey: String = DEFAULT_MARKER_KEY) {
        // 标记必须读写同一个位置：放在兜底存储（主文件）里，它在拆分前后位置不变，
        // 不会因为路由配置调整而"失忆"导致重复搬运。
        if (fallback.read(markerKey) != null) return
        for ((prefix, target) in orderedRoutes) {
            if (target === fallback) continue
            for (key in fallback.keys(prefix)) {
                val value = fallback.read(key) ?: continue
                target.write(key, value)
                fallback.delete(key)
            }
        }
        fallback.write(markerKey, "1")
    }

    private fun storeFor(key: String): KeyValueStore {
        for ((prefix, store) in orderedRoutes) {
            if (key.startsWith(prefix)) return store
        }
        return fallback
    }

    private fun allStores(): List<KeyValueStore> =
        (orderedRoutes.map { it.second } + fallback).distinct()

    companion object {
        const val DEFAULT_MARKER_KEY = "settings/store_split_migrated"

        /** 拆分后仍留在主文件里的数据：凭证、设置、登录中间态、密钥配置。 */
        val MAIN_STORE_PREFIXES = listOf("account/", "settings/", "login/", "pool/")

        /** 体积大、价值低、可重算的数据走独立文件。 */
        val LOG_STORE_PREFIXES = listOf("logs/", "usage/")
    }
}
