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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aigw.core.provider.RoutedModel

/** 模型页：汇总所有供应商的模型（id 带 `provider/` 前缀），按供应商分组展示与流式更新。 */
@Composable
fun ModelsScreen(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 按 routePrefix 分组：区域型供应商（如 WorkBuddy 国内与国外）各成一个分组
    val grouped = remember(state.models) { state.models.groupBy { it.routePrefix } }

    // 刷新按钮旋转动效：仅在加载中才创建无限过渡，停止后不空转每帧插值
    val spinAngle = if (state.modelsLoading) {
        val transition = rememberInfiniteTransition(label = "refreshSpin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "spinAngle",
        )
        angle
    } else {
        0f
    }
    val iconRotation by animateFloatAsState(
        targetValue = spinAngle,
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

    // 还在加载中的供应商列表
    val pendingProviders = state.modelLoadStates.filter { it.value is ModelLoadState.Loading }.keys

    // 本页自带骨架（不走 PageScaffold）：模型量大，LazyColumn 懒加载避免整页一次性组合；
    // PageScaffold 的 verticalScroll 与 LazyColumn 不兼容（无限高度约束会崩）。
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(
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
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
                SectionCard(enterIndex = 0, modifier = Modifier.padding(top = 14.dp)) {
                    Text(
                        text = "拉取模型目录时有报错：${state.modelsError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (grouped.isEmpty()) {
                if (state.modelsLoading) {
                    // 首屏流式载入中骨架屏
                    val loadingList = pendingProviders.toList().ifEmpty { listOf("loading_1", "loading_2") }
                    items(loadingList, key = { "skeleton-$it" }) { id ->
                        val name = state.providerOf(id)?.displayName ?: "正在拉取模型目录"
                        ModelSkeletonCard(providerName = name)
                    }
                } else {
                    item(key = "empty") {
                        SectionCard(enterIndex = 1) {
                            EmptyHint("还没有模型", "点右上角刷新；无可用账号的供应商可能不展示动态模型。")
                        }
                    }
                }
            } else {
                // 已加载的模型按组展示
                for ((routePrefix, models) in grouped) {
                    val title = models.firstOrNull()?.providerName ?: routePrefix
                    item(key = "header-$routePrefix") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
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
                    }
                    items(models, key = { it.fullId }) { model ->
                        val testStatus = state.modelTestLatencies[model.fullId]
                        ModelRow(
                            model = model,
                            testStatus = testStatus,
                            onCopy = {
                                clipboard.setText(AnnotatedString(model.fullId))
                                Toast.makeText(context, "已复制模型名称：${model.fullId}", Toast.LENGTH_SHORT).show()
                            },
                            onTest = { viewModel.testModel(model) },
                        )
                    }
                }

                // 若已有部分模型展示，而剩余供应商仍在加载中，在下方继续展示该供应商的骨架占位
                if (state.modelsLoading && pendingProviders.isNotEmpty()) {
                    items(pendingProviders.toList(), key = { "skeleton-$it" }) { id ->
                        val name = state.providerOf(id)?.displayName ?: id
                        ModelSkeletonCard(providerName = name)
                    }
                }
            }
        }
    }
}

/** 单个模型行：左侧模型 id 与信息（点击复制），中部测速状态，右侧测速按钮。 */
@Composable
private fun ModelRow(
    model: RoutedModel,
    testStatus: ModelTestStatus?,
    onCopy: () -> Unit,
    onTest: () -> Unit,
) {
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
                .clickable(onClick = onCopy)
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
            is ModelTestStatus.Failed -> {
                Text(
                    text = "失败：${testStatus.reason.take(60)}${if (testStatus.reason.length > 60) "…" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
            null -> Unit
        }

        // 右侧 Q 弹测试按钮
        Box(
            modifier = Modifier
                .bouncyClick(enabled = testStatus !is ModelTestStatus.Testing, onClick = onTest)
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

/** 骨架屏占位卡片：配合微光动效呈现正在流式拉取的状态。 */
@Composable
private fun ModelSkeletonCard(
    providerName: String,
    modifier: Modifier = Modifier,
) {
    // 整卡共用一个动画进度：每个占位各建一套无限过渡会让加载期间成倍触发重组
    val shimmerProgress = rememberShimmerProgress()
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
                            .shimmer(shimmerProgress, RoundedCornerShape(4.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(12.dp)
                            .shimmer(shimmerProgress, RoundedCornerShape(3.dp)),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 26.dp)
                        .shimmer(shimmerProgress, MaterialTheme.shapes.small),
                )
            }
        }
    }
}
