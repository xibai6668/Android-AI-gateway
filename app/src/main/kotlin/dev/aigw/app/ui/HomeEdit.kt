package dev.aigw.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * 首页编辑态的可拖动列表。
 *
 * 行高各不相同（有的是一整张卡片，有的只是三个按钮），所以槽位按实测高度累加得出。
 *
 * 让位动画的做法：条目在 [Column] 里仍按当前顺序占位，顺序一变，占位就瞬间换到新槽位；
 * 这里给每项记一个「视觉补偿量」，在换位瞬间把它设成「旧槽位 − 新槽位」，
 * 再用 spring 收回 0——视觉上就是平滑滑到新位置，而不是瞬移。
 * 被拖的那一项不参与补偿，位移直接跟手。
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
    val spacingPx = with(density) { ItemSpacing.toPx() }

    val visible = remember(order, hidden) { order.filterNot { it in hidden } }
    // 拖动过程中只改这份本地顺序，松手才回调出去，避免每帧写盘
    val items = remember(order, hidden) { mutableStateListOf(*visible.toTypedArray()) }

    val heights = remember { mutableStateMapOf<HomeWidget, Int>() }
    val compensations = remember { mutableStateMapOf<HomeWidget, Animatable<Float, AnimationVector1D>>() }
    val lastSlots = remember { mutableStateMapOf<HomeWidget, Float>() }

    var draggingId by remember { mutableStateOf<HomeWidget?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    val lift = remember { Animatable(0f) }

    fun slotOf(index: Int): Float {
        var y = 0f
        for (i in 0 until index) {
            y += (heights[items.getOrNull(i)] ?: 0) + spacingPx
        }
        return y
    }

    // 顺序或高度变化后，给除被拖项以外的条目补一段回位动画
    LaunchedEffect(items.toList(), heights.toMap(), draggingId) {
        items.forEachIndexed { index, widget ->
            val target = slotOf(index)
            val anim = compensations.getOrPut(widget) { Animatable(0f) }
            val previous = lastSlots[widget]
            lastSlots[widget] = target
            when {
                // 首次测量：直接落位，不做动画
                previous == null -> anim.snapTo(0f)
                // 被拖项跟手，不能补偿
                widget == draggingId -> anim.snapTo(0f)
                previous != target -> {
                    anim.snapTo(previous - target)
                    launch { anim.animateTo(0f, Springs.Snappy) }
                }
            }
        }
    }

    LaunchedEffect(draggingId) {
        lift.animateTo(
            targetValue = if (draggingId != null) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        )
    }

    val currentItems by rememberUpdatedState(items.toList())

    fun commit() {
        if (currentItems == visible) return
        // 只把可见项的新相对顺序合并回完整顺序，隐藏项原地不动
        val merged = order.toMutableList()
        val slots = merged.indices.filter { merged[it] !in hidden }
        slots.forEachIndexed { index, slot ->
            currentItems.getOrNull(index)?.let { merged[slot] = it }
        }
        onReorder(merged)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ItemSpacing),
    ) {
        items.forEach { widget ->
            val index = items.indexOf(widget)
            if (index < 0) return@forEach
            val isDragging = draggingId == widget
            val compensation = compensations[widget]?.value ?: 0f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        // 被拖项：槽位 + 手指位移；其余项：槽位 + 回位补偿
                        translationY = if (isDragging) dragDelta else compensation
                        val scale = 1f + LiftScale * lift.value
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(
                        if (isDragging) {
                            Modifier.shadow(LiftElevation * lift.value, MaterialTheme.shapes.large)
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(widget) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingId = widget
                                dragDelta = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                draggingId = null
                                dragDelta = 0f
                                commit()
                            },
                            onDragCancel = {
                                draggingId = null
                                dragDelta = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragDelta += amount.y
                                val from = items.indexOf(widget)
                                if (from < 0) return@detectDragGesturesAfterLongPress
                                val selfHeight = (heights[widget] ?: 0).toFloat()
                                if (selfHeight <= 0f) return@detectDragGesturesAfterLongPress
                                if (dragDelta > 0f) {
                                    val next = items.getOrNull(from + 1) ?: return@detectDragGesturesAfterLongPress
                                    val nextHeight = (heights[next] ?: 0).toFloat()
                                    val threshold = (selfHeight + nextHeight) / 2f + spacingPx
                                    if (dragDelta > threshold) {
                                        items.removeAt(from)
                                        items.add(from + 1, widget)
                                        // 让出下一行的高度，视觉位置保持不动
                                        dragDelta -= nextHeight + spacingPx
                                    }
                                } else if (dragDelta < 0f) {
                                    val prev = items.getOrNull(from - 1) ?: return@detectDragGesturesAfterLongPress
                                    val prevHeight = (heights[prev] ?: 0).toFloat()
                                    val threshold = (selfHeight + prevHeight) / 2f + spacingPx
                                    if (dragDelta < -threshold) {
                                        items.removeAt(from)
                                        items.add(from - 1, widget)
                                        dragDelta += prevHeight + spacingPx
                                    }
                                }
                            },
                        )
                    },
            ) {
                Box(modifier = Modifier.onSizeChanged { heights[widget] = it.height }) {
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

    // 外部把组件加回来时同步本地列表
    LaunchedEffect(visible) {
        if (items.toList() != visible) {
            items.clear()
            items.addAll(visible)
        }
    }
}

private val ItemSpacing = 14.dp
private val LiftElevation = 12.dp
private const val LiftScale = 0.03f

/** 编辑态外壳：拖动手柄 + 标题 + 右侧移除。 */
@Composable
private fun EditableWidgetFrame(
    title: String,
    onRemove: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
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
