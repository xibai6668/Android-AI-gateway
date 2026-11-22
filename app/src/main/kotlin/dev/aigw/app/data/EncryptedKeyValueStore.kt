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
 *
 * [fileName] 决定写入哪个加密文件：加密 SharedPreferences 每次提交都会整份重写，
 * 把体积大的调用记录与凭证放进同一个文件，会让每次落盘都背上重写全部日志的代价。
 * 因此 App 侧开出两个实例，再由 [dev.aigw.core.store.SplitKeyValueStore] 按前缀路由。
 */
class EncryptedKeyValueStore(
    context: Context,
    private val fileName: String = FILE_NAME,
) : KeyValueStore {

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
            context.deleteSharedPreferences(fileName)
            create(context, masterKey)
        }
    }

    /**
     * 是否为「密钥/数据无法解密」类异常：只有这类才该清空重建。
     *
     * 必须逐类白名单，不能整个 [java.security.GeneralSecurityException] 都算：
     * `KeyStoreException` / `NoSuchAlgorithmException` / `InvalidAlgorithmParameterException`
     * 等也在该继承链上，却可能只是 keystore 瞬时躁动（并非数据无法解密）。
     * 误判的代价是**静默清空用户全部账号凭证**，不可恢复。
     */
    private fun isKeyInvalidation(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            when {
                // 数据确实解不开（AEAD/GCM 验签失败、填充错误、密钥被系统作废）
                cause is javax.crypto.AEADBadTagException -> return true
                cause is java.security.InvalidKeyException -> return true
                cause is android.security.keystore.KeyPermanentlyInvalidatedException -> return true
                cause is javax.crypto.BadPaddingException -> return true
                // 类型名兜底：部分厂商/版本包装成同义的其他类
                cause.javaClass.name.let {
                    it.contains("KeyPermanentlyInvalidated") || it.contains("AEADBadTag")
                } -> return true
                // Tink 读坏文件（不在 GeneralSecurityException 继承链上）
                cause.javaClass.name.contains("InvalidProtocolBuffer") -> return true
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        return false
    }

    private fun create(context: Context, masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    companion object {
        /** 主文件：凭证、设置、密钥配置与登录中间态。 */
        const val FILE_NAME = "aigw.store"

        /** 日志文件：调用记录、用量聚合、密钥台账。单独成文件，避免拖慢主文件的每次提交。 */
        const val LOG_FILE_NAME = "aigw.logs"

        private const val TAG = "EncryptedKeyValueStore"
    }
}
