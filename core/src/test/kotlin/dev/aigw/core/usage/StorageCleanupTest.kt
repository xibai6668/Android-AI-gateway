package dev.aigw.core.usage

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.StorageDirProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 存储占用统计与缓存清理。
 *
 * 线上症状：应用占用到几十 MB，但界面上只显示一个几 MB 的数字，且没有任何清理入口。
 * 根因是占用大头在缓存目录（更新包、崩溃日志、调用记录的完整原文），而统计只数键值存储。
 *
 * 这里锁住两条：统计必须把目录算进去；清理只能动可清理项，凭证与调用记录不能被误删。
 */
class StorageCleanupTest {

    private fun tempDir(name: String): File =
        File.createTempFile(name, "").let { f ->
            f.delete()
            f.mkdirs()
            f.apply { deleteOnExit() }
        }

    private fun writeFile(dir: File, name: String, bytes: Int): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes) { 'x'.code.toByte() })
        return file
    }

    private fun engineWith(dirs: List<StorageAudit.DirectoryGroup>, store: InMemoryKeyValueStore = InMemoryKeyValueStore()) =
        GatewayEngine(store, storageDirs = StorageDirProvider { dirs })

    @Test
    fun `统计把目录占用一并算进总量`() {
        val store = InMemoryKeyValueStore()
        store.write("account/trae/u1", "c".repeat(1_000))
        val apkDir = tempDir("updates")
        writeFile(apkDir, "update-1.apk", 5_000)

        val report = engineWith(
            listOf(
                StorageAudit.DirectoryGroup(
                    name = StorageReport.NAME_UPDATE_APK,
                    dir = apkDir,
                    detail = "更新包",
                    clearable = true,
                ),
            ),
            store,
        ).storageReport()

        val apk = report.entries.first { it.name == StorageReport.NAME_UPDATE_APK }
        assertEquals(5_000L, apk.bytes)
        assertEquals(1, apk.items)
        assertTrue(apk.clearable)
        assertEquals(6_000L, report.totalBytes, "目录占用必须计入总量，否则界面数字对不上系统显示")
        assertEquals(5_000L, report.clearableBytes)
    }

    @Test
    fun `没有目录时统计只算键值存储`() {
        val store = InMemoryKeyValueStore()
        store.write("settings/gateway.json", "s".repeat(300))
        val report = engineWith(emptyList(), store).storageReport()
        assertEquals(300L, report.totalBytes)
        assertEquals(0L, report.clearableBytes)
    }

    @Test
    fun `清理缓存只删可清理项并保留调用记录与凭证`() {
        val store = InMemoryKeyValueStore()
        store.write("account/trae/u1", "credential")
        store.write("logs/calls/a", "record")
        val apkDir = tempDir("updates")
        writeFile(apkDir, "update-1.apk", 4_000)
        writeFile(apkDir, "update-2.apk", 4_000)
        val crashDir = tempDir("crash")
        writeFile(crashDir, "crash.txt", 500)

        val engine = engineWith(
            listOf(
                StorageAudit.DirectoryGroup(StorageReport.NAME_UPDATE_APK, apkDir, "更新包", clearable = true),
                StorageAudit.DirectoryGroup(StorageReport.NAME_CRASH, crashDir, "崩溃日志", clearable = true),
            ),
            store,
        )
        val (freed, files) = engine.clearCaches()

        assertEquals(8_500L, freed)
        assertEquals(3L, files)
        assertEquals(0, apkDir.listFiles()?.size ?: 0, "更新包应被清空")
        assertEquals(0, crashDir.listFiles()?.size ?: 0, "崩溃日志应被清空")
        assertEquals("credential", store.read("account/trae/u1"), "凭证绝不能被清理")
        assertEquals("record", store.read("logs/calls/a"), "调用记录不属于缓存，不能被清理")
    }

    @Test
    fun `不可清理的目录不会被清理`() {
        val store = InMemoryKeyValueStore()
        val keepDir = tempDir("keep")
        writeFile(keepDir, "important.bin", 1_000)
        val engine = engineWith(
            listOf(StorageAudit.DirectoryGroup("重要数据", keepDir, "不可清理", clearable = false)),
            store,
        )
        val (freed, files) = engine.clearCaches()
        assertEquals(0L, freed)
        assertEquals(0L, files)
        assertEquals(1, keepDir.listFiles()?.size)
    }

    @Test
    fun `清理会连带删掉完整原文溢出文件`() {
        val spill = tempDir("spill")
        writeFile(spill, "a.request.txt", 2_000)
        writeFile(spill, "a.response.txt", 2_000)
        val engine = GatewayEngine(InMemoryKeyValueStore(), spillDir = spill)

        val (freed, files) = engine.clearCaches()

        assertEquals(4_000L, freed)
        assertEquals(2L, files)
        assertEquals(0, spill.listFiles()?.size ?: 0)
    }

    @Test
    fun `清理后缓存类占用归零`() {
        val store = InMemoryKeyValueStore()
        val apkDir = tempDir("updates2")
        writeFile(apkDir, "update.apk", 3_000)
        val engine = engineWith(
            listOf(StorageAudit.DirectoryGroup(StorageReport.NAME_UPDATE_APK, apkDir, "更新包", clearable = true)),
            store,
        )
        assertEquals(3_000L, engine.storageReport().totalBytes)
        engine.clearCaches()
        val after = engine.storageReport()
        assertEquals(0L, after.clearableBytes, "可清理项应归零")
        // 清理本身会写一条运行日志（logs/requests.json），所以总量不会恰好是 0；
        // 关键是目录占用已经清掉。
        assertEquals(0L, after.entries.first { it.name == StorageReport.NAME_UPDATE_APK }.bytes)
    }

    @Test
    fun `目录不存在时统计与清理都不报错`() {
        val missing = File("/tmp/aigw-not-exists-${System.nanoTime()}")
        val engine = engineWith(
            listOf(StorageAudit.DirectoryGroup("不存在", missing, "占位", clearable = true)),
        )
        val report = engine.storageReport()
        assertEquals(0L, report.totalBytes)
        val (freed, files) = engine.clearCaches()
        assertEquals(0L, freed)
        assertEquals(0L, files)
    }

    @Test
    fun `清理会顺手删掉删空的子目录`() {
        val dir = tempDir("nested")
        val sub = File(dir, "sub").apply { mkdirs() }
        writeFile(sub, "f.bin", 100)
        val engine = engineWith(
            listOf(StorageAudit.DirectoryGroup("嵌套", dir, "占位", clearable = true)),
        )
        engine.clearCaches()
        assertFalse(sub.exists(), "删空的子目录应被清掉，避免留下一堆空文件夹")
        assertTrue(dir.exists(), "被统计的目录本身要保留")
    }
}
