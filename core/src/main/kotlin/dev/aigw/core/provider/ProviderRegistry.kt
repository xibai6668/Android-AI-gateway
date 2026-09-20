package dev.aigw.core.provider

/**
 * 所有 provider 的登记处。
 *
 * 新增一个供应商 = 新增一个自包含目录 + 在这里加一行 `register(...)`，
 * 网关与其它 provider 都不需要改动。
 */
class ProviderRegistry {
    private val providers = LinkedHashMap<String, Provider>()

    fun register(provider: Provider) {
        providers[provider.id] = provider
    }

    fun get(id: String): Provider? = providers[id]

    fun unregister(id: String) {
        providers.remove(id)
    }

    fun all(): List<Provider> = providers.values.toList()

    fun require(id: String): Provider =
        providers[id] ?: throw IllegalArgumentException("未知供应商：$id")
}
