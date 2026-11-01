package dev.aigw.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.aigw.core.store.KeyValueStore

/**
 * 主进程独有的轻量偏好存储：外观、更新检查等仅 UI 使用的键。
 *
 * 刻意不落在引擎的加密存储里——那是 SharedPreferences 整文件重写，两个进程各持一份会互相覆盖
 * （会把对方的凭证/账号池写入冲掉）。这些偏好只有 UI 读写、也不含敏感信息，用普通存储即可。
 */
class UiPrefsStore(context: Context) : KeyValueStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }

    companion object {
        const val FILE_NAME = "aigw.ui"
    }
}
