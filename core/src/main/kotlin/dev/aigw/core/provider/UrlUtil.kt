package dev.aigw.core.provider

/** 从 URL 查询串里取参数（不依赖任何平台 API）。 */
fun queryParam(url: String, name: String): String? {
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
        val index = pair.indexOf('=')
        if (index <= 0) continue
        if (pair.substring(0, index) == name) return pair.substring(index + 1)
    }
    return null
}
