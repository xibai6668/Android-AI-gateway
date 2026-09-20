package dev.aigw.core

import dev.aigw.core.store.KeyValueStore

/** 测试用的内存键值存储。 */
class InMemoryKeyValueStore : KeyValueStore {
    private val data = LinkedHashMap<String, String>()

    override fun read(key: String): String? = data[key]

    override fun write(key: String, value: String) {
        data[key] = value
    }

    override fun delete(key: String) {
        data.remove(key)
    }

    override fun keys(prefix: String): List<String> = data.keys.filter { it.startsWith(prefix) }

    fun raw(): Map<String, String> = data.toMap()
}
