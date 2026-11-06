package dev.aigw.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aigw.core.usage.ModelPrice

/**
 * 模型价格页。
 *
 * 价格只用于算费用，不影响路由。没配价格的模型不会被算成 0 元——
 * 用量里会单独标「未定价」，避免总金额看起来比实际低。
 */
@Composable
fun ModelPricingScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Pair<String, ModelPrice?>?>(null) }

    PageScaffold(
        title = "模型价格",
        subtitle = "按每百万 token 的美元单价计算调用费用",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            Text(
                text = "四个单价都要填：输入、输出、缓存读、缓存写。缺一项会让该模型的价格整体失效，" +
                    "而不是按 0 计——按 0 计会让总成本被系统性低估。没配价格的调用在用量里标为「未定价」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlineActionButton("添加模型价格") { editing = "" to null }
        }

        if (state.pricing.prices.isEmpty()) {
            EmptyHint(
                title = "还没有配置价格",
                message = "订阅类账号通常不按 token 计费，可以不配。自定义中转站按量计费时建议配置。",
            )
        } else {
            SectionLabel("已配置 · ${state.pricing.prices.size} 个模型")
            state.pricing.prices.entries.sortedBy { it.key }.forEachIndexed { index, (model, price) ->
                SectionCard(enterIndex = index + 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        PriceAction("编辑") { editing = model to price }
                        PriceAction("删除") { viewModel.updatePricing(state.pricing.withoutPrice(model)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PriceRow("输入", price.promptPerMillion)
                    PriceRow("输出", price.completionPerMillion)
                    PriceRow("缓存读", price.cacheReadPerMillion)
                    PriceRow("缓存写", price.cacheWritePerMillion)
                }
            }
        }
    }

    editing?.let { (model, price) ->
        PriceEditDialog(
            initialModel = model,
            initialPrice = price,
            onDismiss = { editing = null },
            onConfirm = { id, next ->
                viewModel.updatePricing(state.pricing.withPrice(id, next))
                editing = null
            },
        )
    }
}

@Composable
private fun PriceRow(label: String, value: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            text = "$value 美元 / 百万 token",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.7f),
        )
    }
}

@Composable
private fun PriceAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun PriceEditDialog(
    initialModel: String,
    initialPrice: ModelPrice?,
    onDismiss: () -> Unit,
    onConfirm: (String, ModelPrice) -> Unit,
) {
    var model by remember { mutableStateOf(initialModel) }
    var prompt by remember { mutableStateOf(initialPrice?.promptPerMillion?.toString().orEmpty()) }
    var completion by remember { mutableStateOf(initialPrice?.completionPerMillion?.toString().orEmpty()) }
    var cacheRead by remember { mutableStateOf(initialPrice?.cacheReadPerMillion?.toString().orEmpty()) }
    var cacheWrite by remember { mutableStateOf(initialPrice?.cacheWritePerMillion?.toString().orEmpty()) }

    val allValid = listOf(prompt, completion, cacheRead, cacheWrite).all { it.toDoubleOrNull() != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialPrice == null) "添加模型价格" else "编辑模型价格") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名（可填裸名，对该模型的所有供应商生效）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriceField("输入", prompt) { prompt = it }
                    PriceField("输出", completion) { completion = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriceField("缓存读", cacheRead) { cacheRead = it }
                    PriceField("缓存写", cacheWrite) { cacheWrite = it }
                }
                if (!allValid) {
                    Text(
                        text = "四个单价都要填有效数字才能保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = allValid && model.isNotBlank(),
                onClick = {
                    onConfirm(
                        model.trim(),
                        ModelPrice(
                            promptPerMillion = prompt.toDouble(),
                            completionPerMillion = completion.toDouble(),
                            cacheReadPerMillion = cacheRead.toDouble(),
                            cacheWritePerMillion = cacheWrite.toDouble(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PriceField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.weight(1f),
    )
}
