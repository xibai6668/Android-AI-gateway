package dev.aigw.app.ui.providers.raccoon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PointsBillRow
import dev.aigw.app.ui.PointsBillsHint
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN

object RaccoonUi : ProviderUi {

    override val id: String = "raccoon"

    override fun description(): String = "小浣熊（商汤）网页登录，用插件账号额度"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Pets,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                Button(
                    onClick = { actions.onWebLogin(id, "") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("在浏览器中打开小浣熊登录页")
                }
                OutlineActionButton(
                    text = if (expanded) "收起粘贴导入" else "粘贴回调链接导入",
                ) { expanded = !expanded }
                if (expanded) {
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        label = { Text("回调链接（含 authorization_code）") },
                    )
                    Button(
                        onClick = {
                            actions.onImport(id, raw.trim())
                            raw = ""
                        },
                        enabled = raw.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入")
                    }
                }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("签到领积分", false) {
                    actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                }
            }
            PointsBillsSection(status, actions)
        }
    }

    /**
     * 积分明细：逐笔展示最近收支（每日赠送 / 桌面端登录奖励 / 消耗都在这条流里）。
     *
     * 余额只说「还剩多少」，看不出「签到到底发没发」；明细才能回答这个问题。
     */
    @Composable
    private fun PointsBillsSection(status: AccountStatus, actions: ProviderUiActions) {
        val view = actions.pointsBillsOf(id, status.uid)
        LaunchedEffect(status.uid) {
            if (view == null) actions.onLoadPointsBills(id, status.uid)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("积分明细")
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新积分明细",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { actions.onLoadPointsBills(id, status.uid) }
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            )
        }

        when {
            view == null -> PointsBillsHint("正在加载积分明细…")
            view.error.isNotEmpty() -> PointsBillsHint("加载失败：${view.error}", error = true)
            view.bills.isEmpty() -> PointsBillsHint("暂无积分明细")
            else -> for (bill in view.bills) PointsBillRow(bill)
        }
    }
}
