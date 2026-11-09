package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.app.ui.theme.ThemeMode

/**
 * 主题设置：把外观相关的开关与配色集中到一处。
 *
 * 只放「改了立刻看得见」的项；接入设置、安全防护这类功能开关仍留在「我的」首页。
 */
@Composable
fun ThemeSettingsScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onOpenColors: () -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold(
        title = "主题设置",
        subtitle = "明暗模式 · 配色 · 底栏样式",
        leading = { BackButton(onBack) },
    ) {
        EditorialGroupHeader("01", "明暗模式")
        SectionCard(enterIndex = 0) {
            ModeChoice("跟随系统", state.themeMode == ThemeMode.System) { viewModel.setThemeMode(ThemeMode.System) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ModeChoice("深色", state.themeMode == ThemeMode.Dark) { viewModel.setThemeMode(ThemeMode.Dark) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ModeChoice("浅色", state.themeMode == ThemeMode.Light) { viewModel.setThemeMode(ThemeMode.Light) }
        }

        EditorialGroupHeader("02", "配色")
        SectionCard(enterIndex = 1) {
            SwitchRow(
                title = "动态取色",
                subtitle = "跟随系统壁纸生成配色，仅 Android 12 及以上生效",
                checked = state.dynamicColor,
                onCheckedChange = { viewModel.setDynamicColor(it) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { viewModel.resetAppearance() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("恢复默认外观", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "明暗模式、页面底色、金色渐变与底栏样式一并还原",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "重置",
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalAppColors.current.goldText,
                )
            }
        }

        EditorialGroupHeader("03", "底栏")
        SectionCard(enterIndex = 2) {
            SwitchRow(
                title = "悬浮底栏",
                subtitle = "导航栏改为浮在内容上的圆角卡片，四周留白",
                checked = state.floatingNavBar,
                onCheckedChange = { viewModel.setFloatingNavBar(it) },
            )
        }

        // 配色细节（底色、金色渐变）沿用原有的视觉编辑页
        EditorialGroupHeader("04", "自定义颜色")
        SectionCard(enterIndex = 3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenColors)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("页面底色与金色渐变", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "色相环取色、HEX 输入与底色预设",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "→",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAppColors.current.goldStart,
                )
            }
        }
    }
}

/** 单选行：标题 + 右侧选中块。 */
@Composable
private fun ModeChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    val gold = LocalAppColors.current.goldStart
    val goldText = LocalAppColors.current.goldText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) goldText else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        if (selected) {
                            Modifier.background(gold)
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        },
                    ),
            )
        }
    }
}

/** 开关行：标题 + 说明 + 右侧 Switch。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
