package dev.aigw.app.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 模型页：汇总所有供应商的模型（id 带 `provider/` 前缀），按供应商分组展示与流式更新。 */
@Composable
fun ModelsScreen(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 按 routePrefix 分组：区域型供应商（如 WorkBuddy 国内与国外）各成一个独立卡片
    val grouped = state.models.groupBy { it.routePrefix }

    // 刷新按钮旋转动效
    val infiniteTransition = rememberInfiniteTransition(label = "refreshSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinAngle",
    )
    val iconRotation by animateFloatAsState(
        targetValue = if (state.modelsLoading) spinAngle else 0f,
        label = "refreshIconRotation",
    )

    // 动态副标题：流式加载中展示进度
    val totalProviders = state.modelLoadStates.size
    val doneProviders = state.modelLoadStates.values.count { it !is ModelLoadState.Loading }
    val subtitleText = if (state.modelsLoading) {
        if (totalProviders > 0) "共 ${state.models.size} 个 · 载入中 ($doneProviders/$totalProviders)" else "共 ${state.models.size} 个 · 更新中..."
    } else {
        "共 ${state.models.size} 个"
    }

    PageScaffold(
        title = "模型",
        subtitle = subtitleText,
        actions = {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .bouncyClick { viewModel.refreshModels() }
                    .padding(6.dp)
                    .graphicsLayer { rotationZ = iconRotation },
            )
        },
    ) {
        // 顶部细条流式进度指示
        AnimatedVisibility(
            visible = state.modelsLoading,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            )
        }

        if (state.modelsError.isNotEmpty()) {
            SectionCard(enterIndex = 0) {
                Text(
                    text = "拉取模型目录时有报错：${state.modelsError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 还在加载中的供应商列表
        val pendingProviders = state.modelLoadStates.filter { it.value is ModelLoadState.Loading }.keys

        if (grouped.isEmpty()) {
            if (state.modelsLoading) {
                // 首屏流式载入中骨架屏
                val loadingList = pendingProviders.ifEmpty { listOf("loading_1", "loading_2") }
                for (id in loadingList) {
                    val name = state.providerOf(id)?.displayName ?: "正在拉取模型目录"
                    ModelSkeletonCard(providerName = name)
                }
            } else {
                SectionCard(enterIndex = 1) {
                    EmptyHint("还没有模型", "点右上角刷新；无可用账号的供应商可能不展示动态模型。")
                }
            }
            return@PageScaffold
        }

        // 已加载的模型按组展示，带平滑展开与淡入动效
        for ((routePrefix, models) in grouped) {
            val title = models.firstOrNull()?.providerName ?: routePrefix
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(240)) + expandVertically(tween(240)),
            ) {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel(title)
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${models.size} 款",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }

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

        // 若已有部分模型展示，而剩余供应商仍在加载中，在下方继续展示该供应商的骨架占位
        if (state.modelsLoading && pendingProviders.isNotEmpty()) {
            for (id in pendingProviders) {
                val name = state.providerOf(id)?.displayName ?: id
                ModelSkeletonCard(providerName = name)
            }
        }
    }
}

/** 骨架屏占位卡片：配合微光动效呈现正在流式拉取的状态。 */
@Composable
private fun ModelSkeletonCard(
    providerName: String,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = providerName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "加载中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        repeat(2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(16.dp)
                            .shimmer(RoundedCornerShape(4.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(12.dp)
                            .shimmer(RoundedCornerShape(3.dp)),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 26.dp)
                        .shimmer(MaterialTheme.shapes.small),
                )
            }
        }
    }
}
