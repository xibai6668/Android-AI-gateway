package dev.aigw.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

/** 明暗模式：跟随系统 / 强制深色 / 强制浅色。 */
enum class ThemeMode { Light, Dark, System }

/**
 * 用户可自定义的外观色。
 *
 * 只包含金色渐变的两端：其余语义色（绿/红/灰等）不随用户改变，
 * 避免自定义配色把错误态、成功态也染成金色而失去语义。
 */
data class AppColors(
    val goldStart: Color,
    val goldEnd: Color,
) {
    /** 金色文字：深金，在浅色底上比亮金更清晰。 */
    val goldText: Color get() = goldEnd

    /** 按钮、激活态等金色渐变画笔。 */
    val goldGradientBrush: Brush get() = Brush.horizontalGradient(listOf(goldStart, goldEnd))

    /** 统计数据六色：首尾两项跟随自定义金，中间四项是固定语义色。 */
    fun statColors(): List<Color> = listOf(
        goldStart,          // 金——可用供应商
        Color(0xFF3B82F6),  // 蓝——账号
        Color(0xFF10B981),  // 绿——可用账号
        Color(0xFFF59E0B),  // 橙——今日请求
        Color(0xFF888888),  // 灰——今日 tokens
        goldEnd,            // 深金——额度
    )
}

/** 默认黑金配色。 */
val defaultAppColors = AppColors(GoldAccent, GoldText)

/** 当前生效的外观色；由 [AiGatewayTheme] 提供，UI 里用 `LocalAppColors.current` 读取。 */
val LocalAppColors = staticCompositionLocalOf { defaultAppColors }

/**
 * 解析 `#RRGGBB` 或 `RRGGBB`（大小写不敏感）为不透明颜色；非法输入返回 null。
 */
fun parseHexColor(input: String): Color? {
    val raw = input.trim().removePrefix("#")
    if (raw.length != 6) return null
    val value = raw.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or value)
}

/** 输出 `#RRGGBB`（大写）。 */
fun formatHex(color: Color): String =
    String.format(Locale.US, "#%06X", color.toArgb() and 0xFFFFFF)
