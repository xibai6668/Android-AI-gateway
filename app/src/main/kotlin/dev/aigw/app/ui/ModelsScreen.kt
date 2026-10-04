package dev.aigw.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 模型页：汇总所有供应商的模型（id 带 `provider/` 前缀），按供应商分组。 */
@Composable
fun ModelsScreen(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 按 routePrefix 分组：区域型供应商（如 WorkBuddy 国内与国外）各成一个独立卡片
    val grouped = state.models.groupBy { it.routePrefix }

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
            SectionCard(enterIndex = 0) {
                Text(
                    text = "拉取模型目录时有报错：${state.modelsError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (grouped.isEmpty()) {
            SectionCard(enterIndex = 1) {
                EmptyHint("还没有模型", "点右上角刷新；无可用账号的供应商可能不展示动态模型。")
            }
            return@PageScaffold
        }

        for ((routePrefix, models) in grouped) {
            val title = models.firstOrNull()?.providerName ?: routePrefix
            SectionCard(enterIndex = 2) {
                SectionLabel(title)
                Text(
                    text = "点击模型名可复制全名（$routePrefix/<模型 id>）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (model in models) {
                    val testStatus = state.modelTestLatencies[model.fullId]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 左侧模型名与信息：点击复制模型名称
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    clipboard.setText(AnnotatedString(model.fullId))
                                    Toast.makeText(context, "已复制模型名称：${model.fullId}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = model.fullId,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "复制",
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(13.dp),
                                )
                            }
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

                        // 按钮左侧显示延迟：<5000ms 绿色，>=5000ms 橙色，失败/超时 红色
                        when (testStatus) {
                            is ModelTestStatus.Testing -> {
                                Text(
                                    text = "测试中...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            is ModelTestStatus.Success -> {
                                val isFast = testStatus.latencyMs < 5000
                                Text(
                                    text = "${testStatus.latencyMs}ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isFast) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                                )
                            }
                            is ModelTestStatus.Timeout -> {
                                Text(
                                    text = "超时",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            null -> Unit
                        }

                        // 右侧 Q 弹测试按钮
                        Box(
                            modifier = Modifier
                                .bouncyClick(enabled = testStatus !is ModelTestStatus.Testing) {
                                    viewModel.testModel(model)
                                }
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "测试",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}
