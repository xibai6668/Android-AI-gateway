package dev.aigw.app.ui.providers.codebuddy

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.coolingText
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.codebuddy.CodeBuddyProvider

object CodeBuddyUi : ProviderUi {

    override val id = CodeBuddyProvider.ID

    override val managesOwnAccounts: Boolean
        get() = true

    override fun description() = "在浏览器中登录，走每日积分"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        // 滑块状态持久化在供应商设置里（region=cn/global），登录与账号列表都跟随它
        var global by rememberSaveable {
            mutableStateOf(actions.providerOptionOf(id, "region", CodeBuddyProvider.REGION_CN) == CodeBuddyProvider.REGION_GLOBAL)
        }
        SectionCard {
            RegionSlider(
                global = global,
                onGlobalChange = {
                    global = it
                    actions.onUpdateProviderOption(id, "region", if (it) CodeBuddyProvider.REGION_GLOBAL else CodeBuddyProvider.REGION_CN)
                },
            )
        }
        LoginSection(global, actions)
        if (accounts.isNotEmpty()) {
            AccountRegionSection(global, accounts, actions)
        }
    }

    @Composable
    private fun LoginSection(global: Boolean, actions: ProviderUiActions) {
        val region = if (global) CodeBuddyProvider.REGION_GLOBAL else CodeBuddyProvider.REGION_CN
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard {
                SectionLabel(if (global) "登录 · 国际版 workbuddy.ai" else "登录 · 国内版 copilot.tencent.com")
                Button(
                    onClick = { actions.onDeviceLogin(id, region) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (global) "打开国际版登录页" else "打开国内版登录页")
                }
                OutlineActionButton("重新检查授权状态") { actions.onCheckDeviceAuth(id, region) }
            }
        }
    }

    @Composable
    private fun AccountRegionSection(global: Boolean, accounts: List<AccountStatus>, actions: ProviderUiActions) {
        val current = if (global) CodeBuddyProvider.REGION_GLOBAL else CodeBuddyProvider.REGION_CN
        val scoped = accounts.filter { actions.regionOf(id, it.uid) == current }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (scoped.isEmpty()) {
                SectionCard {
                    SectionLabel("账号")
                    Text(
                        text = if (global) "还没有国际版账号，先登录" else "还没有国内版账号，先登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return
            }
            SectionCard {
                SectionLabel("账号 · ${scoped.size} 个")
                for (account in scoped) {
                    AccountBlock(account, actions)
                }
                OutlineActionButton("刷新额度") { actions.onRefreshCredits(id, null) }
            }
        }
    }

    @Composable
    private fun AccountBlock(status: AccountStatus, actions: ProviderUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = status.nickname.ifEmpty { status.uid },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = status.coolingText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = status.enabled,
                    onCheckedChange = { actions.onToggleAccount(id, status.uid, it) },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("额度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    text = if (status.creditsKnown) status.credits.toString() else "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("签到", false) {
                    actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                }
                ChoiceChip("成长中心", false) {
                    actions.onAction(id, status.uid, ACTION_TASKS, JsonObject())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (status.cooling) {
                    ChoiceChip("解除冷却", false) { actions.onClearCooldown(id, status.uid) }
                }
                ChoiceChip("删除账号", false) { actions.onRemoveAccount(id, status.uid) }
            }
        }
    }

    /**
     * 国内/国外分段滑块：长条一分为二，蓝色背景块在两半之间滑动指示选中侧。
     */
    @Composable
    private fun RegionSlider(
        global: Boolean,
        onGlobalChange: (Boolean) -> Unit,
    ) {
        val corner = RoundedCornerShape(12.dp)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, corner),
        ) {
            // 蓝色指示块：宽度与偏移都是轨道的一半，切换时在两半之间滑动过渡
            val indicatorX by animateDpAsState(
                targetValue = if (global) maxWidth / 2 else 0.dp,
                animationSpec = tween(200),
                label = "regionIndicator",
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.5f)
                    .fillMaxSize()
                    .offset(x = indicatorX)
                    .background(MaterialTheme.colorScheme.primary, corner),
            )
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                RegionSliderHalf(
                    text = "国内",
                    selected = !global,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onClick = { onGlobalChange(false) },
                )
                RegionSliderHalf(
                    text = "国外",
                    selected = global,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onClick = { onGlobalChange(true) },
                )
            }
        }
    }

    @Composable
    private fun RegionSliderHalf(
        text: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Box(modifier = modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize * 2),
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
