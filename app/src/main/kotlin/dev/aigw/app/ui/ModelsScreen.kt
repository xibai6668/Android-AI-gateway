package dev.aigw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding

/** 模型页：汇总所有供应商的模型（id 带 `provider/` 前缀），按供应商分组。 */
@Composable
fun ModelsScreen(state: AppUiState, viewModel: AppViewModel) {
    val grouped = state.models.groupBy { it.providerId }

    PageScaffold(
        title = "模型",
        subtitle = "共 ${state.models.size} 个",
        actions = {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { viewModel.refreshModels() }.padding(6.dp),
            )
        },
    ) {
        if (state.modelsError.isNotEmpty()) {
            SectionCard {
                Text(
                    text = "拉取模型目录时有报错：${state.modelsError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (grouped.isEmpty()) {
            SectionCard {
                EmptyHint("还没有模型", "点右上角刷新；没有可用账号的供应商会退回内置快照。")
            }
            return@PageScaffold
        }

        for ((providerId, models) in grouped) {
            val info = state.providerOf(providerId)
            SectionCard {
                SectionLabel(info?.displayName ?: providerId)
                Text(
                    text = "调用时模型名要写全：$providerId/<模型 id>",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (model in models) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = model.fullId,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = buildString {
                                    append(model.model.name)
                                    if (model.model.contextWindow > 0) {
                                        append(" · 上下文 ${model.model.contextWindow}")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
