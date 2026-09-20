package dev.aigw.core.util

/**
 * 限制长度的字符串累积器。
 *
 * 用途：流式响应需要边收边记，但记录只保留前 [limit] 个字符（`CallLogStore` 存盘时也是截断的）。
 * 直接用 `StringBuilder` 会把整段回复留在堆里——一次长回复几十万字符，
 * 在用 `StringBuilder` 时无法回收；超过上限后本类直接丢弃后续内容，占用恒定。
 *
 * 注意：即使已截断，也要继续统计总长度，便于如实告诉用户「实际有多长」。
 */
class CappedStringBuilder(private val limit: Int) {

    private val head = StringBuilder(minOf(limit, 4096).coerceAtLeast(16))

    /** 累积过的总字符数（含被丢弃的部分）。 */
    var totalLength: Long = 0L
        private set

    /** 是否发生过截断。 */
    val truncated: Boolean get() = totalLength > head.length

    fun append(text: CharSequence) {
        if (text.isEmpty()) return
        totalLength += text.length
        val room = limit - head.length
        if (room <= 0) return
        head.append(if (text.length <= room) text else text.subSequence(0, room))
    }

    /** 已保留的内容（最多 [limit] 个字符）。 */
    fun content(): String = head.toString()

    override fun toString(): String = content()
}
