package dev.aigw.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        } catch (e: Exception) {
            if (!isKeyInvalidation(e)) {
                // 不是「密钥失效」的异常（如磁盘/IO/内存问题）绝不清数据：
                // 清空重建会把用户的全部账号凭证一起丢掉，代价远大于一次启动失败。
                Log.e(TAG, "打开加密存储失败，原因非密钥失效，保留原有数据不清理", e)
                throw e
            }
            // 密钥随备份/换机丢失（或密钥被系统永久作废）时旧数据已无法解密，
            // 此时清掉重建才是唯一可行的自愈方式，否则每次启动都崩。
            Log.w(TAG, "加密存储密钥失效，清空并重建（原有数据无法解密）", e)
            context.deleteSharedPreferences(FILE_NAME)
            create(context, masterKey)
        }
    }

    /**
     * 是否为「密钥/数据无法解密」类异常：只有这类才该清空重建。
     *
     * [java.security.GeneralSecurityException] 覆盖 AEADBadTagException / BadPaddingException /
     * KeyPermanentlyInvalidatedException / InvalidKeyException 等；Tink 读坏文件时抛的
     * InvalidProtocolBufferException 不在该继承链上，按类名兜底。
     */
    private fun isKeyInvalidation(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.security.GeneralSecurityException) return true
            val name = cause.javaClass.name
            if (name.contains("InvalidProtocolBuffer") || name.contains("GeneralSecurity")) return true
            cause = cause.cause?.takeIf { it !== cause }
        }
        return false
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
        const val TAG = "EncryptedKeyValueStore"
    }
}
