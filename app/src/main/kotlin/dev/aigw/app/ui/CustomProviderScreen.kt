package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.provider.custom.CustomProvider

/**
 * 自定义供应商的新建/编辑。
 *
 * 一屏填完三件事：连得上（地址 + Key）、看得见模型（拉取或手填）、存得下（名称与短名）。
 * 老流程把 Key 与模型留到详情页才可填，用户保存完拿到的是一个 0 账号、用不了的供应商。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomProviderScreen(
    existing: CustomProviderConfig?,
    existingKeys: List<String>,
    onSave: (CustomProviderConfig) -> Unit,
    onDelete: ((String) -> Unit)?,
    onFetchModels: (baseUrl: String, apiKey: String, onResult: (List<String>, String) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    // 短名默认随名称派生，用户手动改过之后就不再覆盖
    var key by remember { mutableStateOf(existing?.key.orEmpty()) }
    var keyEdited by remember { mutableStateOf(existing != null) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var apiKeysText by remember { mutableStateOf(existingKeys.joinToString("\n")) }
    var modelsText by remember { mutableStateOf(existing?.models.orEmpty().joinToString("\n")) }
    var advanced by remember { mutableStateOf(existing != null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var fetchError by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val baseUrlValue = baseUrl.trim()
    val normalizedBase = remember(baseUrlValue) { CustomProvider.normalizeBaseUrl(baseUrlValue) }
    val firstKey = apiKeysText.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val parsedModels = modelsText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    PageScaffold(
        title = if (existing == null) "新建供应商" else "编辑供应商",
        subtitle = "OpenAI 兼容接口",
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clickable(onClick = onBack).padding(6.dp),
            )
        },
    ) {
        SectionCard {
            SectionLabel("连接")
            OutlinedTextField(
                value = name,
                onValueChange = { value ->
                    name = value
                    if (!keyEdited) key = CustomProviderConfig.deriveKey(value)
                },
                label = { Text("名称") },
                placeholder = { Text("给自己看的名字") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("接口地址") },
                placeholder = { Text("https://api.example.com") },
                singleLine = true,
                supportingText = {
                    Text(
                        text = if (normalizedBase.isEmpty()) {
                            "域名或带 /v1 的地址都行，/v1 会自动补齐"
                        } else {
                            "实际请求 $normalizedBase/chat/completions"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKeysText,
                onValueChange = { apiKeysText = it },
                label = { Text("API Key") },
                placeholder = { Text("一行一个，多个 Key 轮流使用") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("模型")
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.clickable(enabled = !fetching) {
                        when {
                            baseUrlValue.isEmpty() -> fetchError = "先填写接口地址"
                            firstKey.isEmpty() -> fetchError = "先填写 API Key"
                            else -> {
                                fetchError = ""
                                fetching = true
                                onFetchModels(baseUrlValue, firstKey) { list, message ->
                                    fetching = false
                                    if (message.isEmpty()) fetched = list else fetchError = message
                                }
                            }
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = when {
                            fetching -> "拉取中"
                            parsedModels.isEmpty() -> "获取模型"
                            else -> "重新获取"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (fetchError.isNotEmpty()) {
                Text(
                    text = fetchError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = modelsText,
                onValueChange = { modelsText = it },
                label = { Text("模型 id") },
                placeholder = { Text("一行一个；也可以点右上角获取") },
                minLines = 3,
                supportingText = { Text("已填 ${parsedModels.size} 个") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advanced = !advanced },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "高级",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (advanced) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (advanced) {
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        keyEdited = true
                    },
                    label = { Text("模型前缀") },
                    singleLine = true,
                    enabled = existing == null,
                    supportingText = {
                        Text(
                            text = if (key.isEmpty()) {
                                "自动由名称生成；模型对外 id 为 custom:<前缀>/<模型名>"
                            } else {
                                "模型对外 id：custom:$key/<模型名>"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard {
            if (error.isNotEmpty()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    val normalizedKey = CustomProviderConfig.normalizeKey(key)
                    error = when {
                        name.isBlank() -> "请填写名称"
                        baseUrlValue.isEmpty() -> "请填写接口地址"
                        firstKey.isEmpty() -> "请填写 API Key"
                        parsedModels.isEmpty() -> "至少填一个模型 id，或点「获取模型」"
                        normalizedKey.isEmpty() -> "名称里没有可用的英文字母，请在「高级」里填一个模型前缀"
                        else -> ""
                    }
                    if (error.isEmpty()) {
                        onSave(
                            CustomProviderConfig(
                                key = normalizedKey,
                                name = name.trim(),
                                baseUrl = baseUrlValue,
                                apiKeys = apiKeysText.lines(),
                                models = parsedModels,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (existing == null) "保存并启用" else "保存")
            }
            if (existing != null && onDelete != null) {
                OutlineActionButton("删除该供应商") { onDelete(existing.key) }
            }
        }
    }

    fetched?.let { list ->
        MultiSelectSheet(
            title = "选择模型",
            subtitle = "共拉到 ${list.size} 个",
            items = list,
            keyOf = { it },
            labelOf = { it },
            emptyHint = "上游没有返回模型",
            confirmLabel = { count -> "保存 $count 个" },
            onDismiss = { fetched = null },
            onConfirm = { selected ->
                modelsText = selected.joinToString("\n")
                fetched = null
            },
            preselected = remember(list) { existing?.models.orEmpty().toSet() },
        )
    }
}
