package dev.aigw.app.ui.providers.chatgpt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.QuotaPackBar
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

object ChatGPTUi : ProviderUi {

    override val id = "chatgpt"

    override fun description() = "ChatGPT 订阅额度（Codex 后端）"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        SectionCard(enterIndex = 0) {
            SectionLabel("登录")
            Button(
                onClick = { actions.onWebLogin(id, "") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("在浏览器中登录 ChatGPT 账号") }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        val packs = actions.creditPacksOf(id, status.uid)
        // 首次进详情页没有数据时拉一次（打上游；拉取结果回到 state.creditPacks）
        LaunchedEffect(id, status.uid) {
            if (packs.isEmpty()) {
                actions.onLoadCreditPacks(id, status.uid)
            }
        }
        if (packs.isEmpty()) return

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (pack in packs) {
                QuotaPackBar(pack)
            }
        }
    }
}
