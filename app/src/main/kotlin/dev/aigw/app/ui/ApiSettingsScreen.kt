package dev.aigw.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import dev.aigw.core.gateway.GatewaySettings

/** 接入设置：客户端要用的地址、端口与网关鉴权 Key。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiSettingsScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val settings = state.settings
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var refreshSkew by remember(settings.refreshSkewSeconds) {
        mutableStateOf(settings.refreshSkewSeconds.toString())
    }
    var maxRotate by remember(settings.maxRotate) { mutableStateOf(settings.maxRotate.toString()) }

    PageScaffold(
        title = "接入设置",
        subtitle = "端口、Key 与网关行为",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("接入地址")
            AddressTile(state.localUrl.ifEmpty { "http://127.0.0.1:${settings.port}/v1" }) {
                clipboard.setText(AnnotatedString(state.localUrl))
                Toast.makeText(context, "已复制本机地址", Toast.LENGTH_SHORT).show()
            }
            state.lanUrls.forEach { url ->
                AddressTile(url) {
                    clipboard.setText(AnnotatedString(url))
                    Toast.makeText(context, "已复制局域网地址", Toast.LENGTH_SHORT).show()
                }
            }
        }

        SectionCard(enterIndex = 1) {
            SectionLabel("端口")
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("监听端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("同时监听局域网", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.exposeLan,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(exposeLan = it)) },
                )
            }
        }

        SectionCard(enterIndex = 2) {
            SectionLabel("API Key")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("网关鉴权 Key（留空表示不校验）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("无 Key 调用", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.allowNoKey,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(allowNoKey = it)) },
                )
            }
        }

        SectionCard(enterIndex = 3) {
            SectionLabel("默认供应商")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.providers.forEach { provider ->
                    ChoiceChip(
                        text = provider.displayName,
                        selected = (settings.defaultProvider == provider.id),
                        onClick = { viewModel.updateSettings(settings.copy(defaultProvider = provider.id)) },
                    )
                }
            }
        }

        SectionCard(enterIndex = 4) {
            SectionLabel("网关行为")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("只看可用模型")
                    Text(
                        text = "隐藏上游的非对话内部条目（嵌入、审核类模型），对 /v1/models 与模型页都生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.onlyUsableModels,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(onlyUsableModels = it)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = refreshSkew,
                    onValueChange = { refreshSkew = it.filter(Char::isDigit).take(4) },
                    label = { Text("预刷新窗口（秒）") },
                    supportingText = { Text("token 临期多久内才刷新，上限 ${GatewaySettings.MAX_REFRESH_SKEW_SECONDS}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = maxRotate,
                    onValueChange = { maxRotate = it.filter(Char::isDigit).take(2) },
                    label = { Text("最多换号次数") },
                    supportingText = { Text("单次请求最多换几个账号，1~10") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard(enterIndex = 5) {
            OutlineActionButton("保存") {
                val parsed = port.toIntOrNull()
                val parsedRotate = maxRotate.toIntOrNull()
                val parsedSkew = refreshSkew.toLongOrNull()
                when {
                    parsed == null || parsed !in 1024..65535 -> viewModel.notice("端口需要在 1024~65535 之间")
                    parsedSkew == null -> viewModel.notice("预刷新窗口需要填数字（0~3600 秒）")
                    parsedRotate == null || parsedRotate !in 1..10 -> viewModel.notice("换号次数需要在 1~10 之间")
                    else -> {
                        viewModel.updateSettings(
                            settings.copy(
                                port = parsed,
                                apiKey = apiKey.trim(),
                                refreshSkewSeconds = GatewaySettings.clampRefreshSkew(parsedSkew),
                                maxRotate = parsedRotate,
                            ),
                        )
                        viewModel.notice("已保存；端口改动需重启服务生效")
                    }
                }
            }
        }
    }
}
