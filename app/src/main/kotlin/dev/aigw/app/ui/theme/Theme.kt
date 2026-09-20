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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// 配色对齐参考稿：近白的冷灰底 + 白卡片 + 蓝色主色 + 绿色「运行中」状态色
private val Blue = Color(0xFF1E7BF0)
private val BlueContainer = Color(0xFFDCE9FF)
private val Green = Color(0xFF12A150)
private val GreenContainer = Color(0xFFDCF6E6)

private val LightScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = Color(0xFF0B2B58),
    secondary = Color(0xFF4A6A97),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EAF8),
    onSecondaryContainer = Color(0xFF17324F),
    tertiary = Green,
    onTertiary = Color.White,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = Color(0xFF06421F),
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF191C20),
    surface = Color.White,
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF5D6470),
    surfaceContainerHighest = Color(0xFFE9EEF5),
    outline = Color(0xFFB9C2CE),
    outlineVariant = Color(0xFFE4EAF2),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF14487E),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB7C8E6),
    secondaryContainer = Color(0xFF2E4663),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFF7FD9A6),
    tertiaryContainer = Color(0xFF00522C),
    onTertiaryContainer = Color(0xFF9DF6C2),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF191C20),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    outlineVariant = Color(0xFF42474E),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
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

/** 首页顶部那张主卡用的蓝色渐变。 */
val HeroGradient: List<Color> = listOf(Color(0xFF4B9CF8), Color(0xFF1E6FE8))
