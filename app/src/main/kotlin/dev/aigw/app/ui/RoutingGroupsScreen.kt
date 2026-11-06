package dev.aigw.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import dev.aigw.core.gateway.GroupMember
import dev.aigw.core.gateway.GroupRule
import dev.aigw.core.gateway.GroupStrategy
import dev.aigw.core.gateway.RoutingGroup
import dev.aigw.core.gateway.RoutingGroupSettings

/**
 * 路由组页。
 *
 * 组对客户端就是一个模型：模型名填 `group/<组名>`，网关按组内顺序与规则决定这一轮用谁，
 * 某个成员不可用时自动换下一个。规则只在一轮开始时判定，工具回调中途不换模型。
 */
@Composable
fun RoutingGroupsScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<RoutingGroup?>(null) }
    var creating by remember { mutableStateOf(false) }

    PageScaffold(
        title = "路由组",
        subtitle = "把多个模型合成一个名字，按规则与顺序调度",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            Text(
                text = "组名即对外模型名，客户端填 group/<组名> 即可。规则命中时把这一轮优先交给指定成员，" +
                    "其余成员作为故障转移候选；规则只在一轮开始时判定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlineActionButton("新建路由组") { creating = true }
        }

        if (state.routingGroups.groups.isEmpty()) {
            EmptyHint(
                title = "还没有路由组",
                message = "只有一组模型时不需要它。多个账号、多个供应商都能用同名模型时再建组更划算。",
            )
        } else {
            SectionLabel("已创建的组 · ${state.routingGroups.groups.size}")
            state.routingGroups.groups.forEachIndexed { index, group ->
                RoutingGroupCard(
                    group = group,
                    enterIndex = index + 1,
                    onToggle = { enabled ->
                        viewModel.updateRoutingGroups(
                            state.routingGroups.upsert(group.copy(enabled = enabled)),
                        )
                    },
                    onEdit = { editing = group },
                    onDelete = {
                        viewModel.updateRoutingGroups(state.routingGroups.remove(group.id))
                    },
                )
            }
        }
    }

    if (creating || editing != null) {
        val current = editing
        RoutingGroupEditDialog(
            current = current,
            models = state.models,
            onDismiss = {
                creating = false
                editing = null
            },
            onConfirm = { group ->
                viewModel.updateRoutingGroups(state.routingGroups.upsert(group))
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun RoutingGroupCard(
    group: RoutingGroup,
    enterIndex: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(group.id) { mutableStateOf(false) }

    SectionCard(enterIndex = enterIndex) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "group/${group.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = group.enabled, onCheckedChange = onToggle)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        GroupFactRow(
            "成员",
            if (group.members.isEmpty()) "尚未添加" else group.members.joinToString("、") { "${it.providerId}/${it.model}" },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        GroupFactRow("调度", if (group.strategy == GroupStrategy.WEIGHT) "按权重随机" else "按优先级，同级按权重")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        GroupFactRow("规则", if (group.rules.isEmpty()) "无" else "${group.rules.size} 条")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupAction("编辑", onEdit)
            GroupAction("删除", { confirmDelete = true })
        }
    }

    if (confirmDelete) {
        ConfirmDestructiveDialog(
            title = "删除路由组",
            body = "删除后客户端再请求 group/${group.id} 会找不到该模型。",
            confirmText = "删除",
            onDismiss = { confirmDelete = false },
            onConfirm = { onDelete() },
        )
    }
}

@Composable
private fun GroupFactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.25f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.75f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GroupAction(text: String, onClick: () -> Unit) {
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

/**
 * 组编辑对话框：组信息 + 成员 + 规则三段。
 *
 * 成员从当前模型目录里选（`providerId/model.id`），避免手敲出不存在的组合；
 * 规则的目标成员只能从本组成员里选，选不到成员的规则没有意义。
 */
@Composable
private fun RoutingGroupEditDialog(
    current: RoutingGroup?,
    models: List<dev.aigw.core.provider.RoutedModel>,
    onDismiss: () -> Unit,
    onConfirm: (RoutingGroup) -> Unit,
) {
    var id by remember { mutableStateOf(current?.id.orEmpty()) }
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var strategy by remember { mutableStateOf(current?.strategy ?: GroupStrategy.ORDER) }
    var members by remember { mutableStateOf(current?.members ?: emptyList()) }
    var rules by remember { mutableStateOf(current?.rules ?: emptyList()) }
    var addingMember by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "新建路由组" else "编辑路由组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("组名（客户端填 group/<组名>）") },
                    singleLine = true,
                    enabled = current == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("调度方式", style = MaterialTheme.typography.labelMedium)
                    ChoiceChip("按优先级", strategy == GroupStrategy.ORDER) { strategy = GroupStrategy.ORDER }
                    ChoiceChip("按权重随机", strategy == GroupStrategy.WEIGHT) { strategy = GroupStrategy.WEIGHT }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("成员", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    GroupAction("添加成员") { addingMember = true }
                }
                members.forEachIndexed { index, member ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${member.providerId}/${member.model}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedTextField(
                            value = member.priority.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let { next ->
                                    members = members.toMutableList().also { it[index] = member.copy(priority = next) }
                                }
                            },
                            label = { Text("优先级") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.5f),
                        )
                        OutlinedTextField(
                            value = member.weight.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let { next ->
                                    members = members.toMutableList().also { it[index] = member.copy(weight = next) }
                                }
                            },
                            label = { Text("权重") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.5f),
                        )
                        GroupAction("移除") { members = members.filterIndexed { i, _ -> i != index } }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("规则", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (members.isNotEmpty()) {
                        GroupAction("添加规则") {
                            val first = members.first()
                            rules = rules + GroupRule(first.providerId, first.model)
                        }
                    }
                }
                if (members.isNotEmpty() && rules.isEmpty()) {
                    Text(
                        text = "没有规则时按组策略顺序调度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rules.forEachIndexed { index, rule ->
                    GroupRuleEditor(
                        rule = rule,
                        members = members,
                        onChange = { next -> rules = rules.toMutableList().also { it[index] = next } },
                        onRemove = { rules = rules.filterIndexed { i, _ -> i != index } },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        RoutingGroup(
                            id = id.trim(),
                            name = name.trim(),
                            enabled = current?.enabled ?: true,
                            strategy = strategy,
                            members = members,
                            rules = rules,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (addingMember) {
        MemberPickerDialog(
            models = models,
            existing = members,
            onDismiss = { addingMember = false },
            onPick = { member ->
                members = members + member
                addingMember = false
            },
        )
    }
}

@Composable
private fun MemberPickerDialog(
    models: List<dev.aigw.core.provider.RoutedModel>,
    existing: List<GroupMember>,
    onDismiss: () -> Unit,
    onPick: (GroupMember) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val candidates = remember(models, keyword) {
        val key = keyword.trim().lowercase()
        models
            .filter { key.isEmpty() || it.fullId.lowercase().contains(key) }
            .filterNot { routed -> existing.any { it.providerId == routed.providerId && it.model == routed.model.id } }
            .take(60)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择成员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("搜索模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = "没有匹配的模型。先在「模型」页刷新目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                candidates.forEach { routed ->
                    Text(
                        text = routed.fullId,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(GroupMember(routed.providerId, routed.model.id)) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GroupRuleEditor(
    rule: GroupRule,
    members: List<GroupMember>,
    onChange: (GroupRule) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "交给 ${rule.targetProviderId}/${rule.targetModel}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 目标成员只允许从本组成员里换，否则规则会指向一个组里不存在的目标
                val currentIndex = members.indexOfFirst {
                    it.providerId == rule.targetProviderId && it.model == rule.targetModel
                }
                if (members.size > 1) {
                    Text(
                        text = "目标成员 #${currentIndex + 1}（点击切换）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            val next = members[(currentIndex + 1).coerceAtLeast(0) % members.size]
                            onChange(rule.copy(targetProviderId = next.providerId, targetModel = next.model))
                        },
                    )
                }
            }
            GroupAction("移除", onRemove)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = if (rule.minPromptTokens > 0) rule.minPromptTokens.toString() else "",
                onValueChange = { text ->
                    onChange(rule.copy(minPromptTokens = text.toLongOrNull()?.coerceAtLeast(0) ?: 0L))
                },
                label = { Text("至少多少 token") },
                placeholder = { Text("0 不限") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            ConditionChip("仅带图片", rule.requireImage) { onChange(rule.copy(requireImage = it)) }
            ConditionChip("仅压缩请求", rule.compactOnly) { onChange(rule.copy(compactOnly = it)) }
        }
        OutlinedTextField(
            value = rule.folders.joinToString("\n"),
            onValueChange = { text ->
                onChange(rule.copy(folders = text.lines().map { it.trim() }.filter { it.isNotEmpty() }))
            },
            label = { Text("工作目录（一行一条，留空不限）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = minuteText(rule.startMinuteOfDay),
                onValueChange = { onChange(rule.copy(startMinuteOfDay = parseMinute(it))) },
                label = { Text("起始 HH:mm") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = minuteText(rule.endMinuteOfDay),
                onValueChange = { onChange(rule.copy(endMinuteOfDay = parseMinute(it))) },
                label = { Text("结束 HH:mm") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConditionChip(text: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { onToggle(!selected) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** 「当天第几分钟」与 HH:mm 互转；-1 显示为空。 */
private fun minuteText(minute: Int): String =
    if (minute < 0) "" else "%02d:%02d".format(minute / 60, minute % 60)

private fun parseMinute(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return -1
    val parts = trimmed.split(":")
    if (parts.size != 2) return -1
    val hour = parts[0].toIntOrNull() ?: return -1
    val minute = parts[1].toIntOrNull() ?: return -1
    if (hour !in 0..23 || minute !in 0..59) return -1
    return hour * 60 + minute
}
