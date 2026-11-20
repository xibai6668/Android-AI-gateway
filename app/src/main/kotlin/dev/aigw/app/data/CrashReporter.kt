package dev.aigw.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘。
 *
 * 为什么不用 `Thread.setDefaultUncaughtExceptionHandler` 之外的东西：进程崩溃时其它线程随时会死，
 * 唯一可靠的时机就是崩溃回调本身，所以这里**同步写文件**（不走协程、不走异步队列），
 * 写完再交还给系统默认处理器，让系统照常弹「应用已停止」。
 *
 * 日志写在 `filesDir/crash/` 下，按时间戳命名，最多保留 [MAX_FILES] 份，
 * 避免反复崩溃把存储写满。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crash"
    /** 最多保留的份数（超出后删最旧的）。界面会引用它做提示文案。 */
    const val MAX_FILES = 10
    private const val PREF_NAME = "crash_reporter"
    private const val KEY_SEEN = "auto_opened_file"

    private val FILE_TIME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 最近一次崩溃文件路径（供「启动后自动打开崩溃页」判断）。 */
    @Volatile
    var lastCrashFile: File? = null
        private set

    /**
     * 安装全局崩溃捕获。
     *
     * 必须在 [android.app.Application.onCreate] 里最早调用——晚于任何可能崩溃的初始化就抓不到了。
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = write(appContext, thread, throwable)
                lastCrashFile = file
            } catch (e: Throwable) {
                // 崩溃处理里再抛异常会掩盖原始崩溃，只能吞掉并记一笔
                Log.e(TAG, "写崩溃日志失败", e)
            } finally {
                // 交还给系统默认处理器：否则系统不会弹「应用已停止」，也不会清理进程状态
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /** 读取全部崩溃日志（新→旧）。 */
    fun list(context: Context): List<File> =
        crashDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /**
     * 判断这份崩溃日志是否应该「启动即自动打开」，并把它标记为已看过。
     *
     * 没有这个标记的话，每次启动都会跳崩溃页（文件一直在），用户会被反复打断。
     * 存标记用普通 SharedPreferences：加密存储本身也属于可能崩溃的候选，不能拿它记这件事。
     */
    fun consumeAutoOpen(context: Context, file: File): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SEEN, null) == file.name) return false
        prefs.edit().putString(KEY_SEEN, file.name).apply()
        return true
    }

    fun read(file: File): String = runCatching { file.readText() }.getOrDefault("")

    fun deleteAll(context: Context) {
        list(context).forEach { runCatching { it.delete() } }
        lastCrashFile = null
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_SEEN).apply()
    }

    fun delete(file: File) {
        runCatching { file.delete() }
        if (lastCrashFile == file) lastCrashFile = null
    }

    /** 崩溃日志目录（同时用于「私有目录」入口跳转）。 */
    fun crashDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun write(context: Context, thread: Thread, throwable: Throwable): File {
        val now = System.currentTimeMillis()
        val dir = crashDir(context)
        val file = File(dir, "${FILE_TIME_FORMAT.format(Date(now))}-${thread.id}.txt")

        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))}")
            appendLine("线程：${thread.name}（id=${thread.id}）")
            appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
            appendLine("版本：${appVersion(context)}")
            appendLine("设备：Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）/ ${android.os.Build.MODEL}")
            appendLine()
            appendLine(stack)
        }
        file.writeText(text)
        prune(dir)
        return file
    }

    /** 只留最新的 [MAX_FILES] 份。 */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName}($code)"
    }.getOrDefault("未知")
}
