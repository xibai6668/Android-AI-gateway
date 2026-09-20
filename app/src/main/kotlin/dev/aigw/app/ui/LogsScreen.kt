package dev.aigw.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogTab(val label: String) { Calls("调用记录"), Requests("请求日志") }

private enum class StatusFilter(val label: String) {
    ALL("全部"), SUCCESS("成功"), FAILED("失败"), ABORTED("中断"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(state: AppUiState, viewModel: AppViewModel) {
    var tab by remember { mutableStateOf(LogTab.Calls) }
    var filter by remember { mutableStateOf(StatusFilter.ALL) }
    var detail by remember { mutableStateOf<CallRecord?>(null) }
    var tick by remember { mutableStateOf(0) }

    // 停留在本页时自动刷新（只刷记录，不动低频数据）
    LaunchedEffect(tab) {
        while (true) {
            delay(5_000)
            viewModel.refreshRecords()
            tick++
        }
    }

    val calls = remember(state.calls, filter, tick) {
        state.calls.filter {
            when (filter) {
                StatusFilter.ALL -> true
                StatusFilter.SUCCESS -> it.status == CallStatus.SUCCESS
                StatusFilter.FAILED -> it.status == CallStatus.FAILED
                StatusFilter.ABORTED -> it.status == CallStatus.ABORTED
            }
        }
    }

    PageScaffold(
        title = "记录",
        subtitle = "调用与请求日志",
        actions = {
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            StatusChip(state.running)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(LogTab.Calls.label, tab == LogTab.Calls) { tab = LogTab.Calls }
            ChoiceChip(LogTab.Requests.label, tab == LogTab.Requests) { tab = LogTab.Requests }
        }

        if (tab == LogTab.Calls) {
            Text(
                text = "共 ${state.calls.size} 条记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.entries.forEach { option ->
                    ChoiceChip(option.label, filter == option, { filter = option })
                }
            }
            Text(
                text = "点卡片看原文 · 长按删除 · 停留本页自动刷新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (calls.isEmpty()) {
                SectionCard {
                    EmptyHint(
                        title = "暂无调用记录",
                        message = "用任一 API Key 调用本地网关后，记录会出现在这里。",
                    )
                }
            } else {
                SectionCard {
                    calls.forEachIndexed { index, record ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CallRow(record, onClick = { detail = record }, onLongClick = { viewModel.deleteCall(record.id) })
                    }
                }
            }
        } else {
            Text(
                text = "共 ${state.logs.size} 条日志",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "网关与上游交互的流水，仅保留最近若干条。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.logs.isEmpty()) {
                SectionCard { EmptyHint("暂无请求日志", "启动服务并发起调用后，这里会记录每次转发与换号。") }
            } else {
                SectionCard {
                    state.logs.forEachIndexed { index, line ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = line.render(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    detail?.let { record ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            CallDetailSheet(record)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(record: CallRecord, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.model.ifEmpty { "未指定模型" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusLabel(record.status),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(record.status),
            )
        }
        Text(
            text = buildString {
                append(TIME_FORMAT.format(Date(record.startedAtMillis)))
                append(" · ")
                append(if (record.streaming) "流式" else "非流式")
                append(" · ${record.durationMillis}ms")
                if (record.totalTokens > 0) append(" · ${record.totalTokens} tokens")
                if (record.pointsConsumed > 0) append(" · 消耗 ${record.pointsConsumed} 积分")
                if (record.accountNickname.isNotEmpty()) append(" · ${record.accountNickname}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (record.error.isNotEmpty()) {
            Text(
                text = record.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun CallDetailSheet(record: CallRecord) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(record.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = "${TIME_FORMAT.format(Date(record.startedAtMillis))} · ${statusLabel(record.status)} · HTTP ${record.httpStatus}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DetailBlock("请求原文", record.requestBody) { clipboard.setText(AnnotatedString(record.requestBody)) }
        DetailBlock("响应原文", record.responseBody) { clipboard.setText(AnnotatedString(record.responseBody)) }
        if (record.error.isNotEmpty()) DetailBlock("错误", record.error) { clipboard.setText(AnnotatedString(record.error)) }
    }
}

@Composable
private fun DetailBlock(title: String, body: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            text = body.ifEmpty { "（空）" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        )
    }
}

private fun statusLabel(status: CallStatus): String = when (status) {
    CallStatus.SUCCESS -> "成功"
    CallStatus.FAILED -> "失败"
    CallStatus.ABORTED -> "中断"
}

@Composable
private fun statusColor(status: CallStatus): Color = when (status) {
    CallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
    CallStatus.FAILED -> MaterialTheme.colorScheme.error
    CallStatus.ABORTED -> Color(0xFFC97F0A)
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
