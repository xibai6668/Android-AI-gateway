package dev.aigw.app.ui.providers.loomy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.loomy.LoomyProvider

object LoomyUi : ProviderUi {

    override val id: String = "loomy"

    /** 验证码重发间隔（秒）。 */
    private const val RESEND_SECONDS = 60

    override fun description(): String = "手机号短信登录，走免费积分通道"

    @Composable
    override fun Icon(modifier: Modifier) {
        MaterialIcon(
            imageVector = Icons.Filled.Sms,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(0) }

        // 发送后 60 秒内不允许重发：上游有频率限制，连点只会被拒
        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1_000)
                countdown -= 1
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    label = { Text("手机号") },
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("验证码") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            // 先开倒计时，按钮立刻有反馈；发送失败再恢复可点
                            countdown = RESEND_SECONDS
                            actions.onSmsLogin(id, phone.trim(), "") { error ->
                                if (error.isNotEmpty()) countdown = 0
                            }
                        },
                        enabled = phone.isNotBlank() && countdown == 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (countdown > 0) "${countdown} 秒后重发" else "发送验证码")
                    }
                    Button(
                        onClick = { actions.onSmsLogin(id, phone.trim(), code.trim()) { } },
                        enabled = phone.isNotBlank() && code.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("登录")
                    }
                }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        var invite by remember(status.uid) { mutableStateOf("") }
        var code by remember(status.uid) { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = invite,
                onValueChange = { invite = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("兑换邀请码") },
            )
            Button(
                onClick = {
                    val value = invite.trim()
                    actions.onAction(
                        id,
                        status.uid,
                        LoomyProvider.ACTION_REDEEM_INVITE,
                        JsonObject().apply { addProperty("inviteCode", value) },
                    )
                    invite = ""
                },
                enabled = invite.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("兑换邀请码")
            }

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("兑换码") },
            )
            Button(
                onClick = {
                    val value = code.trim()
                    actions.onAction(
                        id,
                        status.uid,
                        LoomyProvider.ACTION_REDEEM_CODE,
                        JsonObject().apply { addProperty("code", value) },
                    )
                    code = ""
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("兑换码")
            }

            OutlineActionButton("一键完成新手任务") {
                actions.onAction(id, status.uid, ACTION_TASKS, JsonObject())
            }
        }
    }
}
