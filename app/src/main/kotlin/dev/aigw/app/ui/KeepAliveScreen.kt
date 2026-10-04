package dev.aigw.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import dev.aigw.app.data.KeepAlive

/**
 * 保活与权限：把网关在后台持续运行所需的系统条件逐项检测出来，并给出一键跳转。
 *
 * 锁屏后端口还能被客户端调通，需要几件事同时成立：通知权限、应用不在电池优化名单里、
 * 系统没有限制它后台运行。这些都没有 API 能直接代改，只能检测 + 引导。
 */
@Composable
fun KeepAliveScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val status = state.keepAlive

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    PageScaffold(
        title = "保活与权限",
        subtitle = "让网关在后台持续运行",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("保活状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    text = if (status?.allGood == true) "已就绪" else "有待处理",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (status?.allGood == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "通知权限 + 电池优化白名单 + 不被限制后台，这几项都满足后才能锁屏稳定可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.running) {
                Text(
                    text = "当前服务未启动：先去首页启动，否则锁屏后没有进程监听端口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(enterIndex = 1) {
            StatusRow(
                title = "通知权限",
                detail = if (status?.notificationsGranted == true) "已允许，常驻通知可见" else "未允许，后台通知不会显示",
                ok = status?.notificationsGranted == true,
                actionLabel = "去开启",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    launch(context, KeepAlive.notificationSettingsIntent(context), "无法打开通知设置")
                }
            }
            StatusRow(
                title = "电池优化",
                detail = if (status?.batteryUnrestricted == true) {
                    "已加入白名单（不限制耗电）"
                } else {
                    "受系统电池优化限制，锁屏后可能被冻结、端口调不通"
                },
                ok = status?.batteryUnrestricted == true,
                actionLabel = "不限制",
            ) {
                // 部分 ROM 不支持直接请求，失败就退回到电池优化列表页
                if (!launch(context, KeepAlive.batteryOptimizationIntent(context), null)) {
                    launch(context, KeepAlive.batterySettingsIntent(), "无法打开电池优化设置")
                }
            }
            StatusRow(
                title = "后台运行限制",
                detail = if (status?.backgroundRestricted == true) "系统已限制本应用后台运行" else "未被系统限制后台运行",
                ok = status?.backgroundRestricted != true,
                actionLabel = "去设置",
            ) {
                launch(context, KeepAlive.appDetailsIntent(context), "无法打开应用详情")
            }
            StatusRow(
                title = "流量节省",
                detail = if (status?.dataSaverRestricted == true) "已开启流量节省，后台联网可能受限" else "未开启流量节省",
                ok = status?.dataSaverRestricted != true,
                actionLabel = "去设置",
            ) {
                launch(context, KeepAlive.appDetailsIntent(context), "无法打开应用详情")
            }
        }

        SectionCard(enterIndex = 2) {
            Text("厂商后台管理页", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "国产系统默认会限制第三方应用后台。下面的入口会尝试直接打开厂商的后台管理页；" +
                    "打不开就按提示手动设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceChip("打开厂商后台设置", false) {
                val opened = KeepAlive.autoStartIntents().any { launch(context, it, null) }
                if (!opened) {
                    launch(context, KeepAlive.appDetailsIntent(context), "无法打开系统页面")
                }
                Toast.makeText(context, KeepAlive.manualHint(Build.MANUFACTURER), Toast.LENGTH_LONG).show()
            }
            Text(
                text = KeepAlive.manualHint(Build.MANUFACTURER),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "说明：本应用用「常驻通知」保持进程存活，不需要额外的唤醒锁（唤醒锁更耗电且没必要）。" +
                "如果系统把本应用放进电池优化名单或限制后台，锁屏一段时间后进程会被冻结，客户端就会连接失败——" +
                "按上面的检测项逐条处理即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    detail: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        ChoiceChip(actionLabel, selected = false, onClick = onAction)
    }
}

private fun launch(context: Context, intent: Intent, failureMessage: String?): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        if (failureMessage != null) {
            Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
        }
        false
    }
}
