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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.google.gson.JsonObject
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.accountStatusText
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ProviderTask

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
        SectionCard(enterIndex = 0) {
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
            SectionCard(enterIndex = 1) {
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
                SectionCard(enterIndex = 2) {
                    SectionLabel("账号")
                    Text(
                        text = if (global) "还没有国际版账号，先登录" else "还没有国内版账号，先登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return
            }
            SectionCard(enterIndex = 2) {
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
                        text = accountStatusText(status),
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
            // 国际版（workbuddy.ai）没有签到制度与成长中心，只给国内账号渲染这两个入口
            if (actions.regionOf(id, status.uid) != CodeBuddyProvider.REGION_GLOBAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("签到", false) {
                        actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                    }
                    ChoiceChip("成长中心", false) {
                        actions.onAction(id, status.uid, ACTION_TASKS, JsonObject())
                    }
                }
                TaskListSection(status, actions)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChoiceChip("删除账号", false) { actions.onRemoveAccount(id, status.uid) }
            }
        }
    }

    /**
     * 任务中心明细：拉取该账号的成长任务清单，逐条展示完成度（已完成 / 可领取 / 进行中 / 未解锁）。
     *
     * 对照参考实现（WorkBuddy 反代）：`/v2/activity/growth/tasks` 返回 `tasks[]`，
     * 每条含 title / current / target / accept_status / locked / claimed；
     * 进度达标又未领取的可点「领取」调 ACTION_TASKS。
     */
    @Composable
    private fun TaskListSection(status: AccountStatus, actions: ProviderUiActions) {
        val view = actions.taskListOf(id, status.uid)
        // 进入账号块时自动拉一次；账号变化时重拉
        LaunchedEffect(status.uid) { actions.onLoadTaskList(id, status.uid) }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("任务中心")
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新任务列表",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { actions.onLoadTaskList(id, status.uid) }
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            )
        }

        when {
            view == null ->
                Text(
                    text = "正在加载任务列表…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            view.error.isNotEmpty() && view.tasks.isEmpty() ->
                Text(
                    text = "加载任务失败：${view.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            view.tasks.isEmpty() ->
                Text(
                    text = if (view.inPeriod) "该账号没有返回任何任务" else "成长活动未开启，暂无可查看的任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> {
                val done = view.tasks.count { it.completed }
                Text(
                    text = "共 ${view.tasks.size} 个任务 · 已完成 $done 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (task in view.tasks) {
                    TaskRow(task, status.uid, actions)
                }
            }
        }
    }

    @Composable
    private fun TaskRow(task: ProviderTask, uid: String, actions: ProviderUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = taskStatusText(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = taskStatusColor(task),
                )
            }
            val meta = buildString {
                if (task.target > 0) append("进度 ${task.progressText()}")
                if (task.rewardCredit > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("奖励 ${task.rewardCredit} 积分")
                }
                if (task.description.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(task.description)
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.claimable) {
                ChoiceChip("领取", false) {
                    actions.onAction(id, uid, ACTION_TASKS, JsonObject())
                }
            }
        }
    }

    private fun taskStatusText(task: ProviderTask): String = when {
        task.claimed -> "已领取"
        task.completed -> "已完成"
        task.locked -> "未解锁"
        else -> "未完成"
    }

    @Composable
    private fun taskStatusColor(task: ProviderTask) = when {
        task.claimed -> MaterialTheme.colorScheme.onSurfaceVariant
        task.completed -> MaterialTheme.colorScheme.tertiary
        task.locked -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
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
