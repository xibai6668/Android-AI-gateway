package dev.aigw.core.util

import java.util.Calendar

/**
 * 取 [millis] 当天 00:00 的时间戳（按系统默认时区）。
 *
 * 放在 core 的原因：网关状态与界面统计都要按「今天」过滤，两边各写一份 Calendar
 * 逻辑迟早会漂移；而且这里便于单测——直接传时间戳，不依赖真实时钟。
 */
fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
