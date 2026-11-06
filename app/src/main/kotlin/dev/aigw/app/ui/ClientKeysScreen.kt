package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aigw.core.gateway.ClientKey
import dev.aigw.core.gateway.KeyLimit
import dev.aigw.core.gateway.KeyUsage
import dev.aigw.core.gateway.LimitPeriod
import dev.aigw.core.usage.formatMicroUsd

/**
 * 客户端密钥页：管「谁能调用网关」。
 *
 * 与上游账号凭证是两回事：上游凭证决定用谁家的额度，这里的密钥决定谁能用这台网关。
 * 每把钥匙可限制可用模型与用量上限，超额在打上游之前就被拒绝。
 */
@Composable
fun ClientKeysScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<ClientKey?>(null) }
    var creating by remember { mutableStateOf(false) }
    var revealSecret by remember { mutableStateOf<Pair<String, String>?>(null) }

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
                    Text(
                        text = secret,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                    )
                    Text(
                        text = "这段密钥只显示这一次，请先复制保存。关闭后列表里只会显示末四位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { revealSecret = null }) { Text("已保存") } },
        )
    }
}

@Composable
private fun ClientKeyCard(
    key: ClientKey,
    usage: KeyUsage?,
    enterIndex: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
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
                    style = MaterialTheme.typography.bodySmall,
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    onDismiss: () -> Unit,
    onConfirm: (name: String, secret: String?, models: List<String>, limits: List<KeyLimit>) -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(current?.allowedModels?.joinToString("\n").orEmpty()) }
    var limits by remember { mutableStateOf(current?.limits ?: emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "新建密钥" else "编辑密钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                OutlinedTextField(
                    value = models,
                    onValueChange = { models = it },
                    label = { Text("可用模型（一行一条，留空不限）") },
                    supportingText = {
                        Text("支持 provider/model、以 /* 结尾的前缀通配、group/<组名>")
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
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
                    val parsedModels = models.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    onConfirm(
                        name.trim().ifEmpty { "未命名密钥" },
                        secret.trim().takeIf { it.isNotEmpty() && current == null },
                        parsedModels,
                        limits,
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LimitEditorRow(limit: KeyLimit, onChange: (KeyLimit) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            Box(Modifier.weight(1f))
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
