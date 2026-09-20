package dev.aigw.app.ui.providers.custom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.coolingText

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
        if (accounts.isNotEmpty()) {
            SectionCard {
                OutlineActionButton("刷新额度") { actions.onRefreshCredits(id, null) }
            }
        }
    }

    /** 「添加账号」按钮：点击展开表单（名称 + API Key + 拉取模型 + 保存）。 */
    @Composable
    private fun AddAccountSection(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        SectionCard {
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
            ModelsSection(accounts, actions, apiKey.trim())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        actions.onAddCustomAccount(id, nickname, apiKey)
                        nickname = ""
                        apiKey = ""
                        expanded = false
                    },
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("添加") }
                OutlineActionButton("取消") { expanded = false }
            }
        }
    }

    /** 模型管理：云端拉取 + 手动编辑，保存到供应商配置。拉模型优先用填写的 key，否则用第一个已有账号的。 */
    @Composable
    private fun ModelsSection(accounts: List<AccountStatus>, actions: ProviderUiActions, apiKey: String) {
        var models by rememberSaveable { mutableStateOf(actions.customModelsOf(id).joinToString("\n")) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("模型")
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.clickable {
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
                    actions.onFetchCustomModels(baseUrl, key) { fetched, error ->
                        if (error.isNotEmpty()) {
                            actions.onNotice(error)
                        } else {
                            models = fetched.joinToString("\n")
                            actions.onNotice("已拉取 ${fetched.size} 个模型，记得保存")
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
                    text = "拉取模型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        OutlinedTextField(
            value = models,
            onValueChange = { models = it },
            label = { Text("一行一个模型 id") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlineActionButton("保存模型列表") {
            actions.onUpdateCustomModels(
                id,
                models.lines().map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
    }

    /** 账号卡片：点击展开详情（名称编辑、API Key 查看、启用开关、删除）。 */
    @Composable
    private fun AccountDetailCard(account: AccountStatus, actions: ProviderUiActions) {
        var expanded by rememberSaveable(account.uid) { mutableStateOf(false) }
        SectionCard {
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
                        text = account.coolingText(),
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
        var showKey by remember { mutableStateOf(false) }
        val apiKey = actions.accountApiKeyOf(id, account.uid)

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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (showKey) apiKey else apiKey.masked(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showKey) "隐藏" else "显示",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { showKey = !showKey },
                )
            }
            Text(
                text = "UID ${account.uid}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                if (account.cooling) {
                    ChoiceChip("解除冷却", false) { actions.onClearCooldown(id, account.uid) }
                }
                ChoiceChip("删除账号", false) { actions.onRemoveAccount(id, account.uid) }
            }
        }
    }

    private fun String.masked(): String =
        if (length <= 8) "••••••••" else "${take(4)}••••••••${takeLast(4)}"
}
