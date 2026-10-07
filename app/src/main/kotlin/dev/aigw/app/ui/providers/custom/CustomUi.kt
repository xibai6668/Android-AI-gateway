package dev.aigw.app.ui.providers.custom

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.EmptyHint
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.accountStatusText
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

class CustomUi(override val id: String) : ProviderUi {

    override val managesOwnAccounts: Boolean
        get() = true

    override fun description() = "手动导入的 OpenAI 兼容供应商"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Dns,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        AddAccountSection(accounts, actions)
        for (account in accounts) {
            AccountDetailCard(account, actions)
        }
    }

    /** 「添加账号」表单：名称 + API Key + 模型（可拉取/手填），点「添加账号」连同模型列表一并保存。 */
    @Composable
    private fun AddAccountSection(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
        var models by rememberSaveable { mutableStateOf(actions.customModelsOf(id).joinToString("\n")) }
        SectionCard(modifier = Modifier.animateContentSize()) {
            if (!expanded) {
                Button(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("添加账号") }
                return@SectionCard
            }
            SectionLabel("添加账号")
            var nickname by remember { mutableStateOf("") }
            var apiKey by remember { mutableStateOf("") }
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("名称（可选，默认用供应商名）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ModelsSection(accounts, actions, apiKey.trim(), models, onFetched = { fetchedModels = it }) { models = it }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        actions.onAddCustomAccount(
                            id,
                            nickname,
                            apiKey,
                            models.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        )
                        nickname = ""
                        apiKey = ""
                        expanded = false
                    },
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("添加账号") }
                OutlinedButton(
                    onClick = { expanded = false },
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
            }
        }
        fetchedModels?.let { fetched ->
            ModelPickerSheet(
                fetched = fetched,
                currentModels = models,
                onDismiss = { fetchedModels = null },
                onConfirm = { selected ->
                    models = selected.sorted().joinToString("\n")
                    fetchedModels = null
                    actions.onNotice("已选择 ${selected.size} 个模型")
                },
            )
        }
    }

    /** 模型管理：云端拉取 + 手动编辑。拉模型优先用填写的 key，否则用第一个已有账号的；成功后弹层勾选。 */
    @Composable
    private fun ModelsSection(
        accounts: List<AccountStatus>,
        actions: ProviderUiActions,
        apiKey: String,
        models: String,
        onFetched: (List<String>) -> Unit,
        onModelsChange: (String) -> Unit,
    ) {
        var loading by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("模型")
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.clickable(enabled = !loading) {
                    val baseUrl = actions.customBaseUrlOf(id)
                    if (baseUrl.isEmpty()) {
                        actions.onNotice("先保存供应商的接口地址，再拉取模型")
                        return@clickable
                    }
                    val key = apiKey.ifEmpty {
                        accounts.firstOrNull()?.let { actions.accountApiKeyOf(id, it.uid) }.orEmpty()
                    }
                    if (key.isEmpty()) {
                        actions.onNotice("先填写 API Key 再拉取模型")
                        return@clickable
                    }
                    loading = true
                    actions.onFetchCustomModels(baseUrl, key) { fetched, error ->
                        loading = false
                        if (error.isNotEmpty()) {
                            actions.onNotice(error)
                        } else {
                            onFetched(fetched)
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
                    text = if (loading) "拉取中..." else "拉取模型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        OutlinedTextField(
            value = models,
            onValueChange = onModelsChange,
            label = { Text("一行一个模型 id（随账号一起保存）") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /** 拉取成功后的模型勾选弹层：搜索过滤、整行点选，保存时把选中项写回模型输入框。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ModelPickerSheet(
        fetched: List<String>,
        currentModels: String,
        onDismiss: () -> Unit,
        onConfirm: (List<String>) -> Unit,
    ) {
        val selected = remember(fetched) {
            val initial = currentModels.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            mutableStateMapOf<String, Boolean>().apply { initial.forEach { put(it, true) } }
        }
        var search by remember { mutableStateOf("") }
        val selectedCount = selected.values.count { it }
        val filtered = fetched.filter { it.contains(search, ignoreCase = true) }
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "选择模型",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "已选 $selectedCount 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item { EmptyHint("没有匹配的模型", "换个关键词试试") }
                    } else {
                        items(filtered, key = { it }) { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected[model] = !(selected[model] ?: false) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(model, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Checkbox(
                                    checked = selected[model] == true,
                                    onCheckedChange = { checked -> selected[model] = checked },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onConfirm(selected.filterValues { it }.keys.sorted()) },
                    enabled = selectedCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存所选 $selectedCount 个") }
            }
        }
    }

    /** 账号卡片：点击展开详情（名称编辑、启用开关、删除），展开收起带高度过渡。 */
    @Composable
    private fun AccountDetailCard(account: AccountStatus, actions: ProviderUiActions) {
        var expanded by rememberSaveable(account.uid) { mutableStateOf(false) }
        SectionCard(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = account.nickname.ifEmpty { account.uid },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = accountStatusText(account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                AccountDetailBody(account, actions)
            }
        }
    }

    @Composable
    private fun AccountDetailBody(account: AccountStatus, actions: ProviderUiActions) {
        var nickname by remember(account.uid, account.nickname) { mutableStateOf(account.nickname) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlineActionButton("保存名称") {
                actions.onRenameAccount(id, account.uid, nickname)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用", modifier = Modifier.weight(1f))
                Switch(
                    checked = account.enabled,
                    onCheckedChange = { actions.onToggleAccount(id, account.uid, it) },
                )
            }
            Text(
                text = "额度 ${if (account.creditsKnown) account.credits.toString() else "—"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (account.detail.isNotEmpty()) {
                Text(
                    text = account.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChoiceChip("删除账号", false) { actions.onRemoveAccount(id, account.uid) }
            }
        }
    }
}
