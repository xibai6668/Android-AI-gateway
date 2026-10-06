package dev.aigw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import dev.aigw.core.usage.StorageAudit

@Composable
fun MeScreen(state: AppUiState, viewModel: AppViewModel, open: (SubPage) -> Unit) {
    val context = LocalContext.current
    // 点标题「我的」弹出作者信息
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    PageScaffold(
        title = "我的",
        subtitle = "网关与偏好设置",
        onTitleClick = { showAbout = true },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("外观")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("动态取色", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "跟随系统壁纸生成配色（Android 12 及以上）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.dynamicColor, onCheckedChange = { viewModel.setDynamicColor(it) })
            }
        }

        SectionCard(enterIndex = 1) {
            SectionLabel("功能")
            NavRow(Icons.Filled.Key, "接入设置", "端口与 API Key") { open(SubPage.ApiSettings) }
            NavRow(Icons.Filled.Shield, "安全防护", "反审核脱敏与账号限速") { open(SubPage.SecurityProtection) }
            NavRow(Icons.Filled.AccountBalanceWallet, "额度中心", "按供应商查看额度包明细") { open(SubPage.CreditCenter) }
            NavRow(Icons.Filled.BarChart, "用量统计", "请求数、token 与成功率") { open(SubPage.Usage) }
            NavRow(Icons.Filled.VpnLock, "代理设置", "境外供应商需要走代理") { open(SubPage.Proxy) }
            NavRow(Icons.Filled.BatterySaver, "保活与权限", "通知、电池优化与后台限制") { open(SubPage.KeepAlive) }
            NavRow(Icons.Filled.Storage, "数据管理", "存储占用、清理与截断") {
                viewModel.refreshStorage()
                open(SubPage.DataManagement)
            }
        }

        SectionCard(enterIndex = 2) {
            SectionLabel("排查")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("详细日志", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "记录每次调用的请求、转发、发送与返回原文，用于排查 503 等问题；关闭后立即生效",
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

        SectionCard(enterIndex = 3) {
            SectionLabel("关于")
            Text(
                text = "ai-gateway · 本地 OpenAI 兼容网关",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "把多个上游供应商（Trae / Loomy / WorkBuddy / Antigravity / 自定义）" +
                    "统一成一个 OpenAI 兼容端点，供任意客户端调用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "应用版本 ${appVersion(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun appVersion(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("—")

/** 点「我的」标题弹出的作者信息：两行居中，点任意处关闭。 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "夕白",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "3884021834",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
fun UsageScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    PageScaffold(
        title = "用量统计",
        subtitle = "累计与今日",
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clickable(onClick = onBack).padding(6.dp),
            )
        },
    ) {
        SectionCard(enterIndex = 3) {
            SectionLabel("今日")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatCell(formatNumber(state.today.requests), "请求")
                StatCell(formatNumber(state.today.success), "成功")
                StatCell(formatNumber(state.today.failed), "失败")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            TokenUsageRow("输入 Tokens", state.today.promptTokens, MaterialTheme.colorScheme.primary)
            TokenUsageRow("输出 Tokens", state.today.completionTokens, MaterialTheme.colorScheme.tertiary)
            TokenUsageRow("总计 Tokens", state.today.totalTokens, MaterialTheme.colorScheme.secondary)
        }

        SectionCard(enterIndex = 4) {
            SectionLabel("累计")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatCell(formatNumber(state.total.requests), "请求")
                StatCell(formatNumber(state.total.success), "成功")
                StatCell(formatNumber(state.total.failed), "失败")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            TokenUsageRow("输入 Tokens", state.total.promptTokens, MaterialTheme.colorScheme.primary)
            TokenUsageRow("输出 Tokens", state.total.completionTokens, MaterialTheme.colorScheme.tertiary)
            TokenUsageRow("总计 Tokens", state.total.totalTokens, MaterialTheme.colorScheme.secondary)
        }

        SectionCard(enterIndex = 5) {
            SectionLabel("最近调用")
            if (state.calls.isEmpty()) {
                EmptyHint("还没有调用记录", "客户端发起请求后，这里会显示模型、账号与耗时。")
            } else {
                for (record in state.calls.take(20)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${record.providerId}/${record.model}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${record.status} · ${record.durationMillis}ms · " +
                                "tokens ${record.totalTokens} · ${record.accountNickname.ifEmpty { record.accountUid }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard(enterIndex = 6) {
            SectionLabel("存储")
            Text(
                text = "调用记录 ${state.storedCalls} 条，占用约 ${StorageAudit.formatSize(state.storage?.totalChars ?: 0L)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/** 格式化数字：带千分位逗号 */
private fun formatNumber(n: Long): String = String.format(java.util.Locale.US, "%,d", n)

/** 人类友好的数字紧凑缩写（如 73.25M / 276.6K） */
private fun formatHumanTokens(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.2fB", tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> String.format(java.util.Locale.US, "%.2fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(java.util.Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

/** 方案 C：用量统计单项明细行 */
@Composable
private fun TokenUsageRow(
    label: String,
    tokens: Long,
    indicatorColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = formatNumber(tokens),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (tokens >= 1_000L) {
                Text(
                    text = formatHumanTokens(tokens),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
