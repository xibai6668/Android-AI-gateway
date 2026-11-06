package dev.aigw.core.gateway

import java.io.ByteArrayOutputStream

/**
 * `multipart/form-data` 的一个字段。
 *
 * [data] 是**原始字节**：音频文件不能被当成 UTF-8 文本走一遍（任何非法字节序列都会被
 * 替换成 U+FFFD，上传上去的就是坏文件），所以解析全程只在 ByteArray 上定位分隔符。
 */
class MultipartPart(
    val name: String,
    val fileName: String,
    val contentType: String,
    val data: ByteArray,
) {
    /** 文本字段的 UTF-8 内容；二进制字段不要用它。 */
    val text: String get() = data.toString(Charsets.UTF_8)
}

/**
 * 极简 multipart/form-data 解析器。
 *
 * core 只能用 gson + HttpURLConnection，引不进 Apache Commons FileUpload 之类的库，
 * 而 `POST /v1/audio/transcriptions` 是二进制上传，必须自己解。
 *
 * 只做 RFC 7578 里实际会遇到的子集：
 * - 分隔符 `--boundary`，结束分隔符 `--boundary--`；
 * - 每个 part 的 `Content-Disposition` 里取 `name` 与 `filename`（值可能带引号）；
 * - 可选 `Content-Type`；
 * - part 正文保留原始字节，末尾的 CRLF 属于分隔符开销，需去掉恰好一个。
 */
object MultipartForm {

    /** 解析失败（缺少 boundary、帧不完整）时抛出；调用方翻译成 400。 */
    class MalformedException(message: String) : Exception(message)

    /** 从 `Content-Type` 头里取 boundary；不是 multipart 或没有 boundary 时返回 null。 */
    fun boundaryOf(contentType: String): String? {
        val lower = contentType.lowercase()
        if (!lower.contains("multipart/")) return null
        for (segment in splitParams(contentType)) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            if (!segment.substring(0, eq).trim().equals("boundary", ignoreCase = true)) continue
            val value = segment.substring(eq + 1).trim()
            return value.removeSurrounding("\"").takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * 解析整段请求体。
     *
     * boundary 不满足时抛 [MalformedException]；空 part（只有分隔符没有内容）直接跳过，
     * 客户端多打一个 CRLF 不该让整个上传失败。
     */
    fun parse(body: ByteArray, boundary: String): List<MultipartPart> {
        if (boundary.isEmpty()) throw MalformedException("multipart boundary 为空")
        val delimiter = "--$boundary".toByteArray(Charsets.UTF_8)
        val parts = ArrayList<MultipartPart>()

        var cursor = indexOf(body, delimiter, 0)
        if (cursor < 0) throw MalformedException("请求体里找不到 multipart 分隔符")
        while (true) {
            cursor += delimiter.size
            // 结束分隔符：`--boundary--`
            if (cursor + 1 < body.size && body[cursor] == '-'.code.toByte() && body[cursor + 1] == '-'.code.toByte()) {
                break
            }
            cursor = skipLineBreak(body, cursor)
            if (cursor < 0) break

            val headerEnd = indexOf(body, HEADERS_TERMINATOR, cursor)
            if (headerEnd < 0) throw MalformedException("multipart 头部未结束")
            val headerBlock = body.copyOfRange(cursor, headerEnd).toString(Charsets.UTF_8)
            val contentStart = headerEnd + HEADERS_TERMINATOR.size

            val nextDelimiter = indexOf(body, delimiter, contentStart)
            if (nextDelimiter < 0) throw MalformedException("multipart 缺少结束分隔符")
            // 正文与分隔符之间固定隔一个 CRLF，它不属于内容
            val contentEnd = if (nextDelimiter >= 2 &&
                body[nextDelimiter - 2] == '\r'.code.toByte() && body[nextDelimiter - 1] == '\n'.code.toByte()
            ) {
                nextDelimiter - 2
            } else {
                nextDelimiter
            }
            parts.add(buildPart(headerBlock, body.copyOfRange(contentStart, contentEnd)))
            cursor = nextDelimiter
        }
        return parts
    }

    /** 按名字取第一个字段。 */
    fun find(parts: List<MultipartPart>, name: String): MultipartPart? = parts.firstOrNull { it.name == name }

    private fun buildPart(headerBlock: String, data: ByteArray): MultipartPart {
        var name = ""
        var fileName = ""
        var contentType = ""
        for (rawLine in headerBlock.split("\r\n", "\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when {
                key.equals("Content-Disposition", ignoreCase = true) -> {
                    for (param in splitParams(value)) {
                        val eq = param.indexOf('=')
                        if (eq <= 0) continue
                        val pKey = param.substring(0, eq).trim().lowercase()
                        val pValue = param.substring(eq + 1).trim().removeSurrounding("\"")
                        when (pKey) {
                            "name" -> name = pValue
                            "filename" -> fileName = pValue
                        }
                    }
                }
                key.equals("Content-Type", ignoreCase = true) -> contentType = value
            }
        }
        return MultipartPart(name = name, fileName = fileName, contentType = contentType, data = data)
    }

    /** 按 `;` 切分参数，但跳过引号内的分号（filename 里可能有）。 */
    private fun splitParams(header: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        for (ch in header) {
            when {
                ch == '"' -> {
                    quoted = !quoted
                    current.append(ch)
                }
                ch == ';' && !quoted -> {
                    if (current.isNotEmpty()) result.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    /** 跳过分隔符行尾的 CRLF（或仅 LF）。 */
    private fun skipLineBreak(body: ByteArray, from: Int): Int {
        var cursor = from
        if (cursor < body.size && body[cursor] == '\r'.code.toByte()) cursor++
        if (cursor < body.size && body[cursor] == '\n'.code.toByte()) cursor++
        return if (cursor < body.size) cursor else -1
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val last = haystack.size - needle.size
        var i = from.coerceAtLeast(0)
        while (i <= last) {
            if (haystack[i] == needle[0]) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private val HEADERS_TERMINATOR = "\r\n\r\n".toByteArray(Charsets.UTF_8)

    /** 拼一个 multipart 请求体的字段（测试构造上传请求用）。 */
    class Field(val name: String, val data: ByteArray, val fileName: String = "", val contentType: String = "")

    /** 拼一个完整的 multipart 请求体。 */
    fun build(boundary: String, fields: List<Field>): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in fields) {
            out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            val disposition = if (field.fileName.isEmpty()) {
                "Content-Disposition: form-data; name=\"${field.name}\"\r\n"
            } else {
                "Content-Disposition: form-data; name=\"${field.name}\"; filename=\"${field.fileName}\"\r\n"
            }
            out.write(disposition.toByteArray(Charsets.UTF_8))
            if (field.contentType.isNotEmpty()) {
                out.write("Content-Type: ${field.contentType}\r\n".toByteArray(Charsets.UTF_8))
            }
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.write(field.data)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }
}
