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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    val exposure = state.modelExposure
    // 折叠状态：记「被折叠」的分组（默认全展开）。
    //
    // 用不可变 Set + rememberSaveable，而不是 SnapshotStateList：
    // 后者在保存时机（onPause / 进程回收）被 SaveableStateRegistry 读取时，
    // 若正好与 LazyColumn 的组合读快照交叠，会抛「Reading a state that was created
    // after the snapshot was taken」——折叠的分组越多越容易撞上，表现为点折叠直接闪退。
    // 换成纯值 Set 后 save/restore 不碰快照，问题消失；读取仍在组合作用域，
    // 折叠变化会正常触发列表重建。
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // 测试失败原文可能很长，行内只展示截断提示，全文点开弹窗查看
    var failureDetail by remember { mutableStateOf<String?>(null) }

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
                ExposureToggle(
                    enabled = exposure.filterEnabled,
                    onClick = {
                        viewModel.updateModelExposure(exposure.copy(filterEnabled = !exposure.filterEnabled))
                    },
                )
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .bouncyClick { viewModel.refreshModels(forceRefresh = true) }
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
            // 悬浮底栏浮在内容之上：底部要额外让出底栏高度，否则最后一项被完全盖住
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 20.dp + LocalBottomContentPadding.current,
            ),
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
                            EmptyHint(
                                title = "还没有模型",
                                message = "无可用账号的供应商不会返回动态模型；可以先刷新试试。",
                                actionLabel = "重新拉取",
                                onAction = { viewModel.refreshModels(forceRefresh = true) },
                            )
                        }
                    }
                }
            } else {
                // 已加载的模型按组展示
                for ((routePrefix, models) in grouped) {
                    val title = models.firstOrNull()?.providerName ?: routePrefix
                    val allExposed = models.isNotEmpty() && models.all { exposure.allows(it.fullId) }
                    // 这一组本轮没拉到上游、用的是上次成功的清单：明确标注，不能让旧清单冒充实时结果
                    val cachedGroup = models.isNotEmpty() && models.all { it.cached }
                    item(key = "header-$routePrefix") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    collapsed = if (routePrefix in collapsed) {
                                        collapsed - routePrefix
                                    } else {
                                        collapsed + routePrefix
                                    }
                                }
                                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (routePrefix in collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                contentDescription = if (routePrefix in collapsed) "展开" else "折叠",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            SectionLabel(title)
                            if (cachedGroup) {
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                                        .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(
                                        Modifier.breathingDot(MaterialTheme.colorScheme.error, size = 6.dp),
                                    )
                                    Text(
                                        text = "缓存",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            } else {
                                Box(Modifier.breathingDot(MaterialTheme.colorScheme.tertiary, size = 6.dp))
                            }
                            Box(Modifier.weight(1f))
                            if (exposure.filterEnabled) {
                                Text(
                                    text = if (allExposed) "取消全选" else "全选",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            viewModel.toggleGroupExposed(routePrefix, !allExposed)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
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
                    // 折叠的分组跳过其模型行：只渲染分组头，模型行即时收起
                    if (routePrefix in collapsed) continue
                    items(models, key = { it.fullId }) { model ->
                        val testStatus = state.modelTestLatencies[model.fullId]
                        ModelRow(
                            model = model,
                            testStatus = testStatus,
                            exposureEnabled = exposure.filterEnabled,
                            exposed = exposure.allows(model.fullId),
                            onToggleExposed = { viewModel.toggleModelExposed(model.fullId, it) },
                            onCopy = {
                                clipboard.setText(AnnotatedString(model.fullId))
                                Toast.makeText(context, "已复制模型名称：${model.fullId}", Toast.LENGTH_SHORT).show()
                            },
                            onTest = { viewModel.testModel(model) },
                            onShowFailure = { failureDetail = it },
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

        failureDetail?.let { reason ->
            AlertDialog(
                onDismissRequest = { failureDetail = null },
                title = { Text("测试失败原因") },
                text = {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { failureDetail = null }) { Text("关闭") }
                },
            )
        }
    }
}

/**
 * 「开放名单」开关：点击切换是否只对外暴露勾选的模型。
 *
 * 开启时用主色胶囊 + 填充圆点区分，关闭时用描边中性样式。
 */
@Composable
private fun ExposureToggle(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = 1.dp,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .bouncyClick(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                ),
        )
        Text(
            text = if (enabled) "开放名单 开" else "开放名单",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单个模型行：左侧模型 id 与信息（点击复制），中部测速状态，右侧测速按钮。 */
@Composable
private fun ModelRow(
    model: RoutedModel,
    testStatus: ModelTestStatus?,
    exposureEnabled: Boolean,
    exposed: Boolean,
    onToggleExposed: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onTest: () -> Unit,
    onShowFailure: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 开放名单开启时，左侧显示勾选框决定该模型是否对外暴露
        if (exposureEnabled) {
            Checkbox(
                checked = exposed,
                onCheckedChange = onToggleExposed,
                modifier = Modifier.size(22.dp),
            )
        }
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
            // 能力标记：只有上游/供应商明确给了才显示，缺就不渲染（不猜）。
            // 各家写 extra 的口径不统一（CodeBuddy 写 tools/vision="true"，Loomy 写 tool_call/attachment="1"），
            // 这里把同义 key 与两种真假写法都认下，否则能力存在却显示不出来。
            val extra = model.model.extra
            fun flag(vararg keys: String): Boolean = keys.any { key ->
                val v = extra[key]?.trim()?.lowercase()
                v == "true" || v == "1"
            }
            val tags = buildList {
                if (flag("reasoning")) add("思考")
                if (flag("search", "web_search", "online")) add("联网")
                if (flag("vision", "image", "attachment")) add("识图")
                if (flag("tools", "tool_call", "toolcall")) add("工具")
                extra["maxOutput"]?.takeIf { it.isNotBlank() }?.let { add("输出上限 $it") }
                extra["vendor"]?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            // 倍率与限时折扣：供应商（Trae 按积分倍率、CodeBuddy 按 credits）已算好，不显示等于白算
            val rateLabel = extra["rate"]?.takeIf { it.isNotBlank() }
            if (rateLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "倍率 $rateLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    extra["rateDiscount"]?.takeIf { it.isNotBlank() }?.let { discount ->
                        Text(
                            text = "限时 $discount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
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
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onShowFailure(testStatus.reason) }
                        .padding(2.dp),
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
