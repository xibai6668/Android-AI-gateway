package dev.aigw.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.aigw.app.ui.theme.WarningAmber
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus
import dev.aigw.core.usage.formatMicroUsd
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogTab(val label: String) { Calls("调用记录"), Requests("请求日志") }

private enum class StatusFilter(val label: String) {
    ALL("全部"), SUCCESS("成功"), FAILED("失败"), ABORTED("中断"),
}

private enum class LevelFilter(val label: String) {
    ALL("全部"), INFO("信息"), WARN("警告"), ERROR("错误"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(state: AppUiState, viewModel: AppViewModel) {
    var tab by remember { mutableStateOf(LogTab.Calls) }
    var filter by remember { mutableStateOf(StatusFilter.ALL) }
    var levelFilter by remember { mutableStateOf(LevelFilter.ALL) }
    var detail by remember { mutableStateOf<CallRecord?>(null) }
    var showLogSettings by remember { mutableStateOf(false) }

    // 停留在本页时自动刷新（只刷记录，不动低频数据）
    LaunchedEffect(tab) {
        while (true) {
            delay(5_000)
            viewModel.refreshRecords()
        }
    }

    val calls = remember(state.calls, filter) {
        state.calls.filter {
            when (filter) {
                StatusFilter.ALL -> true
                StatusFilter.SUCCESS -> it.status == CallStatus.SUCCESS
                StatusFilter.FAILED -> it.status == CallStatus.FAILED
                StatusFilter.ABORTED -> it.status == CallStatus.ABORTED
            }
        }
    }

    val logs = remember(state.logs, levelFilter) {
        when (levelFilter) {
            LevelFilter.ALL -> state.logs
            else -> state.logs.filter { it.level.equals(levelFilter.name, ignoreCase = true) }
        }
    }

    // 本页自带骨架：筛选区固定在顶部，列表用 LazyColumn 懒加载。
    // 记录最多数百条、每条含大段原文，一次性组合全部卡片会明显卡顿；
    // 而 LazyColumn 不能嵌在 PageScaffold 的 verticalScroll 里（无限高度约束会崩），所以这里不走 PageScaffold。
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(
            title = "记录",
            subtitle = "调用与请求日志",
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                StatusChip(state.running)
            },
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(LogTab.Calls.label, tab == LogTab.Calls) { tab = LogTab.Calls }
                    ChoiceChip(LogTab.Requests.label, tab == LogTab.Requests) { tab = LogTab.Requests }
                }
                // 两个标签下都保留设置入口；面板内容与标签无关（见下方 LogSettings 分组）
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showLogSettings = !showLogSettings }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "日志设置",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (showLogSettings) "收起设置" else "日志设置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showLogSettings) {
                // 面板与当前标签无关：两个标签看到完全相同的设置。
                // 此前内容随标签切换（调用记录给归档/保留/清空，请求日志只给容量/清空），
                // 导致「这个设置只有某个标签才有」的困惑——同类返工已发生两次。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 面板较长，限高 + 可滚动，避免挤掉下面的列表
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DiagnosticsSettings(state, viewModel)
                    CallLogSettings(state, viewModel)
                    RequestLogSettings(state, viewModel)
                }
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
                    text = "点卡片看原文 · 长按删除 · 参数在右上「日志设置」里逐项调整",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LevelFilter.entries.forEach { option ->
                        ChoiceChip(option.label, levelFilter == option) { levelFilter = option }
                    }
                }
                Text(
                    text = "共 ${logs.size} 条日志 · 容量 ${state.settings.requestLogCapacity} 条",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "网关与上游交互的流水，环形覆盖只保留最近若干条；参数在右上「日志设置」里逐项调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().scrollFade(listState),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tab == LogTab.Calls) {
                if (calls.isEmpty()) {
                    item(key = "empty-calls") {
                        SectionCard {
                            EmptyHint(
                                title = "暂无调用记录",
                                message = "用任一 API Key 调用本地网关后，记录会出现在这里。",
                            )
                        }
                    }
                } else {
                    items(calls, key = { it.id }) { record ->
                        var confirmDelete by remember { mutableStateOf(false) }
                        SectionCard {
                            CallRow(
                                record,
                                onClick = { detail = record },
                                // 长按只开确认框：删的是一条带完整原文的记录，误触代价太高
                                onLongClick = { confirmDelete = true },
                            )
                        }
                        if (confirmDelete) {
                            ConfirmDestructiveDialog(
                                title = "删除这条调用记录？",
                                body = "${record.model} · ${statusLabel(record.status)}，删除后不可恢复。",
                                onConfirm = { viewModel.deleteCall(record.id) },
                                onDismiss = { confirmDelete = false },
                            )
                        }
                    }
                }
            } else {
                if (logs.isEmpty()) {
                    item(key = "empty-logs") {
                        SectionCard {
                            EmptyHint("暂无请求日志", "启动服务并发起调用后，这里会记录每次转发与换号。")
                        }
                    }
                } else {
                    // key 用单调自增的 seq：时间戳+内容可能重复（同一秒内多条相同日志），
                    // 重复 key 会让 LazyColumn 直接崩溃。
                    itemsIndexed(logs, key = { _, line -> line.seq }) { _, line ->
                        SectionCard {
                            Text(
                                text = line.render(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = levelColor(line.level),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { record ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            CallDetailSheet(record, viewModel)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
                // 模型名缺失（旧记录或异常写入路径）时给明确占位；纯空白串同样回落，
                // 否则头部会出现「看着像什么都没渲染」的空白。
                text = record.model.ifBlank { "未指定模型" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(record.status)
        }
        Text(
            text = buildString {
                append(TIME_FORMAT.format(Date(record.startedAtMillis)))
                append(" · ")
                append(if (record.streaming) "流式" else "非流式")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 胶囊行用 FlowRow：横向放不下时整块换行，任何宽度下每个胶囊都保持自然宽度，
        // 不会被压到「剩余宽度」里把文字折成多行（旧实现里最后的「积分」胶囊因此变成
        // 近似圆形的徽章，标签折成两行，看起来像「积 / 19 / 分」上下堆叠）。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            MetricChip("耗时", formatDuration(record.durationMillis), MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.totalTokens > 0) {
                MetricChip("tokens", formatTokens(record.totalTokens), MaterialTheme.colorScheme.primary)
            }
            if (record.cachedTokens > 0 && record.promptTokens > 0) {
                val hitPercent = String.format(Locale.US, "%.0f", record.cachedTokens * 100.0 / record.promptTokens)
                MetricChip("命中", "$hitPercent%", MaterialTheme.colorScheme.tertiary)
            }
            if (record.pointsConsumed > 0) {
                MetricChip("积分", record.pointsConsumed.toString(), MaterialTheme.colorScheme.secondary)
            }
            if (record.tries > 1) {
                MetricChip("尝试", record.tries.toString(), MaterialTheme.colorScheme.error)
            }
            if (record.retried) {
                MetricChip("换号救回", "是", MaterialTheme.colorScheme.tertiary)
            }
            if (record.costKnown) {
                MetricChip("费用", formatMicroUsd(record.costMicros), MaterialTheme.colorScheme.primary)
            } else if (record.status == CallStatus.SUCCESS) {
                // 未配价格的模型不能显示成 0 元，否则用户会以为真的免费
                MetricChip("费用", "未定价", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record.clientKeyName.isNotEmpty()) {
                MetricChip("密钥", record.clientKeyName, MaterialTheme.colorScheme.secondary)
            }
            if (record.affinityReused) {
                MetricChip("会话亲和", "命中", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record.accountNickname.isNotEmpty()) {
                MetricChip("账号", record.accountNickname, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (record.error.isNotEmpty()) {
            Text(
                text = record.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 一条指标胶囊：等宽数字 + 标签，用于把 token/命中/耗时等压成一行可扫读的信息。
 *
 * 两个 Text 都禁止折行（maxLines = 1 + softWrap = false）：宽度不足时只会省略显示，
 * 绝不会折成多行把胶囊撑成方块（曾导致「积分」胶囊被挤窄后标签折行、外观像文字堆叠）。
 */
@Composable
private fun MetricChip(label: String, value: String, tone: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = tone,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 状态徽章：成功 / 失败 / 中断三态都有稳定的底色与文字。
 *
 * 不能只留一个无底色的小字——一旦头部字段异常，那一行看起来就像完全没渲染。
 */
@Composable
private fun StatusBadge(status: CallStatus) {
    val tone = statusColor(status)
    Text(
        text = statusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        color = tone,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun CallDetailSheet(record: CallRecord, viewModel: AppViewModel) {
    val clipboard = LocalClipboardManager.current

    // 打开抽屉时后台加载各字段完整原文：没溢出文件（未超摘要长度或已被清理）时回落用记录内摘要。
    var fullRequest by remember(record.id) { mutableStateOf<String?>(null) }
    var fullResponse by remember(record.id) { mutableStateOf<String?>(null) }
    var fullRaw by remember(record.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(record.id) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                viewModel.engineFullText(record.id, dev.aigw.core.usage.CallTextField.REQUEST),
                viewModel.engineFullText(record.id, dev.aigw.core.usage.CallTextField.RESPONSE),
                viewModel.engineFullText(record.id, dev.aigw.core.usage.CallTextField.RAW),
            )
        }
        fullRequest = loaded.first
        fullResponse = loaded.second
        fullRaw = loaded.third
    }

    // 抽屉内容可达几十万字符，必须自身可滚动（ModalBottomSheet 默认不可滚）。
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(record.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = "${TIME_FORMAT.format(Date(record.startedAtMillis))} · ${statusLabel(record.status)} · HTTP ${record.httpStatus}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DetailBlock(
            "请求原文",
            fullRequest ?: record.requestBody,
            truncatedNote(fullRequest == null, record.requestBody),
        ) { clipboard.setText(AnnotatedString(fullRequest ?: record.requestBody)) }
        DetailBlock(
            "响应原文",
            fullResponse ?: record.responseBody,
            truncatedNote(fullResponse == null, record.responseBody),
        ) { clipboard.setText(AnnotatedString(fullResponse ?: record.responseBody)) }
        if (record.rawResponse.isNotEmpty() || fullRaw != null) {
            DetailBlock(
                "返回原文（上游报文）",
                fullRaw ?: record.rawResponse,
                truncatedNote(fullRaw == null, record.rawResponse),
            ) { clipboard.setText(AnnotatedString(fullRaw ?: record.rawResponse)) }
        }
        if (record.error.isNotEmpty()) DetailBlock("错误", record.error, null) { clipboard.setText(AnnotatedString(record.error)) }
    }
}

/**
 * 字段是否被截断的说明行：溢出文件缺失时，长度超过摘要阈值即视为「存储中为摘要」。
 */
private fun truncatedNote(fullMissing: Boolean, summary: String): String? = when {
    !fullMissing -> null
    summary.length >= dev.aigw.core.usage.CallLogStore.MAX_SUMMARY_CHARS ->
        "内容较长，仅保留前 ${dev.aigw.core.usage.CallLogStore.MAX_SUMMARY_CHARS} 字符摘要（完整原文已被清理或本次未启用完整保存）"
    else -> null
}

/**
 * 排查诊断：详细日志开关。开启后记录每次调用的请求、转发、发送与返回原文，用于排查 503 类问题。
 */
@Composable
private fun DiagnosticsSettings(state: AppUiState, viewModel: AppViewModel) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("详细日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "记录每次调用的请求、转发、发送与返回原文，用于排查 503 等问题；关闭后立即生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.settings.verboseLogging,
                onCheckedChange = {
                    viewModel.updateSettings(state.settings.copy(verboseLogging = it))
                },
            )
        }
    }
}

/**
 * 调用记录的参数设置：归档开关、保留天数、清空。
 *
 * 与请求日志、详细日志并列展示在同一面板内——面板内容不随标签变化。
 */
@Composable
private fun CallLogSettings(state: AppUiState, viewModel: AppViewModel) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("记录调用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "保存每次调用的请求/响应原文与用量；关闭后不再新增（已存记录不受影响）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.callLoggingEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(state.settings.copy(callLoggingEnabled = it))
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "服务运行时每日自动清理超过该天数的记录，当前 ${state.settings.logRetentionDays} 天。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RetentionField(state.settings.logRetentionDays) { viewModel.setLogRetentionDays(it) }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text("清空记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "删除本机保存的全部调用记录（含完整原文），不可恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var confirmClear by remember { mutableStateOf(false) }
            OutlineActionButton("清空调用记录") { confirmClear = true }
            if (confirmClear) {
                ConfirmDestructiveDialog(
                    title = "清空全部调用记录？",
                    body = "将删除本机保存的 ${state.storedCalls} 条调用记录（含完整原文），不可恢复。",
                    confirmText = "清空",
                    onConfirm = { viewModel.clearCalls() },
                    onDismiss = { confirmClear = false },
                )
            }
        }
    }
}

/**
 * 保留天数输入。
 *
 * 失焦或回车才提交，避免边输入边保存把「30」中间的「3」当成 30 天。越界值由 core 夹回，
 * 这里只做即时提示。
 */
@Composable
fun RetentionField(current: Int, onCommit: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in GatewaySettings.MIN_RETENTION_DAYS..GatewaySettings.MAX_RETENTION_DAYS
    val changed = parsed != null && parsed != current

    Text(
        text = "保留天数（${GatewaySettings.MIN_RETENTION_DAYS}-${GatewaySettings.MAX_RETENTION_DAYS}）",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { input -> text = input.filter(Char::isDigit).take(4) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = text.isNotEmpty() && !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            when {
                text.isEmpty() -> Text("不能为空")
                !valid -> Text("需在 ${GatewaySettings.MIN_RETENTION_DAYS}-${GatewaySettings.MAX_RETENTION_DAYS} 之间")
            }
        },
    )
    if (changed) {
        OutlineActionButton("保存保留天数") { onCommit(parsed) }
    }
}

/**
 * 请求日志的参数设置：每项参数一个控件，展开后直接调整，不折叠到弹窗里。
 *
 * 与调用记录、详细日志并列展示在同一面板内——面板内容不随标签变化。
 */
@Composable
private fun RequestLogSettings(state: AppUiState, viewModel: AppViewModel) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("日志容量", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "环形缓冲最多保留的日志条数，超出后自动丢弃最旧一条；调大占用更多内存与存储。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LogCapacityField(state.settings.requestLogCapacity) {
                viewModel.updateSettings(state.settings.copy(requestLogCapacity = it))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text("清空日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "删除当前全部请求日志，容量等设置不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var confirmClearLogs by remember { mutableStateOf(false) }
            OutlineActionButton("清空请求日志") { confirmClearLogs = true }
            if (confirmClearLogs) {
                ConfirmDestructiveDialog(
                    title = "清空全部请求日志？",
                    body = "将删除当前所有请求日志（容量等设置不受影响），不可恢复。",
                    confirmText = "清空",
                    onConfirm = { viewModel.clearLogs() },
                    onDismiss = { confirmClearLogs = false },
                )
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, body: String, note: String?, onCopy: () -> Unit) {
    // 大文本折叠：全文可达 512K 字符，一次性组合进 Text 会让抽屉首帧明显卡顿；
    // 默认只渲染预览，点「展开全文」后再渲染完整内容。
    // 折叠用弹性高度过渡（借鉴 open-claude-ui 的 0fr↔1fr 思路，在 Compose 里对应
    // animateContentSize + 与思考折叠相同的 200ms 曲线），避免瞬间跳变。
    var expanded by remember(body) { mutableStateOf(body.length <= PREVIEW_CHARS) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!expanded && body.length > PREVIEW_CHARS) {
                Text(
                    text = "展开全文",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { expanded = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
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
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val visible = if (expanded) body else body.take(PREVIEW_CHARS) + "\n…（点击「展开全文」查看剩余部分）"
        Text(
            text = visible.ifEmpty { "（空）" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .animateContentSize(
                    animationSpec = tween(200, easing = CubicBezierEasing(0.19f, 1f, 0.22f, 1f)),
                )
                .padding(12.dp),
        )
    }
}

private const val PREVIEW_CHARS = 2_000

private fun statusLabel(status: CallStatus): String = when (status) {
    CallStatus.SUCCESS -> "成功"
    CallStatus.FAILED -> "失败"
    CallStatus.ABORTED -> "中断"
}

@Composable
private fun levelColor(level: String): Color = when (level.uppercase()) {
    "WARN" -> WarningAmber
    "ERROR" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

/** 日志容量输入：失焦/回车不适用（按钮提交），越界由 core 夹回。 */
@Composable
private fun LogCapacityField(current: Int, onCommit: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null &&
        parsed in dev.aigw.core.gateway.GatewaySettings.MIN_REQUEST_LOG_CAPACITY..
        dev.aigw.core.gateway.GatewaySettings.MAX_REQUEST_LOG_CAPACITY
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { input -> text = input.filter(Char::isDigit).take(4) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("请求日志容量（${dev.aigw.core.gateway.GatewaySettings.MIN_REQUEST_LOG_CAPACITY}-${dev.aigw.core.gateway.GatewaySettings.MAX_REQUEST_LOG_CAPACITY} 条）") },
            isError = text.isNotEmpty() && !valid,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        )
        if (parsed != null && valid && parsed != current) {
            OutlineActionButton("保存容量") { onCommit(parsed) }
        }
    }
}

@Composable
private fun statusColor(status: CallStatus): Color = when (status) {
    CallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
    CallStatus.FAILED -> MaterialTheme.colorScheme.error
    CallStatus.ABORTED -> WarningAmber
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
