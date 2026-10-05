package dev.aigw.app.ui.providers.minimax

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.bouncyClick
import dev.aigw.app.ui.pressScale
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.minimax.MiniMaxConstants

/**
 * MiniMax 供应商 UI 组件。
 *
 * 登录交互：
 * 1. 核心流程：调用系统浏览器打开 MiniMax 官方授权页，授权后由网关后台自动轮询拾取 Token 落池；
 * 2. 区域切换：支持国内版 / 国际版单选滑块；
 * 3. 辅助轮询：「重新检查授权状态」按钮；
 * 4. 备用兜底：提供折叠式手动粘贴凭证。
 */
object MiniMaxUi : ProviderUi {

    override val id: String = "minimax"

    override fun description(): String = "官方 Device 授权自动登录，支持 M 系列百万上下文模型"

    @Composable
    override fun Icon(modifier: Modifier) {
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.tertiary
        val gradient = Brush.linearGradient(listOf(primary, secondary))

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(gradient)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = "MiniMax",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var global by rememberSaveable {
            mutableStateOf(
                actions.providerOptionOf(id, "region", MiniMaxConstants.REGION_CN) == MiniMaxConstants.REGION_GLOBAL,
            )
        }

        val region = if (global) MiniMaxConstants.REGION_GLOBAL else MiniMaxConstants.REGION_CN

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 区域分段滑块
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
                        Icon(
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
                            actions.onUpdateProviderOption(
                                id,
                                "region",
                                if (isGlobal) MiniMaxConstants.REGION_GLOBAL else MiniMaxConstants.REGION_CN,
                            )
                        },
                    )
                }
            }

            // 浏览器授权主卡片
            SectionCard(
                enterIndex = 1,
                modifier = Modifier.animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f)),
            ) {
                SectionLabel(if (global) "登录 · 国际版 (account.minimax.io)" else "登录 · 国内版 (account.minimaxi.com)")

                Button(
                    onClick = { actions.onDeviceLogin(id, region) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bouncyClick { actions.onDeviceLogin(id, region) },
                ) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = if (global) "在浏览器中打开国际版登录页" else "在浏览器中打开国内版登录页",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                OutlineActionButton("重新检查授权状态") {
                    actions.onCheckDeviceAuth(id, region)
                }

                // 备用粘贴导入折叠区
                ManualImportAccordion(
                    onImport = { raw -> actions.onImport(id, raw) },
                )
            }
        }
    }

    @Composable
    private fun ManualImportAccordion(onImport: (String) -> Unit) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var rawInput by rememberSaveable { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (expanded) "收起手动输入" else "备用：手动导入 API Key 或 JSON 凭证",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(tween(180, easing = FastOutSlowInEasing)),
                exit = shrinkVertically(tween(150)) + fadeOut(tween(100)),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    OutlinedTextField(
                        value = rawInput,
                        onValueChange = { rawInput = it },
                        label = { Text("API Key 或凭证 JSON / 组合串") },
                        placeholder = { Text("粘贴 sk-api-... 或 OAuth 凭证") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (rawInput.isNotBlank()) {
                                    onImport(rawInput.trim())
                                    rawInput = ""
                                    expanded = false
                                }
                            },
                            enabled = rawInput.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .pressScale(),
                        ) {
                            Text("导入凭证")
                        }

                        OutlinedButton(
                            onClick = { expanded = false },
                            modifier = Modifier
                                .weight(1f)
                                .pressScale(),
                        ) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    }

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
                val items = listOf("国内", "国际")
                items.forEachIndexed { index, label ->
                    val selected = (index == 1) == isGlobal
                    val animProgress by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 600f),
                        label = "regionIndicator$index",
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = animProgress),
                            )
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
}
