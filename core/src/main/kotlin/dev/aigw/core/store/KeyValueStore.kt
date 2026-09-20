package dev.aigw.core.store

import java.io.File

/**
 * 极简键值存储。Android 侧用加密实现，命令行侧用 [FileKeyValueStore]。
 * 之所以抽成接口，是为了让凭证在两个平台上都只走一条读写路径。
 */
interface KeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
    /** 返回该前缀下的全部键。 */
    fun keys(prefix: String): List<String>
}

/** 以文件落盘的实现：键里的 `/` 会映射成子目录。 */
class FileKeyValueStore(private val root: File) : KeyValueStore {

    init {
        if (!root.exists()) root.mkdirs()
    }

    override fun read(key: String): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    override fun write(key: String, value: String) {
        val file = fileFor(key)
        file.parentFile?.mkdirs()
        // 先写临时文件再改名，避免中途失败留下半截凭证
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(value)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw IllegalStateException("无法覆盖旧文件: ${file.path}")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("无法写入文件: ${file.path}")
        }
    }

    override fun delete(key: String) {
        val file = fileFor(key)
        if (file.isFile) file.delete()
    }

    override fun keys(prefix: String): List<String> {
        val base = root.absolutePath
        return root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.absolutePath.removePrefix(base).trimStart(File.separatorChar).replace(File.separatorChar, '/') }
            .filter { it.startsWith(prefix) }
            .toList()
    }

    private fun fileFor(key: String): File = File(root, key)
}
