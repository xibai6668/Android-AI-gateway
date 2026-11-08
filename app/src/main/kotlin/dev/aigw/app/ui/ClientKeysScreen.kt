package dev.aigw.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aigw.core.gateway.ClientKey
import dev.aigw.core.gateway.KeyLimit
import dev.aigw.core.gateway.KeyUsage
import dev.aigw.core.gateway.LimitPeriod
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.usage.formatMicroUsd

/**
 * 客户端密钥页：管「谁能调用网关」。
 *
 * 与上游账号凭证是两回事：上游凭证决定用谁家的额度，这里的密钥决定谁能用这台网关。
 * 每把钥匙可限制可用模型与用量上限，超额在打上游之前就被拒绝。
 */
@Composable
fun ClientKeysScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf<ClientKey?>(null) }
    var creating by remember { mutableStateOf(false) }
    var revealSecret by remember { mutableStateOf<Pair<String, String>?>(null) }
    var viewingSecret by remember { mutableStateOf<Pair<String, String>?>(null) }

    PageScaffold(
        title = "客户端密钥",
        subtitle = "谁能调用网关，与上游账号凭证分离",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            Text(
                text = "每个客户端一把钥匙，可单独限制模型与用量。超额会在请求上游之前被拒绝，" +
                    "并返回重置时间。本机回环调用不受限制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlineActionButton("新建密钥") { creating = true }
        }

        if (state.clientKeys.isEmpty()) {
            EmptyHint(
                title = "还没有客户端密钥",
                message = "未建密钥时，网关按「接入设置」里的全局 API Key 校验（或按无 Key 开关放行）。",
            )
        } else {
            SectionLabel("已创建的密钥 · ${state.clientKeys.size}")
            state.clientKeys.forEachIndexed { index, key ->
                ClientKeyCard(
                    key = key,
                    usage = state.clientKeyUsages[key.id],
                    enterIndex = index + 1,
                    onToggle = { enabled -> viewModel.updateClientKey(key.copy(enabled = enabled)) },
                    onEdit = { editing = key },
                    onViewSecret = { viewingSecret = key.name to key.secret },
                    onCopySecret = {
                        clipboard.setText(AnnotatedString(key.secret))
                        Toast.makeText(context, "已复制密钥", Toast.LENGTH_SHORT).show()
                    },
                    onRotate = { viewModel.rotateClientKey(key.id) { secret -> revealSecret = key.name to secret } },
                    onResetUsage = { viewModel.resetClientKeyUsage(key.id) },
                    onDelete = { viewModel.removeClientKey(key.id) },
                )
            }
        }
    }

    if (creating || editing != null) {
        val current = editing
        ClientKeyEditDialog(
            current = current,
            models = state.models,
            onDismiss = {
                creating = false
                editing = null
            },
            onConfirm = { name, secret, models, limits ->
                if (current == null) {
                    viewModel.addClientKey(name, secret, models, limits) { created ->
                        revealSecret = created.name to created.secret
                    }
                } else {
                    viewModel.updateClientKey(
                        current.copy(name = name, allowedModels = models, limits = limits),
                    )
                }
                creating = false
                editing = null
            },
        )
    }

    revealSecret?.let { (name, secret) ->
        AlertDialog(
            onDismissRequest = { revealSecret = null },
            title = { Text("密钥已生成：$name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer {
                        Text(
                            text = secret,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                .padding(12.dp),
                        )
                    }
                    Text(
                        text = "密钥已生成。之后可以在列表卡片随时查看或再次复制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { revealSecret = null }) { Text("已保存") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(secret))
                        Toast.makeText(context, "已复制密钥", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("复制") }
            },
        )
    }

    viewingSecret?.let { (name, secret) ->
        AlertDialog(
            onDismissRequest = { viewingSecret = null },
            title = { Text("查看密钥：$name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer {
                        Text(
                            text = secret,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                .padding(12.dp),
                        )
                    }
                    Text(
                        text = "密钥保存在本机，支持长按选择或直接复制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingSecret = null }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(secret))
                        Toast.makeText(context, "已复制密钥", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("复制") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientKeyCard(
    key: ClientKey,
    usage: KeyUsage?,
    enterIndex: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onViewSecret: () -> Unit,
    onCopySecret: () -> Unit,
    onRotate: () -> Unit,
    onResetUsage: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(key.id) { mutableStateOf(false) }
    var confirmRotate by remember(key.id) { mutableStateOf(false) }

    SectionCard(enterIndex = enterIndex) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(key.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = key.maskedSecret,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = key.enabled, onCheckedChange = onToggle)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        KeyFactRow("可用模型", if (key.allowedModels.isEmpty()) "不限" else key.allowedModels.joinToString("、"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        KeyFactRow("用量限额", if (key.limits.isEmpty()) "不限" else key.limits.joinToString("；") { limitText(it) })
        usage?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            KeyFactRow(
                "已用",
                "${formatCount(it.tokens)} tokens · ${formatMicroUsd(it.costMicros)} 美元",
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallAction("查看密钥", onViewSecret)
            SmallAction("复制", onCopySecret)
            SmallAction("编辑", onEdit)
            SmallAction("轮换", { confirmRotate = true })
            SmallAction("重置用量", onResetUsage)
            SmallAction("删除", { confirmDelete = true })
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("轮换密钥") },
            text = { Text("将生成新的密钥。名称、模型白名单、限额与用量历史保持不变，旧密钥立即失效。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    onRotate()
                }) { Text("轮换") }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("取消") } },
        )
    }
    if (confirmDelete) {
        ConfirmDestructiveDialog(
            title = "删除密钥",
            body = "删除后该密钥立即失效，其用量记录一并清除。",
            confirmText = "删除",
            onDismiss = { confirmDelete = false },
            onConfirm = { onDelete() },
        )
    }
}

@Composable
private fun KeyFactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.32f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.68f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private fun limitText(limit: KeyLimit): String {
    val period = when (limit.period) {
        LimitPeriod.DAY -> "每天"
        LimitPeriod.WEEK -> "每周"
        LimitPeriod.MONTH -> "每月"
        LimitPeriod.HOURS -> "每 ${limit.n} 小时"
        LimitPeriod.DAYS -> "每 ${limit.n} 天"
    }
    val caps = buildList {
        if (limit.maxTokens > 0) add("${formatCount(limit.maxTokens)} tokens")
        if (limit.maxCostMicros > 0) add("${formatMicroUsd(limit.maxCostMicros)} 美元")
    }
    return "$period " + caps.joinToString(" / ")
}

/**
 * 新建/编辑密钥的对话框。
 *
 * 限额按「周期 + N + 两个上限」录入；同一周期只保留一条（重复窗口的两条限额
 * 在台账里会被当成同一窗口各算一遍，语义上说不通）。
 */
@Composable
private fun ClientKeyEditDialog(
    current: ClientKey?,
    models: List<RoutedModel>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, secret: String?, models: List<String>, limits: List<KeyLimit>) -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var selectedModels by remember { mutableStateOf(current?.allowedModels ?: emptyList()) }
    var manualInput by remember { mutableStateOf("") }
    var pickingModels by remember { mutableStateOf(false) }
    var limits by remember { mutableStateOf(current?.limits ?: emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "新建密钥" else "编辑密钥") },
        text = {
            DialogContentColumn {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (current == null) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("自定义密钥（留空自动生成）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 结构化可用模型编辑
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selectedModels.isEmpty()) "可用模型（不限）" else "可用模型 · ${selectedModels.size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    SmallAction("从模型列表选择") { pickingModels = true }
                }

                if (selectedModels.isEmpty()) {
                    Text(
                        text = "未配置白名单时允许调用所有模型。可从列表勾选或手动添加前缀通配/路由组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        selectedModels.forEach { modelId ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                SmallAction("移除") {
                                    selectedModels = selectedModels.filter { it != modelId }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        label = { Text("手动添加") },
                        placeholder = { Text("provider/model、provider/* 或 group/组名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    SmallAction("添加") {
                        val trimmed = manualInput.trim()
                        if (trimmed.isNotEmpty() && trimmed !in selectedModels) {
                            selectedModels = selectedModels + trimmed
                            manualInput = ""
                        }
                    }
                }

                Text(
                    text = "支持 provider/model、以 /* 结尾的前缀通配、group/<组名>",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("用量限额", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    SmallAction("添加") { limits = limits + KeyLimit(LimitPeriod.DAY) }
                }
                limits.forEachIndexed { index, limit ->
                    LimitEditorRow(
                        limit = limit,
                        onChange = { next -> limits = limits.toMutableList().also { it[index] = next } },
                        onRemove = { limits = limits.filterIndexed { i, _ -> i != index } },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim().ifEmpty { "未命名密钥" },
                        secret.trim().takeIf { it.isNotEmpty() && current == null },
                        selectedModels,
                        limits,
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (pickingModels) {
        val catalogKeys = remember(models) { models.map { it.fullId }.toSet() }
        val preselected = remember(selectedModels, catalogKeys) {
            selectedModels.filter { it in catalogKeys }.toSet()
        }
        MultiSelectSheet(
            title = "选择可用模型",
            subtitle = "勾选允许调用的模型，确认后合并进白名单",
            items = models,
            keyOf = { it.fullId },
            labelOf = { it.fullId },
            metaOf = { it.providerName },
            emptyHint = "当前没有可用模型，请先在供应商页面配置账号",
            confirmLabel = { "添加 $it 项" },
            preselected = preselected,
            onDismiss = { pickingModels = false },
            onConfirm = { picked ->
                val pickedIds = picked.map { it.fullId }
                // 按目录顺序去重合并：保留原有非目录项（如通配符、组），再合并新选的模型
                val merged = (selectedModels + pickedIds).distinct()
                selectedModels = merged
                pickingModels = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitEditorRow(limit: KeyLimit, onChange: (KeyLimit) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LimitPeriod.entries.forEach { period ->
                    ChoiceChip(
                        text = when (period) {
                            LimitPeriod.DAY -> "日"
                            LimitPeriod.WEEK -> "周"
                            LimitPeriod.MONTH -> "月"
                            LimitPeriod.HOURS -> "小时"
                            LimitPeriod.DAYS -> "天"
                        },
                        selected = limit.period == period,
                        onClick = { onChange(limit.copy(period = period)) },
                    )
                }
            }
            SmallAction("移除", onRemove)
        }
        if (limit.period == LimitPeriod.HOURS || limit.period == LimitPeriod.DAYS) {
            OutlinedTextField(
                value = limit.n.toString(),
                onValueChange = { text -> text.toIntOrNull()?.let { onChange(limit.copy(n = it.coerceAtLeast(1))) } },
                label = { Text("每 N ${if (limit.period == LimitPeriod.HOURS) "小时" else "天"}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (limit.maxTokens > 0) limit.maxTokens.toString() else "",
                onValueChange = { text ->
                    onChange(limit.copy(maxTokens = text.toLongOrNull()?.coerceAtLeast(0) ?: 0L))
                },
                label = { Text("token 上限") },
                placeholder = { Text("0 不限") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = if (limit.maxCostMicros > 0) formatMicroUsd(limit.maxCostMicros) else "",
                onValueChange = { text ->
                    val usd = text.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    onChange(limit.copy(maxCostMicros = (usd * 1_000_000.0).toLong()))
                },
                label = { Text("美元上限") },
                placeholder = { Text("0 不限") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
