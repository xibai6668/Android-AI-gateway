package dev.aigw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.dp
import dev.aigw.core.failover.CircuitBreakerConfig
import dev.aigw.core.failover.ConcurrencyConfig
import dev.aigw.core.failover.ModelCooldownConfig
import dev.aigw.core.failover.RetryConfig

/**
 * 并发与容错设置页：
 * - 并发闸门：全局/单账号在途上限 + 排队超时（新能力，作用于上游请求全过程）；
 * - 重试退避：单供应商内换号重试次数与指数退避（已有能力，此前无 UI）；
 * - 熔断器：连续失败阈值、静默时长、半开恢复成功数（已有能力，此前无 UI）。
 *
 * 全部参数即时落盘、即时生效，无需重启网关。
 */
@Composable
fun ResilienceScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var editTarget by remember { mutableStateOf<ResilienceEditTarget?>(null) }

    PageScaffold(
        title = "并发与容错",
        subtitle = "并发闸门、重试退避与熔断",
        leading = { BackButton(onBack) },
    ) {
        val failover = state.failover

        ConcurrencyComponent(
            config = failover.concurrency,
            onOpenEdit = { editTarget = ResilienceEditTarget.Concurrency },
        )
        Spacer(Modifier.height(4.dp))
        RetryComponent(
            enabled = failover.enabled,
            config = failover.retry,
            onToggle = { viewModel.updateFailoverSettings(failover.copy(enabled = it)) },
            onOpenEdit = { editTarget = ResilienceEditTarget.Retry },
        )
        Spacer(Modifier.height(4.dp))
        CircuitBreakerComponent(
            config = failover.circuitBreaker,
            onOpenEdit = { editTarget = ResilienceEditTarget.CircuitBreaker },
        )
        Spacer(Modifier.height(4.dp))
        ModelCooldownComponent(
            config = failover.modelCooldown,
            onOpenEdit = { editTarget = ResilienceEditTarget.ModelCooldown },
        )
        Spacer(Modifier.height(4.dp))
        AccountStrategyComponent(
            current = failover.accountStrategy,
            onSelect = { viewModel.updateFailoverSettings(failover.copy(accountStrategy = it)) },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "全部参数保存后立即生效，无需重启网关。并发在途指「已向上游发起、尚未返回」的请求；" +
                "排队超时后该次尝试换下一个账号/供应商。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    editTarget?.let { target ->
        when (target) {
            ResilienceEditTarget.Concurrency -> ConcurrencyEditDialog(
                current = state.failover.concurrency,
                onDismiss = { editTarget = null },
                onConfirm = { next ->
                    viewModel.updateFailoverSettings(state.failover.copy(concurrency = next))
                    editTarget = null
                },
            )
            ResilienceEditTarget.Retry -> RetryEditDialog(
                current = state.failover.retry,
                onDismiss = { editTarget = null },
                onConfirm = { next ->
                    viewModel.updateFailoverSettings(state.failover.copy(retry = next))
                    editTarget = null
                },
            )
            ResilienceEditTarget.CircuitBreaker -> CircuitBreakerEditDialog(
                current = state.failover.circuitBreaker,
                onDismiss = { editTarget = null },
                onConfirm = { next ->
                    viewModel.updateFailoverSettings(state.failover.copy(circuitBreaker = next))
                    editTarget = null
                },
            )
            ResilienceEditTarget.ModelCooldown -> ModelCooldownEditDialog(
                current = state.failover.modelCooldown,
                onDismiss = { editTarget = null },
                onConfirm = { next ->
                    viewModel.updateFailoverSettings(state.failover.copy(modelCooldown = next))
                    editTarget = null
                },
            )
        }
    }
}

private enum class ResilienceEditTarget { Concurrency, Retry, CircuitBreaker, ModelCooldown }

/**
 * 账号选择策略：决定「同一供应商内先烧哪个号的额度」。
 *
 * 策略只影响顺序，不改变可用性过滤（禁用/冷却/在途满载仍会被跳过）。
 */
@Composable
private fun AccountStrategyComponent(
    current: dev.aigw.core.failover.AccountStrategy,
    onSelect: (dev.aigw.core.failover.AccountStrategy) -> Unit,
) {
    SectionLabel("账号选择策略")
    SectionCard(enterIndex = 4) {
        val options = listOf(
            dev.aigw.core.failover.AccountStrategy.SMART to "优先级优先，同级比余额",
            dev.aigw.core.failover.AccountStrategy.ORDER to "严格按优先级顺序，不随机",
            dev.aigw.core.failover.AccountStrategy.ROTATE to "轮流使用，均摊负载",
            dev.aigw.core.failover.AccountStrategy.USAGE to "余额最多的先用",
            dev.aigw.core.failover.AccountStrategy.PACE to "快重置的先花，少让额度作废",
        )
        options.forEach { (strategy, description) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = strategy.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (strategy == current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ChoiceChip(
                    text = if (strategy == current) "已选" else "选择",
                    selected = strategy == current,
                    onClick = { onSelect(strategy) },
                )
            }
            if (strategy != options.last().first) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ConcurrencyComponent(config: ConcurrencyConfig, onOpenEdit: () -> Unit) {
    SectionLabel("并发闸门")
    SectionCard(enterIndex = 0) {
        ParamRow("单账号在途上限", if (config.maxPerAccount == 0) "不限" else "${config.maxPerAccount} 条")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("全局在途上限", if (config.maxGlobal == 0) "不限" else "${config.maxGlobal} 条")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("满载排队上限", "${config.queueTimeoutMs} 毫秒")
        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改并发参数") { onOpenEdit() }
    }
}

@Composable
private fun RetryComponent(
    enabled: Boolean,
    config: RetryConfig,
    onToggle: (Boolean) -> Unit,
    onOpenEdit: () -> Unit,
) {
    SectionLabel("故障转移（单供应商内重试 + 跨供应商切换）")
    SectionCard(enterIndex = 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("启用故障转移", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "关闭后单请求只在本供应商内换号：不退避重试、也不切到其它供应商",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("最大尝试次数", "${config.maxAttempts} 次（含首次）")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("初始退避", "${config.initialBackoffMs} 毫秒")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("退避上限", "${config.maxBackoffMs} 毫秒")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("退避倍率", "×${config.backoffMultiplier}")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("随机抖动", "±${(config.jitterRatio * 100).toInt()}%")
        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改重试参数") { onOpenEdit() }
    }
}

@Composable
private fun CircuitBreakerComponent(config: CircuitBreakerConfig, onOpenEdit: () -> Unit) {
    SectionLabel("熔断器（按供应商）")
    SectionCard(enterIndex = 2) {
        ParamRow("连续失败阈值", "${config.failureThreshold} 次")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("熔断静默时长", "${config.openDurationMs / 1000} 秒")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("半开恢复成功数", "${config.halfOpenSuccessCount} 次")
        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改熔断参数") { onOpenEdit() }
    }
}

@Composable
private fun ModelCooldownComponent(config: ModelCooldownConfig, onOpenEdit: () -> Unit) {
    SectionLabel("模型级冷却")
    SectionCard(enterIndex = 3) {
        ParamRow("冷却时长", "${config.cooldownMs / 1000} 秒")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("冷却上限", "${config.maxCooldownMs / 1000} 秒")
        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改模型级冷却") { onOpenEdit() }
    }
}

@Composable
private fun ParamRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ConcurrencyEditDialog(
    current: ConcurrencyConfig,
    onDismiss: () -> Unit,
    onConfirm: (ConcurrencyConfig) -> Unit,
) {
    var perAccount by remember { mutableStateOf(current.maxPerAccount.toString()) }
    var global by remember { mutableStateOf(current.maxGlobal.toString()) }
    var timeout by remember { mutableStateOf(current.queueTimeoutMs.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改并发参数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("单账号在途上限（条，0=不限）", perAccount, 2) { perAccount = it }
                NumberField("全局在途上限（条，0=不限）", global, 3) { global = it }
                NumberField("排队超时（毫秒）", timeout, 6) { timeout = it }
                Text(
                    text = "风控敏感的账号建议设 1；上游稳定时可放宽。全局上限建议 ≥ 单账号上限 × 账号数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 解析失败回落当前值（与重试/熔断对话框一致），不能静默变成「0=不限」
                onConfirm(
                    ConcurrencyConfig(
                        maxPerAccount = current.maxPerAccount,
                        maxGlobal = current.maxGlobal,
                        queueTimeoutMs = current.queueTimeoutMs,
                    ).copy(
                        maxPerAccount = ConcurrencyConfig.clampMaxPerAccount(perAccount.toIntOrNull() ?: current.maxPerAccount),
                        maxGlobal = ConcurrencyConfig.clampMaxGlobal(global.toIntOrNull() ?: current.maxGlobal),
                        queueTimeoutMs = ConcurrencyConfig.clampQueueTimeoutMs(timeout.toLongOrNull() ?: current.queueTimeoutMs),
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RetryEditDialog(
    current: RetryConfig,
    onDismiss: () -> Unit,
    onConfirm: (RetryConfig) -> Unit,
) {
    var attempts by remember { mutableStateOf(current.maxAttempts.toString()) }
    var initial by remember { mutableStateOf(current.initialBackoffMs.toString()) }
    var maxBackoff by remember { mutableStateOf(current.maxBackoffMs.toString()) }
    var multiplier by remember { mutableStateOf(current.backoffMultiplier.toString()) }
    var jitter by remember { mutableStateOf((current.jitterRatio * 100).toInt().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改重试参数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("最大尝试次数（1-10）", attempts, 2) { attempts = it }
                NumberField("初始退避（毫秒）", initial, 5) { initial = it }
                NumberField("退避上限（毫秒）", maxBackoff, 6) { maxBackoff = it }
                NumberField("退避倍率（1.0-5.0）", multiplier, 4, allowDecimal = true) { multiplier = it }
                NumberField("随机抖动（0-100%）", jitter, 3) { jitter = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val attemptsN = (attempts.toIntOrNull() ?: current.maxAttempts).coerceIn(1, 10)
                val initialN = (initial.toLongOrNull() ?: current.initialBackoffMs).coerceIn(0L, 10_000L)
                val maxBackoffN = (maxBackoff.toLongOrNull() ?: current.maxBackoffMs).coerceIn(0L, 60_000L)
                val multiplierN = (multiplier.toDoubleOrNull() ?: current.backoffMultiplier).coerceIn(1.0, 5.0)
                val jitterN = (jitter.toIntOrNull() ?: (current.jitterRatio * 100).toInt()).coerceIn(0, 100)
                onConfirm(
                    RetryConfig(
                        maxAttempts = attemptsN,
                        initialBackoffMs = initialN,
                        maxBackoffMs = maxBackoffN,
                        backoffMultiplier = multiplierN,
                        jitterRatio = jitterN / 100.0,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CircuitBreakerEditDialog(
    current: CircuitBreakerConfig,
    onDismiss: () -> Unit,
    onConfirm: (CircuitBreakerConfig) -> Unit,
) {
    var threshold by remember { mutableStateOf(current.failureThreshold.toString()) }
    var openSeconds by remember { mutableStateOf((current.openDurationMs / 1000).toString()) }
    var halfOpen by remember { mutableStateOf(current.halfOpenSuccessCount.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改熔断参数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("连续失败阈值（1-100）", threshold, 3) { threshold = it }
                NumberField("熔断静默时长（秒，1-3600）", openSeconds, 4) { openSeconds = it }
                NumberField("半开恢复成功数（1-10）", halfOpen, 2) { halfOpen = it }
                Text(
                    text = "客户端 400 类错误不计入失败；熔断只统计上游侧错误。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    CircuitBreakerConfig(
                        failureThreshold = (threshold.toIntOrNull() ?: current.failureThreshold).coerceIn(1, 100),
                        openDurationMs = ((openSeconds.toLongOrNull() ?: (current.openDurationMs / 1000)).coerceIn(1L, 3600L)) * 1000,
                        halfOpenSuccessCount = (halfOpen.toIntOrNull() ?: current.halfOpenSuccessCount).coerceIn(1, 10),
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ModelCooldownEditDialog(
    current: ModelCooldownConfig,
    onDismiss: () -> Unit,
    onConfirm: (ModelCooldownConfig) -> Unit,
) {
    var cooldownSeconds by remember { mutableStateOf((current.cooldownMs / 1000).toString()) }
    var maxSeconds by remember { mutableStateOf((current.maxCooldownMs / 1000).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改模型级冷却") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("冷却时长（秒）", cooldownSeconds, 5) { cooldownSeconds = it }
                NumberField("冷却上限（秒）", maxSeconds, 5) { maxSeconds = it }
                Text(
                    text = "上游报「该模型已超限」类错误时，只冷却该模型：不换账号、不计熔断，冷却期内该模型直接跳过。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    ModelCooldownConfig(
                        cooldownMs = (cooldownSeconds.toLongOrNull() ?: (current.cooldownMs / 1000)).coerceIn(1L, 7200L) * 1000,
                        maxCooldownMs = (maxSeconds.toLongOrNull() ?: (current.maxCooldownMs / 1000)).coerceIn(1L, 7200L) * 1000,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    maxLen: Int,
    allowDecimal: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            // 整数字段不放行小数点：避免「1.5」这类输入被 toIntOrNull 静默丢弃后落回默认值
            val filtered = input.filter { it.isDigit() || (allowDecimal && it == '.') }
            onChange(filtered.take(maxLen))
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
