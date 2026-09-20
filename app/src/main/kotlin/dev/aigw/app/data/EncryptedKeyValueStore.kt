package dev.aigw.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.aigw.core.store.KeyValueStore

/**
 * 凭证、设置与日志的落盘实现：Android Keystore 保护下的加密 SharedPreferences。
 *
 * 写操作走 `commit()` 而不是 `apply()`：换 token 后需要立刻回读新值，
 * 异步写会让紧随其后的读拿到旧数据。
 */
class EncryptedKeyValueStore(context: Context) : KeyValueStore {

    private val prefs: SharedPreferences = open(context.applicationContext)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).commit()
    }

    override fun keys(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }

    private fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            create(context, masterKey)
        } catch (_: Exception) {
            // 密钥随备份/换机丢失时旧数据无法解密，清掉重建，避免每次启动都崩
            context.deleteSharedPreferences(FILE_NAME)
            create(context, masterKey)
        }
    }

    private fun create(context: Context, masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private companion object {
        const val FILE_NAME = "aigw.store"
    }
}
