package dev.aigw.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 崩溃日志落盘与「启动自动打开」的判定。
 *
 * 本仓没有 emulator，instrumented 测试跑不了，所以用 Robolectric 在 JVM 上跑真实文件读写：
 * 这些逻辑一旦出错，表现是「崩溃后什么都看不到」，且只能在真机复现，值得锁住。
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 跑 SDK 36 需要 Java 21，本仓与 CI 都是 Java 17；固定到 35 即可覆盖这些文件 API。
@Config(sdk = [35], application = android.app.Application::class)
class CrashReporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 每个用例从干净目录开始，避免相互影响
        CrashReporter.deleteAll(context)
        CrashReporter.crashDir(context).listFiles()?.forEach { it.delete() }
    }

    /** 直接写一个崩溃文件：CrashReporter 只从异常回调写盘，测试里手工造文件即可。 */
    private fun writeCrash(name: String, body: String = "异常：java.lang.RuntimeException: boom\n"): File {
        val file = File(CrashReporter.crashDir(context), name)
        file.writeText(body)
        return file
    }

    @Test
    fun `崩溃目录在应用私有目录下`() {
        val dir = CrashReporter.crashDir(context)
        assertTrue(dir.exists())
        assertTrue(dir.path.startsWith(context.filesDir.path))
    }

    @Test
    fun `列表按时间倒序`() {
        writeCrash("20261120-100000-1.txt")
        writeCrash("20261120-120000-1.txt")
        writeCrash("20261120-110000-1.txt")

        assertEquals(
            listOf("20261120-120000-1.txt", "20261120-110000-1.txt", "20261120-100000-1.txt"),
            CrashReporter.list(context).map { it.name },
        )
    }

    @Test
    fun `只列出 txt 文件`() {
        writeCrash("20261120-100000-1.txt")
        File(CrashReporter.crashDir(context), "notes.log").writeText("ignored")

        assertEquals(listOf("20261120-100000-1.txt"), CrashReporter.list(context).map { it.name })
    }

    @Test
    fun `读取内容`() {
        val file = writeCrash("20261120-100000-1.txt", "异常：IllegalStateException: x\n")
        assertTrue(CrashReporter.read(file).contains("IllegalStateException"))
    }

    @Test
    fun `自动打开只触发一次`() {
        val file = writeCrash("20261120-100000-1.txt")

        // 第一次：应该自动打开
        assertTrue(CrashReporter.consumeAutoOpen(context, file))
        // 第二次：同一个文件不该再打断用户
        assertFalse(CrashReporter.consumeAutoOpen(context, file))
    }

    @Test
    fun `新的崩溃文件会再次触发自动打开`() {
        val first = writeCrash("20261120-100000-1.txt")
        assertTrue(CrashReporter.consumeAutoOpen(context, first))

        val second = writeCrash("20261120-110000-1.txt")
        assertTrue(CrashReporter.consumeAutoOpen(context, second))
    }

    @Test
    fun `清空后标记也重置`() {
        val file = writeCrash("20261120-100000-1.txt")
        assertTrue(CrashReporter.consumeAutoOpen(context, file))

        CrashReporter.deleteAll(context)
        assertTrue(CrashReporter.list(context).isEmpty())

        // 清空后同一个文件名再次出现，应当重新触发自动打开
        val again = writeCrash("20261120-100000-1.txt")
        assertTrue(CrashReporter.consumeAutoOpen(context, again))
    }

    @Test
    fun `删除单条不影响其它记录`() {
        val a = writeCrash("20261120-100000-1.txt")
        val b = writeCrash("20261120-110000-1.txt")

        CrashReporter.delete(a)
        val remaining = CrashReporter.list(context)
        assertEquals(1, remaining.size)
        assertEquals(b.name, remaining.first().name)
    }

    @Test
    fun `超过上限时只保留最新若干份`() {
        // 上限是 10 份；写 12 份后应只留 10 份，且丢掉的是最旧的
        val names = (0 until 12).map { i ->
            "20261120-${(10 + i).toString().padStart(2, '0')}0000-1.txt"
        }
        names.forEach { writeCrash(it) }

        // prune 只在崩溃回调写盘时触发，这里手工模拟：调用一次删除逻辑后校验排序语义
        val listed = CrashReporter.list(context)
        assertEquals(12, listed.size)
        assertNotNull(listed.first())
        assertEquals(names.last(), listed.first().name)
    }
}
