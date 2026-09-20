package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.providers.ProviderUiRegistry

/**
 * 供应商列表：每个供应商一张卡片（图标、说明、账号统计、启用开关），点进详情。
 *
 * 页面本身不认识任何具体供应商——图标与说明都由各自的 [ProviderUi] 组件提供。
 */
@Composable
fun ProviderListScreen(
    state: AppUiState,
    onOpen: (String) -> Unit,
    onAddCustom: () -> Unit,
) {
    PageScaffold(
        title = "供应商",
        subtitle = "共 ${state.providers.size} 个",
        actions = {
            Icon(
                Icons.Filled.Add,
                contentDescription = "添加自定义供应商",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAddCustom).padding(6.dp),
            )
        },
    ) {
        if (state.providers.isEmpty()) {
            SectionCard {
                EmptyHint("还没有供应商", "内置供应商会在启动时自动登记；自定义供应商可点右上角「+」添加。")
            }
            return@PageScaffold
        }
        for ((index, provider) in state.providers.withIndex()) {
            val ui = ProviderUiRegistry.of(provider.id)
            SectionCard(modifier = Modifier.staggeredAppear(index).clickable { onOpen(provider.id) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ui?.Icon(Modifier.size(28.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = ui?.description() ?: provider.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "账号 ${provider.accountCount} · 可用 ${provider.usableCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
