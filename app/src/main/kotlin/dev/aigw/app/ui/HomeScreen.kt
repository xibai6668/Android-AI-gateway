package dev.aigw.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aigw.core.gateway.ActiveRequest
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus

/**
 * 首页：只回答「服务是否在跑」与「现在能做什么」。
 *
 * 数据看板（趋势、缓存命中率、模型分布、按供应商额度）都在各自标签页里，
 * 这里不重复展示同一份数字。
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

    PageScaffold(
        title = "ai-gateway",
        subtitle = "本地 OpenAI 兼容网关",
    ) {
        GatewayStatusCard(
            state = state,
            onStart = { startWithNotificationPermission() },
            onStop = { viewModel.stopGateway() },
            onCopy = ::copy,
        )

        UsageSummaryCard(state, openUsage)

        // 批量动作进行中/结束：签到按钮就在本页，进度与失败原因要在这里可见
        BatchProgressHost(state.batchProgress)
        BatchOutcomeHost(state.batchOutcome) { viewModel.dismissBatchOutcome() }
        ActionRow(
            onCheckin = { viewModel.checkinAll() },
            onRefreshCredits = { viewModel.refreshAllCredits() },
            onOpenTaskCenter = openTaskCenter,
        )

        LiveActivityCard(state, openLogs)
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

/** 在途请求与最近一次调用的去处；详情在「记录」页。 */
@Composable
private fun LiveActivityCard(state: AppUiState, openLogs: () -> Unit) {
    SectionCard {
        SectionHeader("实时活动", action = "记录", onAction = openLogs)
        ActivityRow("在途", activeSummary(state.activeRequests), emphasized = state.activeRequests.isNotEmpty())
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActivityRow("最近", lastCallSummary(state.calls.firstOrNull()), emphasized = false)
    }
}

@Composable
private fun ActivityRow(label: String, detail: String, emphasized: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            text = detail,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun activeSummary(active: List<ActiveRequest>): String {
    val first = active.firstOrNull() ?: return "空闲"
    val phase = when (first.phase) {
        ActiveRequest.Phase.CONNECTING -> "连接上游"
        ActiveRequest.Phase.UPSTREAM_ANSWERED -> "等待应答"
        ActiveRequest.Phase.STREAMING -> "输出中"
    }
    val head = "${first.clientModel.ifEmpty { "请求" }} → ${first.providerId.uppercase()} · $phase"
    return if (active.size > 1) "${active.size} 个 · $head" else head
}

private fun lastCallSummary(call: CallRecord?): String {
    if (call == null) return "暂无请求"
    val status = when (call.status) {
        CallStatus.SUCCESS -> "成功"
        CallStatus.FAILED -> "失败"
        CallStatus.ABORTED -> "客户端中断"
    }
    val model = call.model.ifEmpty { "未记录模型" }
    return "$model → ${call.providerId.uppercase()} · $status · ${formatDuration(call.durationMillis)}"
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
