package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.app.ui.providers.ProviderUiRegistry
/**
 * 供应商详情：登录入口 + 账号列表 + 专属设置。
 *
 * 账号卡片里的「专属信息区」由该供应商自己的 UI 组件渲染（[ProviderUiRegistry]），
 * 所以这里不需要认识设备指纹、签到、兑换码这些概念。
 */
@Composable
fun ProviderDetailScreen(
    providerId: String,
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onEditCustom: (String) -> Unit,
) {
    val ui = ProviderUiRegistry.require(providerId)
    val info = state.providerOf(providerId)
    val accounts = state.accountsOf(providerId)

    // 缓存回调集合：30+ 个 lambda 每次重组重建会让账号卡片永远无法跳过重组。
    // creditPacks/taskLists 随 state 变化，用 rememberUpdatedState 读取最新值。
    val creditPacks = rememberUpdatedState(state.creditPacks)
    val taskLists = rememberUpdatedState(state.taskLists)
    val actions = remember(viewModel, providerId) {
        ProviderUiActions(
            // 登录一律交给系统浏览器：本机 WebView 渲染异常（整页缩放错乱、输入框文字镜像），
            // 网关会在本地临时监听回调端口接住登录结果。
            onWebLogin = { id -> viewModel.beginBrowserLogin(id) },
            onDeviceLogin = { id, region -> viewModel.beginDeviceLogin(id, region) },
            onSmsLogin = { id, phone, code, ccode, onResult ->
                if (code.isEmpty()) {
                    viewModel.sendSmsCode(id, phone, ccode, onResult)
                } else {
                    viewModel.smsLogin(id, phone, code, ccode, onResult)
                }
            },
            onImport = { id, raw -> viewModel.importCredentials(id, raw) },
            onAddCustomAccount = { id, nickname, apiKey, models -> viewModel.addCustomAccount(id, nickname, apiKey, models) },
            onRenameAccount = { id, uid, nickname -> viewModel.renameAccount(id, uid, nickname) },
            accountApiKeyOf = { id, uid -> viewModel.accountApiKey(id, uid) },
            customModelsOf = { id -> viewModel.customModels(id) },
            customBaseUrlOf = { id -> viewModel.customBaseUrl(id) },
            onFetchCustomModels = { baseUrl, apiKey, onResult ->
                viewModel.fetchCustomModels(baseUrl, apiKey, onResult)
            },
            onRefreshCredits = { id, uid -> viewModel.refreshCredits(id, uid) },
            onAction = { id, uid, action, payload -> viewModel.performAction(id, uid, action, payload) },
            onCheckDeviceAuth = { id, region -> viewModel.checkDeviceAuth(id, region) },
            onToggleAccount = { id, uid, enabled -> viewModel.setAccountEnabled(id, uid, enabled) },
            onRemoveAccount = { id, uid -> viewModel.removeAccount(id, uid) },
            onUpdateSecret = { id, uid, secret, notice ->
                viewModel.updateAccountSecret(id, uid, secret)
                viewModel.notice(notice)
            },
            deviceIdOf = { id, uid -> viewModel.deviceIdOf(id, uid) },
            regionOf = { id, uid -> viewModel.accountRegion(id, uid) },
            providerOptionOf = { id, key, fallback -> viewModel.providerOption(id, key, fallback) },
            onUpdateProviderOption = { id, key, value -> viewModel.updateProviderOption(id, key, value) },
            creditPacksOf = { id, uid ->
                creditPacks.value["$id/$uid"].orEmpty()
            },
            onLoadCreditPacks = { id, uid -> viewModel.loadCreditPacks(id, uid) },
            taskListOf = { id, uid -> taskLists.value["$id/$uid"] },
            onLoadTaskList = { id, uid -> viewModel.loadTaskList(id, uid) },
            onRegenerateDeviceId = { id, uid -> viewModel.regenerateDeviceId(id, uid) },
            onSetDeviceId = { id, uid, value -> viewModel.setDeviceId(id, uid, value) },
            onNotice = { viewModel.notice(it) },
        )
    }

    PageScaffold(
        title = info?.displayName ?: providerId,
        subtitle = "${accounts.size} 个账号 · 可用 ${accounts.count { it.usable }}",
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(6.dp),
            )
        },
        actions = {
            if (providerId.startsWith(ProviderUiRegistry.CUSTOM_PREFIX)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "编辑供应商",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onEditCustom(providerId) }
                        .padding(6.dp),
                )
            }
        },
    ) {
        // LoginEntry 自带卡片，这里不再包一层，避免双层卡片
        ui.LoginEntry(accounts, actions)

        // 账号列表由供应商组件自管（如 WorkBuddy 按国内外分区）时，不再重复渲染通用账号卡
        if (accounts.isNotEmpty() && !ui.managesOwnAccounts) {
            SectionCard(enterIndex = 0) {
                SectionLabel("账号")
                accounts.forEachIndexed { index, account ->
                    AccountRow(account, ui, actions, index = index)
                }
                OutlineActionButton("刷新额度") { actions.onRefreshCredits(providerId, null) }
            }
        }

        ui.SettingsSection(viewModel.providerSettings(providerId)) { updated ->
            viewModel.updateProviderSettings(providerId, updated)
        }
    }
}

@Composable
private fun AccountRow(
    account: dev.aigw.core.pool.AccountStatus,
    ui: dev.aigw.app.ui.providers.ProviderUi,
    actions: ProviderUiActions,
    index: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth().staggeredAppear(index),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.nickname.ifEmpty { account.uid },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // 额度与明细交给各供应商自己的组件展示（它们知道该怎么解读），这里只报状态
                Text(
                    text = accountStatusText(account),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = account.enabled,
                onCheckedChange = { actions.onToggleAccount(account.providerId, account.uid, it) },
            )
        }

        ui.AccountExtra(account, actions)

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LinkText("删除账号") { actions.onRemoveAccount(account.providerId, account.uid) }
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
