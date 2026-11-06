package dev.aigw.core.gateway

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * multipart 解析器的二进制安全性回归。
 *
 * 音频上传必须字节级还原：任何「先转 UTF-8 再转回字节」的实现都会把非法字节序列
 * 替换成 U+FFFD，上传上去就是坏文件，所以这里专门用含 0x00/0xFF 的 wav 头做断言。
 */
class MultipartFormTest {

    private val boundary = "----aigwBoundary7MA4YWxkTrZu0gW"

    /** 一段「wav」字节：含 0x00、0xFF、以及会被 UTF-8 解码破坏的孤立高位字节。 */
    private val wavBytes = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        0x24, 0x00, 0x00, 0x00,
        'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
        0x00, 0xFF.toByte(), 0x80.toByte(), 0xC3.toByte(), 0x28, 0x00, 0x7F,
    )

    private fun buildBody(): ByteArray = MultipartForm.build(
        boundary,
        listOf(
            MultipartForm.Field("file", wavBytes, fileName = "sample 录音.wav", contentType = "audio/wav"),
            MultipartForm.Field("model", "whisper-1".toByteArray()),
            MultipartForm.Field("language", "zh".toByteArray()),
        ),
    )

    @Test
    fun `二进制音频字节被原样还原`() {
        val parts = MultipartForm.parse(buildBody(), boundary)
        val file = MultipartForm.find(parts, "file")!!
        assertContentEquals(wavBytes, file.data, "音频字节必须完全一致，不能被文本编码污染")
        assertEquals("sample 录音.wav", file.fileName)
        assertEquals("audio/wav", file.contentType)
        assertEquals("whisper-1", MultipartForm.find(parts, "model")!!.text)
    }

    @Test
    fun `正文里出现类似分隔符的字节不会误切`() {
        // 内容里放 `--` 前缀但不是完整分隔符：只有 `--boundary` 才算
        val tricky = "--not-a-boundary\r\nraw".toByteArray()
        val body = MultipartForm.build(boundary, listOf(MultipartForm.Field("file", tricky, fileName = "a.mp3")))
        val file = MultipartForm.find(MultipartForm.parse(body, boundary), "file")!!
        assertContentEquals(tricky, file.data)
    }

    @Test
    fun `从 Content-Type 里取 boundary，带引号也认`() {
        assertEquals(boundary, MultipartForm.boundaryOf("multipart/form-data; boundary=$boundary"))
        assertEquals(boundary, MultipartForm.boundaryOf("multipart/form-data; boundary=\"$boundary\""))
        // 参数顺序颠倒、大小写混杂也要认
        assertEquals(boundary, MultipartForm.boundaryOf("Multipart/Form-Data; charset=utf-8; Boundary=\"$boundary\""))
        assertNull(MultipartForm.boundaryOf("application/json"))
        assertNull(MultipartForm.boundaryOf("multipart/form-data"))
    }

    @Test
    fun `缺少结束分隔符时报格式错误`() {
        val truncated = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nabc".toByteArray()
        assertFailsWith<MultipartForm.MalformedException> {
            MultipartForm.parse(truncated, boundary)
        }
    }

    @Test
    fun `文件名里带分号不影响 name 解析`() {
        val body = MultipartForm.build(
            boundary,
            listOf(MultipartForm.Field("file", "x".toByteArray(), fileName = "a;b.wav")),
        )
        val file = MultipartForm.find(MultipartForm.parse(body, boundary), "file")!!
        assertEquals("a;b.wav", file.fileName)
        assertTrue(file.data.isNotEmpty())
    }
}
