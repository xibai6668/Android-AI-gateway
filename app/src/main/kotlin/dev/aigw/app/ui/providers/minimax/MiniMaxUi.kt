package dev.aigw.app.ui.providers.minimax

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.bouncyClick
import dev.aigw.app.ui.pressScale
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * MiniMax Agent 国内版反代供应商的 UI 交互与动画组件。
 *
 * 特性：
 * 1. 动效卡片：支持展开/折叠的高阻尼弹性动画；
 * 2. 模式切换：支持「快捷组合串 / JSON」与「分字段表单」平滑淡入滑入切换；
 * 3. 实时格式探测与微光高亮：智能检测粘贴内容并给予动态视觉反馈；
 * 4. 继承通用账号卡（managesOwnAccounts = false），享有统一列表滑动流光与额度刷新体验。
 */
object MiniMaxUi : ProviderUi {

    override val id: String = "minimax"

    override fun description(): String = "MiniMax Agent 国内版网页端反代（支持快速/Agent双模式）"

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
        var expanded by rememberSaveable { mutableStateOf(false) }

        SectionCard(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = 500f,
                    ),
                ),
        ) {
            AnimatedVisibility(
                visible = !expanded,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120)),
            ) {
                Button(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bouncyClick { expanded = true },
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = if (accounts.isEmpty()) "添加 MiniMax 凭证" else "添加新账号", modifier = Modifier.padding(start = 8.dp))
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                ) + fadeIn(tween(220, easing = FastOutSlowInEasing)),
                exit = shrinkVertically(
                    animationSpec = tween(180),
                ) + fadeOut(tween(120)),
            ) {
                ImportForm(
                    onDismiss = { expanded = false },
                    onImport = { payload ->
                        actions.onImport(id, payload)
                        expanded = false
                    },
                )
            }
        }
    }

    @Composable
    private fun ImportForm(
        onDismiss: () -> Unit,
        onImport: (String) -> Unit,
    ) {
        // 0: 快捷一键粘贴模式 (JSON 或 userId+token 组合串)
        // 1: 字段逐项分步填入
        var mode by rememberSaveable { mutableIntStateOf(0) }

        var rawInput by rememberSaveable { mutableStateOf("") }
        var userId by rememberSaveable { mutableStateOf("") }
        var token by rememberSaveable { mutableStateOf("") }
        var deviceId by rememberSaveable { mutableStateOf("") }

        val isFastValid = remember(rawInput) {
            val trimmed = rawInput.trim()
            trimmed.startsWith("{") || (trimmed.contains("+") && trimmed.length > 20)
        }
        val isFormValid = remember(userId, token) {
            userId.trim().isNotEmpty() && token.trim().isNotEmpty()
        }
        val canSubmit = if (mode == 0) isFastValid else isFormValid

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionLabel("添加 MiniMax 账号")
                ModeSegmentedSlider(
                    selectedMode = mode,
                    onSelect = { mode = it },
                )
            }

            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(tween(220)) togetherWith fadeOut(tween(150)))
                },
                label = "inputModeTransition",
            ) { targetMode ->
                if (targetMode == 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = rawInput,
                            onValueChange = { rawInput = it },
                            label = { Text("粘贴凭证（支持组合串或 JSON）") },
                            placeholder = { Text("例如：450234567894+eyJhbGci... 或 {\"token\":\"...\",\"userId\":\"...\"}") },
                            leadingIcon = {
                                Icon(Icons.Filled.DataObject, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingIcon = {
                                val alpha by animateFloatAsState(if (isFastValid) 1f else 0f, label = "detectCheck")
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "格式正确",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.graphicsLayer { this.alpha = alpha },
                                )
                            },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = userId,
                            onValueChange = { userId = it },
                            label = { Text("User ID (必填)") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("Token JWT (必填)") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = { deviceId = it },
                            label = { Text("Device ID (选填，留空网关自动注册)") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Filled.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        val payload = if (mode == 0) {
                            rawInput.trim()
                        } else {
                            val json = JsonObject().apply {
                                addProperty("userId", userId.trim())
                                addProperty("token", token.trim())
                                if (deviceId.trim().isNotEmpty()) {
                                    addProperty("deviceId", deviceId.trim())
                                }
                            }
                            json.toString()
                        }
                        onImport(payload)
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .pressScale(),
                ) {
                    Text("导入凭证")
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .pressScale(),
                ) {
                    Text("取消")
                }
            }
        }
    }

    /**
     * 优雅的分段微胶囊滑块。
     */
    @Composable
    private fun ModeSegmentedSlider(
        selectedMode: Int,
        onSelect: (Int) -> Unit,
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
                val modes = listOf("快捷粘贴", "分项填入")
                modes.forEachIndexed { index, title ->
                    val isSelected = selectedMode == index
                    val animProgress by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 600f),
                        label = "tabIndicator$index",
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = animProgress),
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
