package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aigw.core.security.OutboundEvidence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全防护与风控设置页：
 * 以可插拔的模块化组件结构组织，方便后续扩展更多防护能力（如 IP管控、指纹脱敏等）。
 */
@Composable
fun SecurityProtectionScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    var showEvidenceSheet by remember { mutableStateOf(false) }
    var showRateLimitDialog by remember { mutableStateOf(false) }

    PageScaffold(
        title = "安全防护",
        subtitle = "反审核脱敏与账号限速",
        leading = { BackButton(onBack) },
    ) {
        // 组件 1：反审核脱敏模块
        DesensitizationComponent(
            state = state,
            onToggle = { enabled ->
                viewModel.updateSecuritySettings(state.security.copy(sanitizeEnabled = enabled))
            },
            onReloadPipeline = { viewModel.reloadSanitizerPipeline() },
            onViewEvidence = { showEvidenceSheet = true },
        )

        // 组件 2：账号级限速模块
        RateLimitComponent(
            state = state,
            onOpenEdit = { showRateLimitDialog = true },
        )
    }

    if (showEvidenceSheet) {
        EvidenceSheet(
            evidences = state.evidences,
            onDismiss = { showEvidenceSheet = false },
            onClear = { viewModel.clearEvidences() },
        )
    }

    if (showRateLimitDialog) {
        RateLimitEditDialog(
            currentMin = state.security.minIntervalMillis,
            currentJitter = state.security.jitterMillis,
            onDismiss = { showRateLimitDialog = false },
            onConfirm = { newMin, newJitter ->
                viewModel.updateSecuritySettings(
                    state.security.copy(
                        minIntervalMillis = newMin,
                        jitterMillis = newJitter,
                    ),
                )
                showRateLimitDialog = false
            },
        )
    }
}

// ==============================================================================
// 独立防护组件 1：反审核脱敏
// ==============================================================================

@Composable
fun DesensitizationComponent(
    state: AppUiState,
    onToggle: (Boolean) -> Unit,
    onReloadPipeline: () -> Unit,
    onViewEvidence: () -> Unit,
) {
    SectionLabel("反审核脱敏")
    SectionCard(enterIndex = 0) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("开启脱敏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "客户端的合规声明里常有 DoS / exploit 等词，会被上游误拦。开启后在这些词内部插零宽空格，只改 system 消息，不影响用户输入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.security.sanitizeEnabled,
                onCheckedChange = onToggle,
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlineActionButton("重新加载处理链") {
            onReloadPipeline()
        }

        OutlineActionButton("进入出网取证") {
            onViewEvidence()
        }
    }
}

// ==============================================================================
// 独立防护组件 2：账号级限速
// ==============================================================================

@Composable
fun RateLimitComponent(
    state: AppUiState,
    onOpenEdit: () -> Unit,
) {
    SectionLabel("账号级限速")
    SectionCard(enterIndex = 1) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("最小间隔", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${state.security.minIntervalMillis} 毫秒（同一账号两次上游请求的最短间隔）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("抖动幅度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${state.security.jitterMillis} 毫秒（避免固定节拍本身成为特征）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改限速") {
            onOpenEdit()
        }
    }
}

// ==============================================================================
// 出网取证详情底部抽屉
// ==============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceSheet(
    evidences: List<OutboundEvidence>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("出网取证", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = "共 ${evidences.size} 条脱敏证据（只展示 system 改写）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (evidences.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("清空")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (evidences.isEmpty()) {
                EmptyHint("暂无取证记录", "当客户端发送包含 DoS / exploit 等词的 system 提示词时，脱敏穿透快照会记录在这里。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(
                        evidences,
                        // key 用单调自增的 seq：时间戳+模型+命中词可能重复，重复 key 会让 LazyColumn 崩溃
                        key = { _, item -> item.seq },
                    ) { index, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${timeFormat.format(Date(item.atMillis))} · ${item.model}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "复制",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            clipboard.setText(AnnotatedString(item.sanitizedSnippet))
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }

                            Text(
                                text = "命中词汇：${item.matchedTerms.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )

                            Text(
                                text = "脱敏前片段：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = item.originalSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Text(
                                text = "脱敏后（已注入零宽空格）：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = item.sanitizedSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 限速参数修改弹窗
// ==============================================================================

@Composable
private fun RateLimitEditDialog(
    currentMin: Long,
    currentJitter: Long,
    onDismiss: () -> Unit,
    onConfirm: (newMin: Long, newJitter: Long) -> Unit,
) {
    var minText by remember { mutableStateOf(currentMin.toString()) }
    var jitterText by remember { mutableStateOf(currentJitter.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改账号限速参数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = { minText = it.filter(Char::isDigit).take(5) },
                    label = { Text("最小间隔（毫秒）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = jitterText,
                    onValueChange = { jitterText = it.filter(Char::isDigit).take(5) },
                    label = { Text("抖动幅度（毫秒）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "同一账号连续请求将强制按“最小间隔 + 随机抖动”节拍等待，建议 1000~3000ms。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val min = minText.toLongOrNull() ?: currentMin
                    val jitter = jitterText.toLongOrNull() ?: currentJitter
                    onConfirm(min.coerceIn(0L, 60_000L), jitter.coerceIn(0L, 10_000L))
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
