package dev.aigw.app.ui.providers.antigravity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.QuotaPackBar
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

object AntigravityUi : ProviderUi {

    override val id = "antigravity"

    override fun description() = "Google 账号 OAuth 登录"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        SectionCard {
            SectionLabel("登录")
            Button(
                onClick = { actions.onWebLogin(id) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("在浏览器中登录 Google 账号") }
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
