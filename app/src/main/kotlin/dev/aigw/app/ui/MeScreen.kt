package dev.aigw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.app.ui.theme.formatHex
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
        // 顶部事实陈述：全部取自可核实的运行状态
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (state.running) "本地网关 · 运行中" else "本地网关 · 已停止",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "监听 :${state.port} · 供应商 ${state.providers.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${state.providers.size} 项",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        EditorialGroupHeader("01", "外观风格 · APPEARANCE")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EditorialSwitchRow(
                index = "01.1",
                title = "动态取色 · DYNAMIC COLOR",
                subtitle = "跟随系统壁纸生成配色（Android 12 及以上）",
                checked = state.dynamicColor,
                onCheckedChange = { viewModel.setDynamicColor(it) },
            )
            EditorialRow(
                "01.2",
                "外观主题 · THEME",
                "明暗模式、配色方案与金色文字",
                appearanceMeta(state),
            ) { open(SubPage.AppearanceTheme) }
        }

        EditorialGroupHeader("02", "核心功能偏好 · CORE FUNCTIONS")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EditorialRow("02.1", "接入设置 · ENDPOINTS", "端口与 API Key", ":${state.port}") { open(SubPage.ApiSettings) }
            EditorialRow(
                "02.2",
                "安全防护 · SECURITY",
                "反审核脱敏与账号限速",
                if (state.security.sanitizeEnabled) "脱敏开启" else "脱敏关闭",
            ) { open(SubPage.SecurityProtection) }
            EditorialRow(
                "02.3",
                "额度中心 · CREDITS",
                "按供应商查看额度包明细",
                if (state.pool.creditsKnown > 0) formatNumber(state.pool.totalCredits) else "",
            ) { open(SubPage.CreditCenter) }
            EditorialRow("02.4", "用量统计 · TELEMETRY", "请求数、token 与成功率", formatHumanTokens(state.today.totalTokens)) {
                open(SubPage.Usage)
            }
            EditorialRow(
                "02.5",
                "代理设置 · NETWORK PROXY",
                "境外供应商需要走代理",
                if (state.proxy.usable) "已启用" else "未启用",
            ) { open(SubPage.Proxy) }
            EditorialRow(
                "02.6",
                "保活与权限 · BACKGROUND",
                "通知、电池优化与后台限制",
                if (state.keepAlive?.allGood == true) "就绪" else "待配置",
            ) { open(SubPage.KeepAlive) }
            EditorialRow(
                "02.7",
                "数据管理 · STORAGE AUDIT",
                "存储占用、清理与截断",
                StorageAudit.formatSize(state.storage?.totalChars ?: 0L),
            ) {
                viewModel.refreshStorage()
                open(SubPage.DataManagement)
            }
        }

        EditorialGroupHeader("03", "排查诊断 · DIAGNOSTICS")
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

        EditorialGroupHeader("04", "架构关于 · ABOUT THE ENGINE")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        EditorialRow(
            "04.1",
            "检查更新 · CHECK UPDATE",
            "从 GitHub Releases 获取最新版本",
            if (state.updateChecking) "检查中…" else "",
        ) { if (!state.updateChecking) viewModel.checkUpdate() }

        EditorialRow(
            "04.2",
            "项目地址 · GITHUB",
            "在浏览器打开源码仓库",
            "GitHub",
        ) { viewModel.openProjectRepo() }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动检查更新", style = MaterialTheme.typography.titleSmall)
                Text(
                    "打开应用时后台检测，每天最多一次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.autoCheckUpdate,
                onCheckedChange = { viewModel.setAutoCheckUpdate(it) },
            )
        }
    }
}

/**
 * 编辑式条目：左侧等宽编号 + 标题 + 灰色副标题，右侧真实元数据（为空则不显示）+ 金色箭头。
 */
@Composable
private fun EditorialRow(
    index: String,
    title: String,
    subtitle: String,
    meta: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().pressScale().clickable(onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = "→", style = MaterialTheme.typography.bodyMedium, color = LocalAppColors.current.goldStart)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 编辑式开关条目：编号 + 标题 + 副标题 + 右侧 Switch（与 [EditorialRow] 同一栅格）。 */
@Composable
private fun EditorialSwitchRow(
    index: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 外观主题条目的元信息：当前模式 + 是否自定义金色。 */
private fun appearanceMeta(state: AppUiState): String {
    val mode = when (state.themeMode) {
        dev.aigw.app.ui.theme.ThemeMode.Light -> "浅色"
        dev.aigw.app.ui.theme.ThemeMode.Dark -> "深色"
        dev.aigw.app.ui.theme.ThemeMode.System -> "自动"
    }
    val customGold = formatHex(state.goldStart) != "#D4A843" || formatHex(state.goldEnd) != "#B88A20"
    return mode + " · " + (if (customGold) formatHex(state.goldStart) else "默认金")
}

private fun appVersion(context: android.content.Context): String = runCatching {
    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
    val name = pkg.versionName.orEmpty()
    @Suppress("DEPRECATION")
    val code = pkg.versionCode
    "${name.ifEmpty { "—" }}（build $code）"
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
    // 存储占用只在数据管理页刷过，本页「存储」卡片读的是同一份 state，
    // 不主动刷新会恒为 0；进入本页时算一次（代价高，不放进 refresh）。
    LaunchedEffect(Unit) { viewModel.refreshStorage() }
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
