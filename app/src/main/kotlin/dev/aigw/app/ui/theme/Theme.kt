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

// 黑金极简设计规范 Token
val GoldAccent = Color(0xFFD4A843)
val GoldText = Color(0xFFB88A20)
val HairlineBorder = Color(0xFFEAEAEA)

private val LightScheme = lightColorScheme(
    primary = GoldAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBF6EA),
    onPrimaryContainer = Color(0xFF6B4E0E),
    secondary = GoldText,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5F5F7),
    onSecondaryContainer = Color(0xFF0D0D0D),
    tertiary = Color(0xFF10B981),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0FDF4),
    onTertiaryContainer = Color(0xFF166534),
    background = Color(0xFFFCFCFD),
    onBackground = Color(0xFF0D0D0D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF8F8FA),
    onSurfaceVariant = Color(0xFF777777),
    surfaceContainerHighest = Color(0xFFF0F0F2),
    outline = HairlineBorder,
    outlineVariant = Color(0xFFEDEDED),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
)

private val DarkScheme = darkColorScheme(
    primary = GoldAccent,
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFF2A2210),
    onPrimaryContainer = Color(0xFFE5C158),
    secondary = GoldText,
    onSecondary = Color(0xFF141414),
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color(0xFFF5F5F5),
    tertiary = Color(0xFF10B981),
    onTertiary = Color(0xFF141414),
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFF999999),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun AiGatewayTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // 必须 remember：dynamicXxxColorScheme() 每次调用都会重新读取系统资源，
    // 而主题在每次重组时都会被求值——不缓存就是持续的重复分配。
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

/** Hero 黑金渐变（#1A1A1A → #080808）。 */
val HeroGradient: List<Color> = listOf(Color(0xFF1A1A1A), Color(0xFF080808))

/** 可直接用于 Modifier.background(brush) 的渐变画笔。 */
val HeroBrush: Brush get() = Brush.linearGradient(HeroGradient)

/** 按钮、激活态等金色渐变画笔（#D4A843 → #B88A20）。 */
val GradientBrush: Brush get() = Brush.horizontalGradient(listOf(Color(0xFFD4A843), Color(0xFFB88A20)))

/** 统计数据六色，与黑金体系保持协调。 */
val StatColors: List<Color> = listOf(
    Color(0xFFD4A843), // 金——可用供应商
    Color(0xFF3B82F6), // 蓝——账号
    Color(0xFF10B981), // 绿——可用账号
    Color(0xFFF59E0B), // 橙——今日请求
    Color(0xFF888888), // 灰——今日 tokens
    Color(0xFFB88A20), // 深金——额度
)
