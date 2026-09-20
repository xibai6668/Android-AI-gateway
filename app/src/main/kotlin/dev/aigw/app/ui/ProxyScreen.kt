package dev.aigw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 代理设置：境外供应商（如 Antigravity）需要走代理。
 *
 * 打开总开关后默认所有上游都走代理，个别不需要的可以在下面的列表里单独关掉。
 */
@Composable
fun ProxyScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val settings = state.proxy
    var host by remember(settings.host) { mutableStateOf(settings.host) }
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember(settings.password) { mutableStateOf(settings.password) }

    PageScaffold(
        title = "代理设置",
        subtitle = if (settings.usable) "已启用 ${settings.host}:${settings.port}" else "未启用",
        leading = { BackButton(onBack) },
    ) {
        SectionCard {
            SectionLabel("代理")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用代理", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { viewModel.updateProxySettings(settings.copy(enabled = it)) },
                )
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
            OutlineActionButton("保存") {
                val parsed = port.toIntOrNull()
                if (parsed == null || parsed !in 1..65535) {
                    viewModel.notice("端口需要在 1~65535 之间")
                } else {
                    viewModel.updateProxySettings(
                        settings.copy(
                            host = host.trim(),
                            port = parsed,
                            username = username.trim(),
                            password = password,
                        ),
                    )
                    viewModel.notice("代理设置已保存")
                }
            }
        }

        SectionCard {
            SectionLabel("走代理的供应商")
            if (state.providers.isEmpty()) {
                EmptyHint("还没有供应商", "内置供应商会在启动时自动登记。")
            } else {
                for (info in state.providers) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = info.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = info.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = info.id !in settings.excludedProviderIds,
                            onCheckedChange = { checked ->
                                val excluded = if (checked) {
                                    settings.excludedProviderIds - info.id
                                } else {
                                    settings.excludedProviderIds + info.id
                                }
                                viewModel.updateProxySettings(settings.copy(excludedProviderIds = excluded))
                            },
                        )
                    }
                }
            }
        }
    }
}
