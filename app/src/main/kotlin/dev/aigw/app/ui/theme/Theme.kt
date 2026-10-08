package dev.aigw.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// 方案B：渐变柔和现代风——紫粉渐变主色 + 极淡薰衣草背景 + 翡翠绿状态色
private val Purple = Color(0xFF7C3AED)
private val PurpleContainer = Color(0xFFEDE9FE)
private val Pink = Color(0xFFEC4899)
private val Violet = Color(0xFFA855F7)
private val Green = Color(0xFF10B981)
private val GreenContainer = Color(0xFFD1FAE5)

private val LightScheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleContainer,
    onPrimaryContainer = Color(0xFF3B0764),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E8FF),
    onSecondaryContainer = Color(0xFF581C87),
    tertiary = Green,
    onTertiary = Color.White,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = Color(0xFF064E3B),
    background = Color(0xFFF6F0FE),
    onBackground = Color(0xFF1A0B2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A0B2E),
    surfaceVariant = Color(0xFFF3E8FF),
    onSurfaceVariant = Color(0xFF6B21A8),
    surfaceContainerHighest = Color(0xFFEDE9FE),
    outline = Color(0xFFD8B4FE),
    outlineVariant = Color(0xFFE9D5FF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC4B5FD),
    onPrimary = Color(0xFF3B0764),
    primaryContainer = Color(0xFF5B21B6),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFFD8B4FE),
    onSecondary = Color(0xFF581C87),
    secondaryContainer = Color(0xFF6D28D9),
    onSecondaryContainer = Color(0xFFF3E8FF),
    tertiary = Color(0xFF6EE7B7),
    onTertiary = Color(0xFF064E3B),
    tertiaryContainer = Color(0xFF065F46),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF120D1F),
    onBackground = Color(0xFFEDE9FE),
    surface = Color(0xFF1E1530),
    onSurface = Color(0xFFEDE9FE),
    surfaceVariant = Color(0xFF3B2A58),
    onSurfaceVariant = Color(0xFFD8B4FE),
    outlineVariant = Color(0xFF3B2A58),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AiGatewayTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // 必须 remember：dynamicXxxColorScheme() 每次调用都会重新计算整套配色
    // （会读取系统资源），而主题在每次重组时都会被求值——不缓存就是持续的重复分配。
    val scheme = remember(dynamicColor, dark, context) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> DarkScheme
            else -> LightScheme
        }
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}

/** 首页 Hero 横幅卡片的紫粉渐变（左上→右下）。 */
val HeroGradient: List<Color> = listOf(Color(0xFF7C3AED), Color(0xFFA855F7), Color(0xFFEC4899))

/** 可直接用于 Modifier.background(brush) 的渐变画笔。 */
val HeroBrush: Brush get() = Brush.linearGradient(HeroGradient)

/** 按钮、激活态等小面积渐变画笔（同色系，方向从左到右）。 */
val GradientBrush: Brush get() = Brush.horizontalGradient(HeroGradient)

/** 统计数据六色：可用供应商/账号/可用账号/今日请求/今日tokens/额度，与设计稿对齐。 */
val StatColors: List<Color> = listOf(
    Color(0xFF8B5CF6), // 紫——可用供应商
    Color(0xFF3B82F6), // 蓝——账号
    Color(0xFF10B981), // 绿——可用账号
    Color(0xFFF59E0B), // 橙——今日请求
    Color(0xFFEC4899), // 粉——今日 tokens
    Color(0xFF6366F1), // 靛青——额度
)
