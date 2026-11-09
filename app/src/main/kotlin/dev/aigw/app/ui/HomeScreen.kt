package dev.aigw.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.app.ui.theme.WarningAmber
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus
import java.util.Locale

/**
 * 首页：服务状态、用量与快捷入口。
 *
 * 组件顺序与可见性由用户决定（右上角「编辑」进入编辑态，拖拽排序、移除、添加），
 * 布局存于 [HomeLayoutStore]。
 */
@Composable
fun HomeScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    openTaskCenter: () -> Unit,
    openLogs: () -> Unit,
    openUsage: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    // Android 13+ 通知权限被拒时前台服务照常跑，但常驻通知会被系统静默隐藏，
    // 用户看起来就是「服务没起来」；启动前先补上授权请求。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startGateway() }

    fun startWithNotificationPermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startGateway()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun copy(text: String, message: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 编辑态：返回键先退出编辑，而不是直接把页面退出（与 FlClash 一致）
    BackHandler(enabled = editing) { editing = false }

    if (adding) {
        AddHomeWidgetDialog(
            hidden = state.homeHidden,
            onAdd = { widget ->
                viewModel.saveHomeLayout(state.homeOrder, state.homeHidden - widget)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    val visible = state.homeOrder.filterNot { it in state.homeHidden }

    PageScaffold(
        title = "ai-gateway",
        subtitle = if (editing) "编辑首页组件" else "本地 OpenAI 兼容网关",
        actions = {
            if (editing && state.homeHidden.isNotEmpty()) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "添加组件",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable { adding = true }
                        .padding(6.dp),
                )
            }
            HomeEditAction(editing) { editing = !editing }
        },
    ) {
        // 批量进度与结果不属于可摆放组件：它们是动作的反应，必须始终可见
        BatchProgressHost(state.batchProgress)
        BatchOutcomeHost(state.batchOutcome) { viewModel.dismissBatchOutcome() }

        if (editing) {
            HomeEditHint(visible.isEmpty())
            EditableHomeWidgetList(
                order = state.homeOrder,
                hidden = state.homeHidden,
                onReorder = { viewModel.saveHomeLayout(it, state.homeHidden) },
                onRemove = { widget ->
                    viewModel.saveHomeLayout(state.homeOrder, state.homeHidden + widget)
                },
            ) { widget ->
                HomeWidgetContent(widget, state, viewModel, ::startWithNotificationPermission, ::copy, openTaskCenter, openLogs, openUsage)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                visible.forEach { widget ->
                    HomeWidgetContent(widget, state, viewModel, ::startWithNotificationPermission, ::copy, openTaskCenter, openLogs, openUsage)
                }
            }
        }
    }
}

/** 按组件 id 分发内容；新增组件只需在此加一个分支。 */
@Composable
private fun HomeWidgetContent(
    widget: HomeWidget,
    state: AppUiState,
    viewModel: AppViewModel,
    onStart: () -> Unit,
    onCopy: (String, String) -> Unit,
    openTaskCenter: () -> Unit,
    openLogs: () -> Unit,
    openUsage: () -> Unit,
) {
    when (widget) {
        HomeWidget.STATUS -> GatewayStatusCard(
            state = state,
            onStart = onStart,
            onStop = { viewModel.stopGateway() },
            onCopy = onCopy,
        )
        HomeWidget.SUMMARY -> UsageSummaryCard(state, openUsage)
        HomeWidget.ACTIONS -> ActionRow(
            onCheckin = { viewModel.checkinAll() },
            onRefreshCredits = { viewModel.refreshAllCredits() },
            onOpenTaskCenter = openTaskCenter,
        )
        HomeWidget.LAST_CALL -> RecentCallCard(state, openLogs)
        HomeWidget.CONTEXT -> ContextCapacityCard(state)
    }
}

/** 状态 + 接入地址 + 启停。 */
@Composable
private fun GatewayStatusCard(
    state: AppUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val lanUrl = state.lanUrls.firstOrNull()
    val localUrl = state.localUrl.ifEmpty { "http://127.0.0.1:${state.port}/v1" }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(state.running)
            Text(
                text = if (state.running) "运行中" else "已停止",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ":${state.port}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .then(
                        if (state.running) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.onSurface)
                        },
                    )
                    .clickable { if (state.running) onStop() else onStart() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (state.running) "停止服务" else "启动服务",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
                )
            }
        }

        AddressRow("本机", localUrl) { onCopy(localUrl, "已复制本机地址") }
        // 未开局域网监听或没连 Wi-Fi 时没有可用地址，整行不占位
        if (lanUrl != null) {
            AddressRow("局域网", lanUrl) { onCopy(lanUrl, "已复制局域网地址") }
        }
    }
}

/** 一行接入地址：浅灰底 + 等宽地址 + 复制小块。 */
@Composable
private fun AddressRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.onSurface)
                .clickable(onClick = onCopy)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                "复制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

/** 三项核心指标；完整统计在「用量统计」页。 */
@Composable
private fun UsageSummaryCard(state: AppUiState, openUsage: () -> Unit) {
    SectionCard {
        SectionHeader("今日用量", action = "用量统计", onAction = openUsage)
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                value = "${state.pool.usable} / ${state.pool.total}",
                label = "可用账号",
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = formatCount(state.today.requests),
                label = "请求",
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = formatTokens(state.today.totalTokens),
                label = "tokens",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 首页可执行的三件事。 */
@Composable
private fun ActionRow(
    onCheckin: () -> Unit,
    onRefreshCredits: () -> Unit,
    onOpenTaskCenter: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton("批量签到", primary = true, onClick = onCheckin, modifier = Modifier.weight(1f))
        ActionButton("任务中心", primary = false, onClick = onOpenTaskCenter, modifier = Modifier.weight(1f))
        ActionButton("刷新额度", primary = false, onClick = onRefreshCredits, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (primary) {
                    Modifier.background(MaterialTheme.colorScheme.onSurface)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** 最近一次请求的完整路由流水：模型、去向、结果、token 开销。 */
@Composable
private fun RecentCallCard(state: AppUiState, openLogs: () -> Unit) {
    SectionCard {
        SectionHeader("最近路由记录", action = "记录", onAction = openLogs)
        val call = state.calls.firstOrNull()
        if (call == null) {
            Text(
                text = "暂无请求记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return@SectionCard
        }
        RecentCallRoutingFlow(call)
    }
}

@Composable
private fun RecentCallRoutingFlow(call: CallRecord) {
    val dotNeutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val resultColor = when (call.status) {
        CallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
        CallStatus.FAILED -> MaterialTheme.colorScheme.error
        CallStatus.ABORTED -> WarningAmber
    }
    val resultText = when (call.status) {
        CallStatus.SUCCESS -> "成功"
        CallStatus.FAILED -> "失败"
        CallStatus.ABORTED -> "客户端中断"
    }
    val accountDisplay = call.accountNickname.ifEmpty { call.accountUid.ifEmpty { "默认账号" } }

    RoutingFlowRow(dotNeutral, "客户端请求", call.model.ifEmpty { "未记录" })
    RoutingFlowRow(
        dotColor = LocalAppColors.current.goldStart,
        label = "路由到",
        detail = "${call.providerId.uppercase()} · $accountDisplay",
    )
    RoutingFlowRow(dotColor = resultColor, label = "结果", detail = "$resultText · HTTP ${call.httpStatus} · 耗时 ${formatDuration(call.durationMillis)}")

    val tokenDetail = buildString {
        append("输入 ${formatTokens(call.promptTokens)} · 输出 ${formatTokens(call.completionTokens)}")
        if (call.cachedTokens > 0) append(" · 缓存 ${formatTokens(call.cachedTokens)}")
    }
    RoutingFlowRow(dotNeutral, "Token", tokenDetail)
}

@Composable
private fun RoutingFlowRow(dotColor: Color, label: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 单个模型的近 50 次请求聚合统计。 */
private data class ModelTokenAggregation(
    val model: String,
    val requests: Int,
    val promptTokens: Long,
)

/**
 * 在 state.models 里匹配 contextWindow：
 * 1. 用 providerId + model（完全匹配 ProviderModel.id 或 name，并支持剥离 routePrefix）；
 * 2. 退化为任意 provider 下匹配模型名；
 * 3. 0 或未匹配返回 0L（表示未知）。
 */
private fun resolveContextWindow(
    providerId: String,
    modelName: String,
    models: List<RoutedModel>,
): Long {
    if (modelName.isEmpty() || models.isEmpty()) return 0L
    val cleanName = modelName.substringAfterLast('/')

    val matched = models.firstOrNull { routed ->
        routed.providerId.equals(providerId, ignoreCase = true) &&
            (routed.model.id.equals(modelName, ignoreCase = true) ||
                routed.model.id.equals(cleanName, ignoreCase = true) ||
                routed.model.name.equals(cleanName, ignoreCase = true) ||
                routed.fullId.equals(modelName, ignoreCase = true))
    }
    if (matched != null && matched.model.contextWindow > 0L) {
        return matched.model.contextWindow
    }

    val fallback = models.firstOrNull { routed ->
        routed.model.id.equals(cleanName, ignoreCase = true) ||
            routed.model.name.equals(cleanName, ignoreCase = true)
    }
    if (fallback != null && fallback.model.contextWindow > 0L) {
        return fallback.model.contextWindow
    }
    return 0L
}

/**
 * 上下文容量监控 + 近 50 次请求按模型的输入 token 分布。
 *
 * 容量取最近一次请求的输入 token 与该模型的上下文上限；上限未知时不编造数字，
 * 直接写「上限未知」。只命中一个模型时分段条与图例与下方明细重复，不画。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextCapacityCard(state: AppUiState) {
    val recentCalls = remember(state.calls) { state.calls.take(50) }
    val latestCall = recentCalls.firstOrNull()
    val promptTokens = latestCall?.promptTokens ?: 0L
    val cachedTokens = latestCall?.cachedTokens ?: 0L
    val contextWindow = remember(latestCall, state.models) {
        if (latestCall != null) {
            resolveContextWindow(latestCall.providerId, latestCall.model, state.models)
        } else {
            0L
        }
    }
    val aggregated = remember(recentCalls) {
        recentCalls
            .groupBy { it.model.ifEmpty { "未命名模型" } }
            .map { (model, calls) ->
                ModelTokenAggregation(
                    model = model,
                    requests = calls.size,
                    promptTokens = calls.sumOf { it.promptTokens },
                )
            }
            .sortedByDescending { it.promptTokens }
    }
    val totalPromptTokens = remember(aggregated) { aggregated.sumOf { it.promptTokens } }
    val palette = LocalAppColors.current.providerPalette
    var expanded by remember { mutableStateOf(false) }

    SectionCard {
        SectionHeader("上下文容量与分布")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (latestCall != null) formatTokens(promptTokens) else "—",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val remainingText = when {
                    contextWindow <= 0L -> "剩余未知"
                    contextWindow >= promptTokens -> "剩余 ${formatTokens(contextWindow - promptTokens)}"
                    else -> "超出上限"
                }
                val limitText = if (contextWindow > 0L) "上限 ${formatTokens(contextWindow)}" else "上限未知"
                Text(
                    text = "$limitText · $remainingText",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                // 没有输入 token 时不编造 0%
                Text(
                    text = if (promptTokens > 0L) String.format(Locale.US, "%.1f%%", cachedTokens.toDouble() / promptTokens * 100.0) else "—",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = if (cachedTokens > 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "缓存命中",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "近 50 次请求的输入 token 按模型",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (totalPromptTokens > 0L) formatTokens(totalPromptTokens) else "暂无 token 数据",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (totalPromptTokens <= 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        } else if (aggregated.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                aggregated.forEachIndexed { index, item ->
                    val fraction = item.promptTokens.toFloat() / totalPromptTokens
                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(Motion.BAR_FILL_MILLIS, easing = FastOutSlowInEasing),
                        label = "modelTokenBar$index",
                    )
                    Box(
                        modifier = Modifier
                            .weight(animatedFraction.coerceAtLeast(0.001f))
                            .fillMaxSize()
                            .background(palette[index % palette.size]),
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                aggregated.take(4).forEachIndexed { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(palette[index % palette.size]))
                        Text(
                            text = "${item.model.substringAfterLast('/')} (${formatTokens(item.promptTokens)})",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (aggregated.size > 4) {
                    Text(
                        text = "+${aggregated.size - 4}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (aggregated.isNotEmpty()) {
            val displayItems = if (expanded) aggregated else aggregated.take(5)
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                displayItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(palette[index % palette.size]))
                        Text(
                            text = item.model,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${item.requests} 次",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatTokens(item.promptTokens),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (aggregated.size > 5) {
                    Text(
                        text = if (expanded) "收起" else "显示全部 ${aggregated.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.goldText,
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 状态圆点：运行中绿点带浅光环，停止灰色。 */
@Composable
private fun StatusDot(running: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        if (running) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)))
        }
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
        )
    }
}
