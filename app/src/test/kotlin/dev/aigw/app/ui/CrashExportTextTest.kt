package dev.aigw.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志的导出内容。
 *
 * 导出的 .log 是要发给别人看的东西：格式错乱（少分隔线、内容串行）会让对方读不懂，
 * 所以把格式本身锁住。不依赖 Android 环境，纯 JVM 即可跑。
 */
class CrashExportTextTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun crashFile(name: String, content: String): File =
        temp.newFile(name).apply { writeText(content) }

    @Test
    fun `空列表也给出可读的表头`() {
        val text = buildExportText(emptyList(), Date(0))
        assertTrue(text.contains("crash", ignoreCase = true) || text.contains("崩溃"))
        assertTrue(text.contains("记录数：0"))
    }

    @Test
    fun `单份记录包含文件名与内容`() {
        val file = crashFile("20261120-100000-1.txt", "异常：RuntimeException: boom\n")
        val text = buildExportText(listOf(file), Date(0))

        assertTrue(text.contains("<<< ${file.name} >>>"))
        assertTrue(text.contains("RuntimeException: boom"))
        // 只有一份时不该出现分隔线
        assertTrue(!text.contains("=".repeat(60)))
    }

    @Test
    fun `多份记录用分隔线隔开`() {
        val a = crashFile("20261120-100000-1.txt", "异常：A\n")
        val b = crashFile("20261120-110000-1.txt", "异常：B\n")
        val text = buildExportText(listOf(a, b), Date(0))

        assertTrue(text.contains("=".repeat(60)))
        // 两份都在
        assertTrue(text.contains("<<< ${a.name} >>>"))
        assertTrue(text.contains("<<< ${b.name} >>>"))
    }

    @Test
    fun `记录数如实反映传入数量`() {
        val files = (0 until 3).map { crashFile("20261120-10${it}000-1.txt", "异常：$it\n") }
        assertTrue(buildExportText(files, Date(0)).contains("记录数：3"))
    }

    @Test
    fun `导出时间可注入，便于断言`() {
        val when0 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("2026-11-20 10:30:00")!!
        val text = buildExportText(emptyList(), when0)
        assertTrue(text.contains("2026-11-20 10:30:00"))
        assertEquals(1, text.lines().count { it.startsWith("导出时间：") })
    }
}
