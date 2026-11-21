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
    var showPromptRewriteSheet by remember { mutableStateOf(false) }
    var showRateLimitDialog by remember { mutableStateOf(false) }

    PageScaffold(
        title = "安全防护",
        subtitle = "反审核脱敏与账号限速",
        leading = { BackButton(onBack) },
    ) {
        // 组件 0：本地隐私脱敏
        PrivacyRedactionComponent(
            state = state,
            onToggle = { enabled ->
                viewModel.updateSecuritySettings(state.security.copy(privacyRedactionEnabled = enabled))
            },
        )

        // 组件 1：反审核脱敏模块
        DesensitizationComponent(
            state = state,
            onToggle = { enabled ->
                viewModel.updateSecuritySettings(state.security.copy(sanitizeEnabled = enabled))
            },
            onReloadPipeline = { viewModel.reloadSanitizerPipeline() },
            onViewEvidence = { showEvidenceSheet = true },
        )

        // 组件 2：风控提示词替换
        PromptRewriteComponent(
            state = state,
            onToggle = { enabled ->
                viewModel.updateSecuritySettings(state.security.copy(promptRewriteEnabled = enabled))
            },
            onTogglePreset = { setName -> viewModel.togglePromptRewritePreset(setName) },
            onAddRule = { name, pattern, replacement ->
                viewModel.addPromptRewriteRule(name, pattern, replacement)
            },
            onRemoveRule = { rule -> viewModel.removePromptRewriteRule(rule) },
            onViewEvidence = { showPromptRewriteSheet = true },
        )

        // 组件 3：账号级限速模块
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

    if (showPromptRewriteSheet) {
        PromptRewriteEvidenceSheet(
            evidences = state.promptRewriteEvidences,
            onDismiss = { showPromptRewriteSheet = false },
            onClear = { viewModel.clearPromptRewriteEvidences() },
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
// 独立防护组件 0：本地隐私脱敏
// ==============================================================================

@Composable
fun PrivacyRedactionComponent(
    state: AppUiState,
    onToggle: (Boolean) -> Unit,
) {
    SectionLabel("本地隐私脱敏")
    SectionCard(enterIndex = 0) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("出网前脱敏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "请求发给上游前，把 API Key、邮箱等换成占位符，等回答回来再还原。上游与模型看到的不是你的真实密钥，本机客户端无感",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.security.privacyRedactionEnabled,
                onCheckedChange = onToggle,
            )
        }
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
// 独立防护组件 2：风控提示词替换
// ==============================================================================

@Composable
fun PromptRewriteComponent(
    state: AppUiState,
    onToggle: (Boolean) -> Unit,
    onTogglePreset: (String) -> Unit,
    onAddRule: (name: String, pattern: String, replacement: String) -> Unit,
    onRemoveRule: (dev.aigw.core.security.PromptRule) -> Unit,
    onViewEvidence: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val activePresets = state.security.effectivePromptPresets()
    val presetSets = remember { dev.aigw.core.security.PromptRiskRewriter.builtinRuleSets() }

    SectionLabel("风控提示词替换")
    SectionCard(enterIndex = 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("开启替换", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "反重力会检查系统提示词来判定你是不是反代，命中后不一定报错，常伪装成 429 或直接降智。" +
                        "开启后把那些固定句式改写成等义说法，模型理解不变。只改 system，不动你的输入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.security.promptRewriteEnabled,
                onCheckedChange = onToggle,
            )
        }

        if (state.security.promptRewriteEnabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "内置规则集",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            presetSets.forEach { (setName, rules) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(setName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${rules.size} 条规则",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = setName in activePresets,
                        onCheckedChange = { onTogglePreset(setName) },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "自定义规则（${state.security.promptRewriteRules.size} 条）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.security.promptRewriteRules.isEmpty()) {
                Text(
                    text = "上游新增检测时，在这里录入命中的句子和替换文本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.security.promptRewriteRules.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = rule.pattern,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRemoveRule(rule) }) { Text("删除") }
                    }
                }
            }

            OutlineActionButton("新增自定义规则") {
                showAddDialog = true
            }
            OutlineActionButton("查看替换取证") {
                onViewEvidence()
            }
        }
    }

    if (showAddDialog) {
        PromptRewriteRuleDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, pattern, replacement ->
                onAddRule(name, pattern, replacement)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun PromptRewriteRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, pattern: String, replacement: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增替换规则") },
        text = {
            DialogContentColumn {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("要匹配的文本") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("替换为") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "匹配不分大小写，按整句替换。只作用于 system 消息，不影响你的输入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, pattern, replacement) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptRewriteEvidenceSheet(
    evidences: List<dev.aigw.core.security.PromptRewriteEvidence>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
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
                    Text("替换取证", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = "共 ${evidences.size} 条命中记录（只展示 system 改写）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (evidences.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (evidences.isEmpty()) {
                EmptyHint("暂无替换记录", "当系统提示词命中替换规则时，改写前后的对照会记录在这里。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(evidences, key = { _, item -> item.seq }) { _, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${timeFormat.format(Date(item.atMillis))} · ${item.providerId} · ${item.model}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "命中规则：${item.matchedRules.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "改写前：",
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
                                text = "改写后：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = item.rewrittenSnippet,
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
// 独立防护组件 3：账号级限速
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
            DialogContentColumn {
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
