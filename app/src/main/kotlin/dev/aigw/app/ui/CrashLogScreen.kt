package dev.aigw.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.R
import dev.aigw.app.data.CrashReporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志页。
 *
 * 应用崩溃后系统会直接杀掉进程，用户什么都看不到；这里把崩溃时的堆栈、版本与设备信息
 * 落盘（见 [CrashReporter]），下次打开应用就能翻出来。
 *
 * 列表默认折叠：堆栈动辄几百行，全部铺开既卡列表也没法扫。点某一条就地展开，
 * 需要给别人看时用下方的「导出为 .log 文件」——走系统文件选择器，存到用户自己选的位置。
 *
 * 只列本机文件，不上传任何地方——崩溃堆栈常含本地路径与账号 uid，外发需要用户自己决定。
 */
@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var files by remember { mutableStateOf(CrashReporter.list(context)) }
    // 展开的条目按文件名记录：文件会被删除/重建，不能用下标
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    // 待写入的导出内容：文件选择器回调是异步的，先把内容存起来
    var pendingExport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        Toast.makeText(context, if (ok) "已导出" else "导出失败", Toast.LENGTH_SHORT).show()
    }

    fun export(fileName: String, content: String) {
        pendingExport = content
        exportLauncher.launch(fileName)
    }

    PageScaffold(
        title = "崩溃日志",
        subtitle = "应用异常退出时的堆栈记录",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("本机记录")
            if (files.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "还没有崩溃记录。应用异常退出后会在这里留下堆栈，最多保留 ${CrashReporter.MAX_FILES} 份。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                files.forEachIndexed { index, file ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    val isExpanded = file.name in expanded
                    // 错峰入场与网关其它列表保持一致（每项间隔 40ms 淡入上浮）
                    CrashItem(
                        file = file,
                        expanded = isExpanded,
                        enterIndex = index,
                        onToggle = {
                            expanded = if (isExpanded) expanded - file.name else expanded + file.name
                        },
                        onCopy = {
                            clipboard.setText(AnnotatedString(CrashReporter.read(file)))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        onExport = {
                            export("aigw-crash-${file.nameWithoutExtension}.log", CrashReporter.read(file))
                        },
                    )
                }
            }
        }

        if (files.isNotEmpty()) {
            SectionCard(enterIndex = 1) {
                SectionLabel("导出")
                Text(
                    text = "导出为 .log 文件后会打开系统文件选择器，存到你指定的位置。崩溃堆栈可能包含本地路径与账号标识，分享前请自行确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlineActionButton("导出全部为 .log 文件") {
                    export("aigw-crash-all-${stamp()}.log", buildExportText(files))
                }
                Spacer(Modifier.height(10.dp))
                OutlineActionButton("清空全部") {
                    CrashReporter.deleteAll(context)
                    files = CrashReporter.list(context)
                    expanded = emptySet()
                    Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                }
            }
        }

    }
}

/** 一条崩溃记录：折叠时只露摘要，展开后就地显示完整堆栈。 */
@Composable
private fun CrashItem(
    file: File,
    expanded: Boolean,
    enterIndex: Int,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
) {
    val text = remember(file) { CrashReporter.read(file) }
    val summary = remember(text) {
        text.lineSequence().firstOrNull { it.startsWith("异常：") }?.removePrefix("异常：") ?: "（无摘要）"
    }

    Column(Modifier.fillMaxWidth().staggeredAppear(enterIndex).animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale()
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "▾ " else "▸ ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = file.nameWithoutExtension,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 3 else 2,
                )
            }
            Text(
                text = "${file.length() / 1024} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlineActionButton("复制", modifier = Modifier.weight(1f), onClick = onCopy)
                OutlineActionButton("导出这条 .log", modifier = Modifier.weight(1f), onClick = onExport)
            }
        }
    }
}

/**
 * 导出内容：每份之间用分隔线隔开，便于一个文件里看多次崩溃。
 *
 * 拆成独立函数（非 private）是为了能直接单测——它决定导出的文件长什么样，
 * 而界面层在本仓没有 emulator 可跑。
 */
internal fun buildExportText(files: List<File>, now: Date = Date()): String = buildString {
    appendLine("ai-gateway 崩溃日志导出")
    appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
    appendLine("记录数：${files.size}")
    appendLine()
    files.forEachIndexed { index, file ->
        if (index > 0) {
            appendLine("=".repeat(60))
            appendLine()
        }
        appendLine("<<< ${file.name} >>>")
        appendLine(CrashReporter.read(file).trimEnd())
        appendLine()
    }
}

private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
