package dev.aigw.app.ui.providers.trae

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.aigw.core.provider.ACTION_CHECKIN

object TraeUi : ProviderUi {

    override val id: String = "trae"

    override fun description(): String = "网页登录导入，走 SOLO 免费通道（国内/国际）"

    @Composable
    override fun Icon(modifier: Modifier) {
        MaterialIcon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }
        // 滑块状态持久化在供应商设置里（region=cn/global）；引擎开登录页时按它选 host
        var global by rememberSaveable {
            mutableStateOf(actions.providerOptionOf(id, "region", "cn") == "global")
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MaterialIcon(
                            Icons.Filled.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        SectionLabel("服务区域")
                    }
                    RegionSelector(
                        isGlobal = global,
                        onSelectGlobal = { isGlobal ->
                            global = isGlobal
                            actions.onUpdateProviderOption(id, "region", if (isGlobal) "global" else "cn")
                        },
                    )
                }
                SectionLabel(if (global) "登录 · 国际版 trae.ai" else "登录 · 国内版 trae.cn")
                Button(
                    onClick = { actions.onWebLogin(id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (global) "在浏览器中打开 Trae 国际版登录页" else "在浏览器中打开 Trae 登录页")
                }
                OutlineActionButton(
                    text = if (expanded) "收起粘贴导入" else "粘贴凭证 JSON 导入",
                ) { expanded = !expanded }
                if (expanded) {
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        label = { Text("回调链接 / 凭证 JSON") },
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

    /** 国内/国外分段滑块（与 CodeBuddy 同款）。 */
    @Composable
    private fun RegionSelector(
        isGlobal: Boolean,
        onSelectGlobal: (Boolean) -> Unit,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.clip(CircleShape),
        ) {
            Row(
                modifier = Modifier.padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf("国内", "国外").forEachIndexed { index, label ->
                    val selected = (index == 1) == isGlobal
                    val animProgress by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 600f),
                        label = "traeRegion$index",
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = animProgress))
                            .clickable { onSelectGlobal(index == 1) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        val deviceId = actions.deviceIdOf(id, status.uid)
        var manual by remember(status.uid) { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("额度", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (status.creditsKnown) status.credits.toString() else "—",
                style = MaterialTheme.typography.bodyMedium,
            )
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
            }
            Text("设备指纹", style = MaterialTheme.typography.titleSmall)
            Text(
                text = deviceId.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "设备指纹用于签到校验，非数字格式会被上游拒绝。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("重新生成", false) { actions.onRegenerateDeviceId(id, status.uid) }
            }
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("手动填写设备指纹") },
            )
            Button(
                onClick = {
                    actions.onSetDeviceId(id, status.uid, manual.trim())
                    manual = ""
                },
                enabled = manual.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}
