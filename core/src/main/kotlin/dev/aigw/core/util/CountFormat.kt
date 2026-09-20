package dev.aigw.core.util

import java.util.Locale

/**
 * 大数字压缩显示（如 23200 → "23.2K"），用于统计行的窄格子。
 *
 * 放在 core 而不是 UI 层，是因为这属于纯格式化逻辑，可以脱离 Android 直接单测——
 * 界面上的换行/撑破问题往往就出在这里。
 */
fun formatCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> trimTrailingZero(value / 1_000.0) + "K"
    else -> trimTrailingZero(value / 1_000_000.0) + "M"
}

/**
 * `%.1f` 后去掉多余的 `.0`。
 *
 * 固定用 [Locale.US]：小数点为逗号的地区（如德语）会把 3.2 格式化成 "3,2"。
 */
private fun trimTrailingZero(value: Double): String =
    "%.1f".format(Locale.US, value).removeSuffix(".0")
