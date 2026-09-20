package dev.aigw.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 首页：接入地址、服务启停与总览。 */
@Composable
fun HomeScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    openProviders: () -> Unit,
    openTaskCenter: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lanUrl = state.lanUrls.firstOrNull()

    // Android 13+ 通知权限被拒时前台服务照常跑，但常驻通知会被系统静默隐藏，
    // 用户看起来就是「服务没起来」；启动前先补上授权请求。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startGateway() }

    fun startWithNotificationPermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startGateway()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    PageScaffold(
        title = "ai-gateway",
        subtitle = "本地 OpenAI 兼容网关",
        actions = { StatusChip(state.running) },
    ) {
        SectionCard(modifier = Modifier.staggeredAppear(0)) {
            Text(
                text = "接入地址 · 端口 ${state.port} · 点即复制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AddressTile(state.localUrl.ifEmpty { "http://127.0.0.1:${state.port}/v1" }) {
                clipboard.setText(AnnotatedString(state.localUrl))
                Toast.makeText(context, "已复制本机地址", Toast.LENGTH_SHORT).show()
            }
            AddressTile(lanUrl ?: "未连接 Wi-Fi（局域网不可用）") {
                if (lanUrl != null) {
                    clipboard.setText(AnnotatedString(lanUrl))
                    Toast.makeText(context, "已复制局域网地址", Toast.LENGTH_SHORT).show()
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.running) {
                    // 停止是破坏性动作，红色提示
                    Button(
                        onClick = { viewModel.stopGateway() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("停止服务")
                    }
                } else {
                    Button(onClick = { startWithNotificationPermission() }, modifier = Modifier.weight(1f)) {
                        Text("启动服务")
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.refreshAllCredits() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("刷新额度")
                }
            }
        }

        SectionCard(modifier = Modifier.staggeredAppear(1)) {
            SectionLabel("总览")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatCell(state.usableProviders.toString(), "可用供应商")
                StatCell(state.pool.total.toString(), "账号")
                StatCell(state.pool.usable.toString(), "可用账号")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatCell(state.today.requests.toString(), "今日请求")
                StatCell(state.today.totalTokens.toString(), "今日 tokens")
                StatCell(
                    if (state.pool.creditsKnown > 0) state.pool.totalCredits.toString() else "—",
                    "已知额度合计",
                )
            }
        }

        SectionCard(modifier = Modifier.staggeredAppear(2)) {
            SectionLabel("快捷操作")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { viewModel.checkinAll() }, modifier = Modifier.weight(1f)) {
                    Text("批量签到")
                }
                OutlinedButton(onClick = openTaskCenter, modifier = Modifier.weight(1f)) {
                    Text("任务中心")
                }
            }
            Text(
                text = "去供应商页管理账号与登录 →",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = openProviders),
            )
        }

        AntigravityQuotaSection(state, viewModel, baseIndex = 3)

        SectionCard(modifier = Modifier.staggeredAppear(4)) {
            SectionLabel("模型总数")
            Text(
                text = if (state.models.isEmpty()) {
                    "还没拉取模型目录，去「模型」页刷新。"
                } else {
                    "当前可用模型 ${state.models.size} 个" +
                        (if (state.modelsError.isNotEmpty()) "（拉取有报错：${state.modelsError}）" else "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Antigravity 的模型组配额窗口（每组 × 每窗口一条），首页与账号详情共用。 */
@Composable
private fun AntigravityQuotaSection(state: AppUiState, viewModel: AppViewModel, baseIndex: Int) {
    val accounts = state.accountsOf("antigravity")
    if (accounts.isEmpty()) return

    // 进首页拉一次；账号集合变化时重拉
    LaunchedEffect(accounts.map { it.uid }) {
        accounts.forEach { viewModel.loadCreditPacks("antigravity", it.uid) }
    }

    SectionCard(modifier = Modifier.staggeredAppear(baseIndex)) {
        SectionLabel("Antigravity 配额")
        for (account in accounts) {
            val packs = state.creditPacks["antigravity/${account.uid}"].orEmpty()
            if (accounts.size > 1) {
                Text(
                    text = account.nickname.ifEmpty { account.uid },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (packs.isEmpty()) {
                Text(
                    text = if (account.detail.isNotEmpty()) account.detail else "尚未拉取配额",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pack in packs) {
                        QuotaPackBar(pack)
                    }
                }
            }
        }
    }
}
