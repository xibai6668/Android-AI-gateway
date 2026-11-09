package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
/**
 * 首页编辑态的可拖动列表。
 *
 * 拖拽而不是上下按钮：手机上一屏能看到的组件有限，反复点箭头挪一行要按很多次。
 * 长按进入拖动（[detectDragGesturesAfterLongPress]）而不是直接拖，是为了不和组件内部的点击、
 * 色轮的横向拖动抢手势。
 */
@Composable
internal fun EditableHomeWidgetList(
    order: List<HomeWidget>,
    hidden: Set<HomeWidget>,
    onReorder: (List<HomeWidget>) -> Unit,
    onRemove: (HomeWidget) -> Unit,
    content: @Composable (HomeWidget) -> Unit,
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf<HomeWidget?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // 手势块只在组装时建立一次；顺序换位后不能重启指针输入，
    // 否则拖动进行到一半时 pointerInput 被重建，手一离开就断了（表现为一次只能挪一格）。
    val currentOrder by rememberUpdatedState(order)
    val currentHidden by rememberUpdatedState(hidden)
    val currentOnReorder by rememberUpdatedState(onReorder)

    // 每个条目实测高度（含卡片间距），拖拽过半就是换位
    val rowHeights = remember { HashMap<HomeWidget, Int>() }

    // 换位逻辑抽成函数：onDrag 是嵌套 lambda，用 return@detectDragGesturesAfterLongPress
    // 从里面返回并不合法（那是给 onDrag 外层用的），只能靠函数提前退出。
    fun handleDrag(widget: HomeWidget, deltaY: Float): Float {
        val visible = currentOrder.filterNot { it in currentHidden }
        val from = visible.indexOf(widget)
        if (from < 0) return deltaY
        val threshold = with(density) { (rowHeights[widget] ?: 0).toFloat() / 2f }
        if (threshold <= 0f) return deltaY
        val direction = when {
            deltaY > threshold -> 1
            deltaY < -threshold -> -1
            else -> 0
        }
        if (direction == 0) return deltaY
        val to = from + direction
        if (to !in visible.indices) return deltaY
        val reordered = visible.toMutableList().apply { add(to, removeAt(from)) }
        // 只把可见项的新相对顺序合并回完整顺序，隐藏项原地不动
        val merged = currentOrder.toMutableList()
        val visibleSlots = merged.indices.filter { merged[it] !in currentHidden }
        visibleSlots.forEachIndexed { index, slot -> merged[slot] = reordered[index] }
        currentOnReorder(merged)
        return deltaY - direction * threshold * 2f
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        order.filterNot { it in hidden }.forEach { widget ->
            val isDragging = dragging == widget
            val dragModifier = Modifier
                .fillMaxWidth()
                .zIndex(if (isDragging) 1f else 0f)
                .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                .pointerInput(widget) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragging = widget
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            dragging = null
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            dragging = null
                            dragOffset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset = handleDrag(widget, dragOffset + amount.y)
                        },
                    )
                }
            Box(modifier = dragModifier) {
                Box(modifier = Modifier.onMeasured { rowHeights[widget] = it }) {
                    EditableWidgetFrame(
                        title = widget.title,
                        onRemove = { onRemove(widget) },
                    ) {
                        content(widget)
                    }
                }
            }
        }
    }
}

/** 编辑态外壳：虚线描边 + 顶部标题条 + 右侧删除。 */
@Composable
private fun EditableWidgetFrame(
    title: String,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), shape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除 $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        content()
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
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

/** 页头在编辑态的提示条：说明当前能做什么。 */
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
            text = if (isEmpty) "首页没有组件，点右上角「+」添加" else "长按拖动调整顺序，点组件右上角移除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 记录条目高度，用于计算拖拽换位的阈值。 */
private fun Modifier.onMeasured(onSize: (Int) -> Unit): Modifier =
    this.onSizeChanged { onSize(it.height) }
