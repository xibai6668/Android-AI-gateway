package dev.aigw.app.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.app.ui.theme.ThemeMode
import dev.aigw.app.ui.theme.formatHex
import dev.aigw.app.ui.theme.parseHexColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 页面底色预设：hex 为 null 表示不覆盖（用默认底色）。 */
private data class BgPreset(val label: String, val hex: String?)

private val bgPresets = listOf(
    BgPreset("默认金", null),
    BgPreset("冷银灰", "#F7F7F8"),
    BgPreset("暖象牙", "#FBF9F5"),
    BgPreset("曜石黑", "#121214"),
    BgPreset("深空黑", "#0A0A0B"),
)

/** 色相环尺寸参数（统一在此定义，保证拖动命中与绘制一致）。 */
private val WheelSize = 120.dp
private val WheelStroke = 12.dp
private val WheelThumb = 10.dp

/** 外观主题二级页：明暗模式、配色方案、金色文字、动态取色。 */
@Composable
fun AppearanceThemeScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val gold = LocalAppColors.current.goldStart

    PageScaffold(
        title = "外观主题",
        subtitle = "明暗模式 · 配色方案 · 金色文字",
        leading = { BackButton(onBack) },
    ) {
        // ---------------------------------------------------------- 01 明暗模式
        EditorialGroupHeader("01", "明暗模式 · DISPLAY MODE", meta = "RADIO LIST")
        SectionCard(enterIndex = 0) {
            ModeRow("自动 · 跟随系统", "跟随 Android 系统的深浅色设置自适应切换", state.themeMode == ThemeMode.System) {
                viewModel.setThemeMode(ThemeMode.System)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ModeRow("深色模式", "经典黑金暗色调，适合弱光与长时夜间使用", state.themeMode == ThemeMode.Dark) {
                viewModel.setThemeMode(ThemeMode.Dark)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ModeRow("浅色模式", "极简明亮排版，高对比度呈现黑金理性层次", state.themeMode == ThemeMode.Light) {
                viewModel.setThemeMode(ThemeMode.Light)
            }
        }

        // ---------------------------------------------------------- 02 配色方案
        EditorialGroupHeader("02", "配色方案 · COLOR PRESETS", meta = "CAPSULE CHIPS")
        SectionCard(enterIndex = 1) {
            Text(
                text = "预设黑金系调色基调，切换后仅替换页面底色（卡片/表面色不变）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val currentHex = state.backgroundOverride?.let { formatHex(it) }
                bgPresets.forEach { preset ->
                    PresetChip(
                        label = preset.label,
                        dotColor = preset.hex?.let { parseHexColor(it) } ?: gold,
                        selected = currentHex == preset.hex,
                        onClick = { viewModel.setBackgroundOverride(preset.hex) },
                    )
                }
            }
            HexField(
                value = state.backgroundOverride?.let { formatHex(it) } ?: "",
                label = "自定义页面底色",
                onCommit = { viewModel.setBackgroundOverride(it) },
                onClear = if (state.backgroundOverride != null) {
                    { viewModel.setBackgroundOverride(null) }
                } else {
                    null
                },
                swatch = state.backgroundOverride,
            )
        }

        // ---------------------------------------------------------- 03 金色文字
        EditorialGroupHeader("03", "金色文字 · GOLD GRADIENT ACCENT", meta = "DUAL HUE WHEELS")
        SectionCard(enterIndex = 2) {
            Text(
                text = "应用主色渐变由起始色与结束色插值生成，可拖动色相环或直接输入代码：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WheelColumn(
                    title = "起始色 · START",
                    color = state.goldStart,
                    modifier = Modifier.weight(1f),
                    onPick = { viewModel.setGoldColors(formatHex(it), formatHex(state.goldEnd)) },
                )
                WheelColumn(
                    title = "结束色 · END",
                    color = state.goldEnd,
                    modifier = Modifier.weight(1f),
                    onPick = { viewModel.setGoldColors(formatHex(state.goldStart), formatHex(it)) },
                )
            }
            GradientPreview(state.goldStart, state.goldEnd)
        }

        // ---------------------------------------------------------- 04 动态取色
        EditorialGroupHeader("04", "动态取色 · MATERIAL YOU", meta = "SYSTEM OVERRIDE")
        SectionCard(enterIndex = 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("动态取色", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "从系统壁纸提取配色，开启后配色方案不再生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.dynamicColor,
                    onCheckedChange = { viewModel.setDynamicColor(it) },
                )
            }
        }

        // ---------------------------------------------------------- 底部操作
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale()
                .clip(MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.medium)
                .clickable { viewModel.resetAppearance() }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "重置默认",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 单选行：选中=金色文字 + 金色对勾；未选中=黑色文字 + 空心圈。 */
@Composable
private fun ModeRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val gold = LocalAppColors.current.goldStart
    val goldText = LocalAppColors.current.goldText
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) goldText else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(gold),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                Box(Modifier.size(18.dp).clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
    }
}

/** 配色预设胶囊：色点 + 名称；选中时金色描边 + 浅金底。 */
@Composable
private fun PresetChip(label: String, dotColor: Color, selected: Boolean, onClick: () -> Unit) {
    val gold = LocalAppColors.current.goldStart
    val goldText = LocalAppColors.current.goldText
    Row(
        modifier = Modifier
            .pressScale()
            .clip(CircleShape)
            .background(if (selected) gold.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) gold else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) goldText else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 等宽 HEX 输入框：满 6 位合法即提交；可选色彩示意块与「清除」入口。 */
@Composable
private fun HexField(
    value: String,
    label: String,
    onCommit: (String) -> Unit,
    onClear: (() -> Unit)? = null,
    swatch: Color? = null,
) {
    var text by remember(value) { mutableStateOf(value.removePrefix("#").uppercase()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val cleaned = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                text = cleaned
                if (cleaned.length == 6 && parseHexColor(cleaned) != null) onCommit("#$cleaned")
            },
            label = { Text(label) },
            prefix = { Text("#", fontFamily = FontFamily.Monospace) },
            leadingIcon = if (swatch != null) {
                { Box(Modifier.size(20.dp).clip(MaterialTheme.shapes.extraSmall).background(swatch)) }
            } else {
                null
            },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
            modifier = Modifier.fillMaxWidth(),
        )
        if (onClear != null) {
            Text(
                text = "清除覆盖",
                style = MaterialTheme.typography.labelSmall,
                color = LocalAppColors.current.goldText,
                modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** 单个色相环卡片：标题 + 色相环 + HEX 输入框。 */
@Composable
private fun WheelColumn(
    title: String,
    color: Color,
    modifier: Modifier,
    onPick: (Color) -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(MaterialTheme.shapes.extraSmall).background(color))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${hueOf(color)}°",
                style = MaterialTheme.typography.labelSmall,
                color = LocalAppColors.current.goldText,
            )
        }
        HueWheel(color = color, onPick = onPick)
        HexField(value = formatHex(color), label = "HEX", onCommit = { onPick(requireColor(it)) })
    }
}

/** 色相环：角度映射色相 H、半径比例映射饱和度 S，明度固定 1；可拖动或点击取色。 */
@Composable
private fun HueWheel(color: Color, onPick: (Color) -> Unit) {
    val (hue, saturation) = hueSatOf(color)
    val hueColors = remember { List(13) { Color.hsv(it * 30f, 1f, 1f) } }
    Canvas(
        modifier = Modifier
            .size(WheelSize)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onPick(colorAt(offset, size.width, size.height, density)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onPick(colorAt(change.position, size.width, size.height, density))
                }
            },
    ) {
        val stroke = WheelStroke.toPx()
        val thumbR = WheelThumb.toPx()
        val ringR = (minOf(size.width, size.height) / 2f - thumbR - stroke / 2f).coerceAtLeast(1f)
        val center = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            brush = Brush.sweepGradient(hueColors),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(center.x - ringR, center.y - ringR),
            size = Size(ringR * 2f, ringR * 2f),
            style = Stroke(width = stroke),
        )
        val angleRad = Math.toRadians(hue.toDouble())
        val dist = (saturation.coerceIn(0f, 1f)) * ringR
        val thumb = Offset(
            center.x + (dist * cos(angleRad)).toFloat(),
            center.y + (dist * sin(angleRad)).toFloat(),
        )
        drawCircle(Color.White, thumbR, thumb)
        drawCircle(Color(0xFF0D0D0D), thumbR, thumb, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color, thumbR * 0.45f, thumb)
    }
}

/** START→END 渐变预览条 + 两端代码标注 + 渐变文字实测。 */
@Composable
private fun GradientPreview(start: Color, end: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(start, end))),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "0% START (${formatHex(start)})",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = start,
            )
            Text(
                text = "100% END (${formatHex(end)})",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = end,
            )
        }
        Text(
            text = "AI-GATEWAY · PRESTIGE 黑金渐变文字",
            style = MaterialTheme.typography.headlineSmall.copy(
                brush = Brush.horizontalGradient(listOf(start, end)),
                fontWeight = FontWeight.Light,
                letterSpacing = 1.sp,
            ),
        )
    }
}

/** 触点 → 颜色：角度映射 H，半径比例映射 S，V 固定 1。[density] 为像素密度（同 PointerInputScope.density）。 */
private fun colorAt(offset: Offset, width: Int, height: Int, density: Float): Color {
    val cx = width / 2f
    val cy = height / 2f
    val dx = offset.x - cx
    val dy = offset.y - cy
    val thumbR = WheelThumb.value * density
    val stroke = WheelStroke.value * density
    val ringR = (minOf(width, height) / 2f - thumbR - stroke / 2f).coerceAtLeast(1f)
    val angle = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()) + 360f) % 360f
    val saturation = (hypot(dx, dy) / ringR).coerceIn(0f, 1f)
    return Color.hsv(angle, saturation, 1f)
}

/** 取颜色的 HSV 色相与饱和度（0..360 / 0..1）。 */
private fun hueSatOf(color: Color): Pair<Float, Float> {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    return hsv[0] to hsv[1]
}

private fun hueOf(color: Color): Int = hueSatOf(color).first.toInt()

/** 已校验过的 HEX → 颜色；由 [parseHexColor] 保证非空。 */
private fun requireColor(hex: String): Color = parseHexColor(hex) ?: Color.Unspecified
