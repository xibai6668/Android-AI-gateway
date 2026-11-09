package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.aigw.app.ui.theme.LocalAppColors

/**
 * 首页编辑态：独立的可拖动列表。
 *
 * 关键点：条目**真的会重新排列**（用 LazyColumn 换 key 换位置），
 * 重叠与空洞的根源是「只平移视觉位置、不改实际占位」。
 * 让位动画交给 LazyColumn 自带的 animateItem，拖起来时被拖项跟手、其余项平滑交换。
 */
@Composable
internal fun EditableHomeWidgetList(
    order: List<HomeWidget>,
    hidden: Set<HomeWidget>,
    onReorder: (List<HomeWidget>) -> Unit,
    onRemove: (HomeWidget) -> Unit,
    content: @Composable (HomeWidget) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    val visible = remember(order, hidden) { order.filterNot { it in hidden } }
    val items = remember(order, hidden) { mutableStateListOf(*visible.toTypedArray()) }

    var draggingId by remember { mutableStateOf<HomeWidget?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableMapOf<HomeWidget, Float>() }

    val currentItems by rememberUpdatedState(items.toList())

    fun commit() {
        if (currentItems == visible) return
        val merged = order.toMutableList()
        val slots = merged.indices.filter { merged[it] !in hidden }
        slots.forEachIndexed { index, slot ->
            currentItems.getOrNull(index)?.let { merged[slot] = it }
        }
        onReorder(merged)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ItemSpacing),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        items(items, key = { it }) { widget ->
            val isDragging = draggingId == widget
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationY = dragOffset
                            val scale = 1f + LiftScale
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                    .onGloballyPositioned { coords ->
                        itemHeights[widget] = coords.size.height.toFloat()
                    }
                    .pointerInput(widget) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingId = widget
                                dragOffset = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                draggingId = null
                                dragOffset = 0f
                                commit()
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val from = currentItems.indexOf(widget)
                                if (from < 0) return@detectDragGesturesAfterLongPress
                                val spacing = with(density) { ItemSpacing.toPx() }
                                val selfHeight = itemHeights[widget] ?: 0f
                                if (dragOffset > 0f) {
                                    val next = currentItems.getOrNull(from + 1) ?: return@detectDragGesturesAfterLongPress
                                    val nextHeight = itemHeights[next] ?: 0f
                                    val threshold = (selfHeight + nextHeight) / 2f + spacing
                                    if (dragOffset > threshold) {
                                        items.removeAt(from)
                                        items.add(from + 1, widget)
                                        dragOffset -= nextHeight + spacing
                                    }
                                } else if (dragOffset < 0f) {
                                    val prev = currentItems.getOrNull(from - 1) ?: return@detectDragGesturesAfterLongPress
                                    val prevHeight = itemHeights[prev] ?: 0f
                                    val threshold = (selfHeight + prevHeight) / 2f + spacing
                                    if (dragOffset < -threshold) {
                                        items.removeAt(from)
                                        items.add(from - 1, widget)
                                        dragOffset += prevHeight + spacing
                                    }
                                }
                            },
                        )
                    },
            ) {
                EditableWidgetFrame(
                    title = widget.title,
                    dragging = isDragging,
                    onRemove = { onRemove(widget) },
                ) {
                    content(widget)
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (items.toList() != visible) {
            items.clear()
            items.addAll(visible)
        }
    }
}

private val ItemSpacing = 12.dp
private const val LiftScale = 0.025f

/** 编辑态外壳：拖动手柄 + 标题 + 右侧移除。 */
@Composable
private fun EditableWidgetFrame(
    title: String,
    dragging: Boolean,
    onRemove: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val borderColor = if (dragging) {
        LocalAppColors.current.goldStart
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除 $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(4.dp), content = content)
    }
}

/** 编辑态按钮：与原状态胶囊同高，避免页头高度在进入/退出编辑时跳动。 */
@Composable
internal fun HomeEditAction(editing: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            if (editing) Icons.Filled.Close else Icons.Filled.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (editing) "完成" else "编辑",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 添加组件：列出当前被隐藏的组件，点击即放回末尾。 */
@Composable
internal fun AddHomeWidgetDialog(
    hidden: Set<HomeWidget>,
    onAdd: (HomeWidget) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = HomeWidget.entries.filter { it in hidden }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加组件") },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    text = "所有组件都已在首页",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    candidates.forEach { widget ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onAdd(widget) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = widget.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 页头在编辑态的提示条。 */
@Composable
internal fun HomeEditHint(isEmpty: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isEmpty) "首页没有组件，点右上角「+」添加" else "长按拖动调整顺序，点右上角移除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
