package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.core.gateway.ProxyProfile
import dev.aigw.core.gateway.ProxyProbeStatus
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.gateway.ProxyTarget
import dev.aigw.core.gateway.ProxyType

/** 可连通=绿；超时与无法连接=红（失败时有具体原因，颜色只表达「不走这一条」）。 */
private val ProxyOkGreen = Color(0xFF2E7D32)

/**
 * 代理设置：多套配置 + 按「供应商 / 区域」决定谁走代理。
 *
 * 是否走代理只看用户勾选，没有任何隐藏的强制直连规则；WorkBuddy 与 Trae 的国内 / 国际
 * 是两个独立条目，可分别决定。
 */
@Composable
fun ProxyScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val settings = state.proxy
    var editing by remember { mutableStateOf<ProxyProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProxyProfile?>(null) }
    var excludedText by remember(settings.excludedHosts) {
        mutableStateOf(settings.excludedHosts.joinToString("\n"))
    }

    PageScaffold(
        title = "代理设置",
        subtitle = proxySummary(settings),
        leading = { BackButton(onBack) },
        actions = {
            Text(
                text = "测试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(enabled = settings.profiles.isNotEmpty()) {
                        settings.profiles.forEach { viewModel.testProxyProfile(it.id) }
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("代理配置")
            if (settings.profiles.isEmpty()) {
                EmptyHint("还没有代理配置", "新建一套填入地址与端口，就能按上游决定是否走代理。")
            } else {
                for (profile in settings.profiles) {
                    ProxyProfileRow(
                        profile = profile,
                        active = profile.id == settings.activeProfileId,
                        probe = state.proxyProbeResults[profile.id],
                        onActivate = { viewModel.activateProxyProfile(profile.id) },
                        onTest = { viewModel.testProxyProfile(profile.id) },
                        onEdit = { editing = profile },
                        onDelete = { deleting = profile },
                    )
                }
            }
            OutlineActionButton("新建配置") { creating = true }
        }

        SectionCard(enterIndex = 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("走代理的上游")
                Box(Modifier.weight(1f))
                Text(
                    text = "全选",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { viewModel.toggleAllProxyRoutes(true) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { viewModel.toggleAllProxyRoutes(false) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (state.proxyTargets.isEmpty()) {
                EmptyHint("还没有供应商", "内置供应商会在启动时自动登记。")
            } else {
                val allProxied = ProxySettings.ALL_ROUTES in settings.proxiedRoutes ||
                    state.proxyTargets.all { it.routeKey in settings.proxiedRoutes }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "全部上游",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = allProxied,
                        onCheckedChange = { viewModel.toggleAllProxyRoutes(it) },
                    )
                }
                for (target in state.proxyTargets) {
                    ProxyTargetRow(
                        target = target,
                        checked = allProxied || target.routeKey in settings.proxiedRoutes,
                        onCheckedChange = { viewModel.toggleProxyRoute(target.routeKey, it) },
                    )
                }
            }
        }

        SectionCard(enterIndex = 2) {
            SectionLabel("不走代理的地址")
            OutlinedTextField(
                value = excludedText,
                onValueChange = { excludedText = it },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        text = "每行一条；支持通配符 *、host:port、CIDR 网段。本机与内网默认直连。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            OutlineActionButton("保存排除地址") {
                val rules = excludedText
                    .split(Regex("[,，\\n\\s]+"))
                    .filter { it.isNotEmpty() }
                    .distinct()
                viewModel.updateProxySettings(settings.copy(excludedHosts = rules))
                viewModel.notice("排除地址已保存")
            }
        }
    }

    if (creating) {
        ProxyProfileDialog(
            existing = null,
            onSave = { profile ->
                creating = false
                viewModel.saveProxyProfile(profile)
                viewModel.notice("代理配置已保存")
            },
            onDismiss = { creating = false },
        )
    }
    editing?.let { profile ->
        ProxyProfileDialog(
            existing = profile,
            onSave = { updated ->
                editing = null
                viewModel.saveProxyProfile(updated)
                viewModel.notice("代理配置已保存")
            },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除代理配置") },
            text = { Text("将删除「${profile.name}」。正在使用它时，会自动切换到其它配置。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.removeProxyProfile(profile.id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

private fun proxySummary(settings: ProxySettings): String {
    val profile = settings.activeProfile ?: return "未选择代理配置"
    if (!profile.usable) return "「${profile.name}」地址未填写完整"
    return "使用「${profile.name}」 ${profile.host}:${profile.port}"
}

/** 单套配置行：名称与地址、测试状态、选用/编辑/删除。 */
@Composable
private fun ProxyProfileRow(
    profile: ProxyProfile,
    active: Boolean,
    probe: ProxyProbeState?,
    onActivate: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = 1.dp,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onActivate)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline,
                    ),
            )
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            )
            Text(
                text = if (active) "使用中" else "切换",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${profile.type.label} · ${profile.host.ifEmpty { "未填写地址" }}:${profile.port}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProxyProbeLabel(probe, Modifier.weight(1f))
            for ((label, action) in listOf(
                "测试" to onTest,
                "编辑" to onEdit,
                "删除" to onDelete,
            )) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable(onClick = action)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** 测试结果：进行中为灰，可连通为绿+延时，超时/无法连接为红+原因。 */
@Composable
private fun ProxyProbeLabel(probe: ProxyProbeState?, modifier: Modifier = Modifier) {
    val (text, color) = when (probe) {
        null -> "未测试" to MaterialTheme.colorScheme.onSurfaceVariant
        ProxyProbeState.Testing -> "测试中…" to MaterialTheme.colorScheme.onSurfaceVariant
        is ProxyProbeState.Done -> when (probe.result.status) {
            ProxyProbeStatus.OK -> "可连通 · ${probe.result.latencyMs}ms" to ProxyOkGreen
            ProxyProbeStatus.TIMEOUT -> "超时 · ${probe.result.message}" to MaterialTheme.colorScheme.error
            ProxyProbeStatus.UNREACHABLE -> "无法连接 · ${probe.result.message}" to MaterialTheme.colorScheme.error
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 2,
        modifier = modifier,
    )
}

/** 单个路由：名称按区域区分（如 Trae（国际版）），副标题是路由键与上游域名。 */
@Composable
private fun ProxyTargetRow(
    target: ProxyTarget,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = target.providerName + (target.region?.let { "（${regionLabel(it)}）" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = target.routeKey + if (target.hosts.isEmpty()) "" else " · ${target.hosts.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun regionLabel(region: String): String = when (region) {
    "cn" -> "国内"
    "global" -> "国际"
    else -> region
}

/** 新建/编辑一套代理配置。 */
@Composable
private fun ProxyProfileDialog(
    existing: ProxyProfile?,
    onSave: (ProxyProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "") }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: ProxyType.HTTP) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新建代理配置" else "编辑代理配置") },
        text = {
            DialogContentColumn {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（如：家里 / 公司）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (candidate in ProxyType.entries) {
                        ChoiceChip(
                            text = candidate.label,
                            selected = candidate == type,
                            onClick = { type = candidate },
                        )
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("代理地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotEmpty()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = port.toIntOrNull()
                when {
                    host.isBlank() -> error = "请填写代理地址"
                    parsed == null || parsed !in 1..65535 -> error = "端口需要在 1~65535 之间"
                    else -> onSave(
                        ProxyProfile(
                            // 编辑时沿用原 id；新建用时间戳，避免与已有配置撞名
                            id = existing?.id ?: "p${System.currentTimeMillis()}",
                            name = name.trim().ifEmpty { host.trim() },
                            type = type,
                            host = host.trim(),
                            port = parsed,
                            username = username.trim(),
                            password = password,
                        ),
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
