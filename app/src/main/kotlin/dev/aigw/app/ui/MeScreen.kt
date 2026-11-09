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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.app.ui.theme.formatHex
import dev.aigw.core.usage.StorageAudit
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.usage.formatMicroUsd
import java.util.Locale

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
                "并发与容错 · RESILIENCE",
                "并发闸门、重试退避与熔断器",
                concurrencySummary(state.failover),
            ) { open(SubPage.Resilience) }
            EditorialRow(
                "02.4",
                "额度中心 · CREDITS",
                "按供应商查看额度包明细",
                if (state.pool.creditsKnown > 0) formatCount(state.pool.totalCredits) else "",
            ) { open(SubPage.CreditCenter) }
            EditorialRow("02.5", "用量统计 · TELEMETRY", "请求数、token 与成功率", formatTokens(state.today.totalTokens)) {
                open(SubPage.Usage)
            }
            EditorialRow(
                "02.6",
                "代理设置 · NETWORK PROXY",
                "多套配置，按供应商或区域决定是否走代理",
                when {
                    state.proxy.profiles.isEmpty() -> "未配置"
                    state.proxy.usable -> "使用中 ${state.proxy.activeProfile?.name.orEmpty()}"
                    else -> "${state.proxy.profiles.size} 套配置"
                },
            ) { open(SubPage.Proxy) }
            EditorialRow(
                "02.7",
                "保活与权限 · BACKGROUND",
                "通知、电池优化与后台限制",
                if (state.keepAlive?.allGood == true) "就绪" else "待配置",
            ) { open(SubPage.KeepAlive) }
            EditorialSwitchRow(
                index = "02.8",
                title = "实时状态 · LIVE STATUS",
                subtitle = "仅网关有请求时显示，点击回到 App",
                checked = state.liveNotification,
                onCheckedChange = { viewModel.setLiveNotification(it) },
            )
            EditorialRow(
                "02.9",
                "账号导入导出 · ACCOUNTS BACKUP",
                "按供应商与账号备份、迁移凭证",
                "${state.pool.total} 个账号",
            ) { open(SubPage.AccountTransfer) }
            EditorialRow(
                "02.10",
                "客户端密钥 · CLIENT KEYS",
                "谁能调用网关，可限制模型与用量",
                if (state.clientKeys.isEmpty()) "未创建" else "${state.clientKeys.size} 把",
            ) { open(SubPage.ClientKeys) }
            EditorialRow(
                "02.11",
                "路由组 · ROUTING GROUPS",
                "多个模型合成一个对外模型名",
                if (state.routingGroups.groups.isEmpty()) "未创建" else "${state.routingGroups.groups.size} 组",
            ) { open(SubPage.RoutingGroups) }
            EditorialRow(
                "02.12",
                "模型价格 · PRICING",
                "按 token 计算调用费用",
                if (state.pricing.prices.isEmpty()) "未配置" else "${state.pricing.prices.size} 个模型",
            ) { open(SubPage.ModelPricing) }
            EditorialRow(
                "02.13",
                "请求改写 · REWRITE",
                "模型别名、system 追加与思考标签",
                rewriteSummary(state.middleware),
            ) { open(SubPage.RequestRewrite) }
        }

        EditorialGroupHeader("03", "架构关于 · ABOUT THE ENGINE")
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
                // 标题固定单行：「中文 · ENGLISH」被挤成两行会破坏栅格（英文标签跑到第二行）
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            DialogContentColumn(
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
    // 存储占用不挂在全局 refresh 里（要扫全量存储键并解密，代价高），
    // 本页「存储」卡片读的是 state 里的同一份，不主动刷新会恒为 0，故进入本页时算一次。
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
            CacheHitGauge(state.today)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatCell(formatCount(state.today.requests), "请求")
                StatCell(formatCount(state.today.success), "成功")
                StatCell(formatCount(state.today.failed), "失败")
                StatCell(formatCount(state.today.aborted), "中断")
            }
            CostLine(state.today)
        }

        SectionCard(enterIndex = 4) {
            SectionLabel("累计")
            CacheHitGauge(state.total)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatCell(formatCount(state.total.requests), "请求")
                StatCell(formatCount(state.total.success), "成功")
                StatCell(formatCount(state.total.failed), "失败")
                StatCell(formatCount(state.total.aborted), "中断")
            }
            CostLine(state.total)
        }

        SectionCard(enterIndex = 5) {
            SectionLabel("存储")
            Text(
                text = "调用记录 ${state.storedCalls} 条，占用约 ${StorageAudit.formatSize(state.storage?.totalChars ?: 0L)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 费用与重试两行。
 *
 * 未定价的调用不能算进金额，也不能默默省略——并排告知「另有 N 次未定价」，
 * 否则总额看起来偏低而用户不知道原因。
 */
@Composable
private fun CostLine(stats: UsageStats) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatCell(formatCount(stats.retried), "换号救回")
        StatCell(formatMicroUsd(stats.costMicros), "费用（美元）")
        if (stats.unpriced > 0) {
            StatCell(formatCount(stats.unpriced), "未定价调用")
        }
    }
}

/**
 * 输入 token 构成环：命中缓存 / 未命中 / 输出 三段按比例分色填充，中心显示命中率。
 *
 * 环形绘制借鉴 open-claude-ui（MIT, Caho1）的 Token 仪表做法：
 * `strokeDasharray = 2πr`、`strokeDashoffset = circumference × (1 − ratio)`，
 * 各段用 offset 依次错开。没有输入 token 时只显示「—」，不画环（不编造 0%）。
 */
@Composable
private fun CacheHitGauge(stats: UsageStats) {
    val hitColor = MaterialTheme.colorScheme.tertiary
    val missColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val outputColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val hit = stats.cachedTokens.coerceAtMost(stats.promptTokens)
    val miss = (stats.promptTokens - hit).coerceAtLeast(0)
    val output = stats.completionTokens
    val total = hit + miss + output
    val percent = stats.cacheHitPercent
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(72.dp)) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                // 轨道
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (total > 0L) {
                    var start = -90f
                    for ((value, color) in listOf(
                        hit to hitColor,
                        miss to missColor,
                        output to outputColor,
                    )) {
                        if (value <= 0L) continue
                        val sweep = 360f * value / total.toFloat()
                        // 段间留 2° 缝隙，避免同色相邻时粘成一团
                        val gap = if (sweep > 6f) 2f else 0f
                        drawArc(
                            color = color,
                            startAngle = start + gap / 2,
                            sweepAngle = (sweep - gap).coerceAtLeast(0f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                        start += sweep
                    }
                }
            }
            Text(
                text = if (percent != null) "${String.format(Locale.US, "%.0f", percent)}%" else "—",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // 没有任何 token 数据时只留一个空环 + 「—」：图例全为 0 没有信息量，还会与上方的请求数重复
        if (total > 0L) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                GaugeLegend("命中缓存", hit, hitColor)
                GaugeLegend("未命中", miss, missColor)
                GaugeLegend("输出", output, outputColor)
            }
        } else {
            Text(
                text = "还没有 token 用量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GaugeLegend(label: String, tokens: Long, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(7.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatTokens(tokens),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 「并发与容错」入口的右侧元信息。
 *
 * 写成紧凑形式（不带空格）：这一行的标题是全页最长（「并发与容错 · RESILIENCE」），
 * 元信息再用「并发 单账号 1 / 全局 0」这种长文案会把英文标签挤到第二行。
 */
private fun concurrencySummary(failover: dev.aigw.core.failover.ModelFailoverSettings): String {
    val concurrency = failover.concurrency
    return when {
        concurrency.maxPerAccount == 0 && concurrency.maxGlobal == 0 -> "并发不限"
        else -> "单账号${concurrency.maxPerAccount}/全局${concurrency.maxGlobal}"
    }
}

/** 请求改写入口行的右侧摘要：一眼看出开了哪几项。 */
private fun rewriteSummary(settings: dev.aigw.core.gateway.MiddlewareSettings): String {
    val parts = buildList {
        if (settings.aliases.isNotEmpty()) add("别名 ${settings.aliases.size}")
        if (settings.systemPromptAppend.isNotBlank()) add("追加提示词")
        if (settings.stripThinkTags) add("清理思考标签")
    }
    return if (parts.isEmpty()) "未启用" else parts.joinToString("、")
}
