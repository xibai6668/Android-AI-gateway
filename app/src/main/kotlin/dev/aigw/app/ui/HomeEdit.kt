package dev.aigw.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.aigw.app.ui.theme.LocalAppColors
import kotlin.math.abs

/**
 * 首页编辑态的可拖动列表。
 *
 * 三条不可违反的约束（前几版全踩过，表现为「拖不动」「组件重叠」「中间留白」）：
 * 1. **拖动期间不改 Modifier 链**。按拖动状态增删 `animateItem()`/`shadow()` 会让 Compose
 *    重建修饰符节点，正在进行的拖动手势当场被取消——长按有反馈，但手指一动就断。
 *    这里整条链固定，位移与缩放只通过 `graphicsLayer` 的浮点值变化实现。
 * 2. **拖动期间不改列表顺序**。重排会让条目换槽位，位置动画再叠上 `translationY` 就是双重位移，
 *    于是出现错位与空洞。这里顺序只在松手那一刻变。
 * 3. **让位用索引算，不依赖实测槽位累加**。条目高度不等，用「被拖项高度 + 间距」整体让一行，
 *    索引区间外的条目一律 0 偏移。
 *
 * 松手时的连续性：拖动中 A 已被画在 B 的槽位上（靠 B 让出 A 的高度），松手瞬间顺序换成新顺序、
 * 所有偏移归零，两者在同一帧生效，视觉位置不变，因此不会闪一下。
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
    val listState = rememberLazyListState()

    val visible = remember(order, hidden) { order.filterNot { it in hidden } }
    val items = remember(order, hidden) { mutableStateListOf(*visible.toTypedArray()) }
    val heights = remember { mutableStateMapOf<HomeWidget, Int>() }

    var draggingId by remember { mutableStateOf<HomeWidget?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableStateOf(-1) }

    val currentItems by rememberUpdatedState(items.toList())
    val currentTarget by rememberUpdatedState(targetIndex)

    fun slotTopOf(index: Int): Float {
        var y = 0f
        for (i in 0 until index) {
            y += (heights[currentItems.getOrNull(i)] ?: 0) + spacingPx
        }
        return y
    }

    /** 手指把被拖项带到了哪个槽位。用「视觉中心落在哪个条目的中心区间内」判断。 */
    fun resolveTarget(from: Int): Int {
        val fromHeight = (heights[currentItems.getOrNull(from)] ?: 0).toFloat()
        if (fromHeight <= 0f) return from
        val visualCenter = slotTopOf(from) + fromHeight / 2f + dragOffset
        var best = from
        var bestDistance = Float.MAX_VALUE
        currentItems.indices.forEach { i ->
            val h = (heights[currentItems[i]] ?: 0).toFloat()
            val center = slotTopOf(i) + h / 2f
            val distance = abs(visualCenter - center)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    fun commit() {
        val from = currentItems.indexOf(draggingId ?: return)
        val to = currentTarget
        if (from < 0 || to < 0 || from == to) return
        val reordered = currentItems.toMutableList().apply { add(to, removeAt(from)) }
        if (reordered == visible) return
        // 只把可见项的新相对顺序合并回完整顺序，隐藏项原地不动
        val merged = order.toMutableList()
        val slots = merged.indices.filter { merged[it] !in hidden }
        slots.forEachIndexed { index, slot ->
            reordered.getOrNull(index)?.let { merged[slot] = it }
        }
        onReorder(merged)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ItemSpacing),
        contentPadding = PaddingValues(bottom = 24.dp),
        // 拖动中关掉列表滚动，否则垂直手势会被父级 scrollable 先消费。
        // 这个开关变化不会重建 item 的 Modifier 链，因此不会打断进行中的手势。
        userScrollEnabled = draggingId == null,
    ) {
        itemsIndexed(items, key = { _, widget -> widget }) { index, widget ->
            val isDragging = draggingId == widget
            val dragged = currentItems.getOrNull(currentItems.indexOf(draggingId))

            // 让位：只有「被拖项经过的区间」才整行让开，其余保持 0
            val from = currentItems.indexOf(draggingId)
            val shiftTarget = if (draggingId == null || dragged == null || isDragging || from < 0 || targetIndex < 0) {
                0f
            } else {
                val step = (heights[dragged] ?: 0) + spacingPx
                when {
                    targetIndex > from && index in (from + 1)..targetIndex -> -step
                    targetIndex < from && index in targetIndex until from -> step
                    else -> 0f
                }
            }
            val shift by animateFloatAsState(
                targetValue = shiftTarget,
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "shift$widget",
            )
            val liftTarget = if (isDragging) 1f else 0f
            val currentLift by animateFloatAsState(
                targetValue = liftTarget,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                label = "lift$widget",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Modifier 链固定：任何随状态增删的修饰符都会重建节点、中断拖动
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else shift
                        val scale = 1f + LiftScale * currentLift
                        scaleX = scale
                        scaleY = scale
                        shadowElevation = LiftElevation.toPx() * currentLift
                        shape = RoundedCornerShape(16.dp)
                        clip = false
                    }
                    .onSizeChanged { heights[widget] = it.height }
                    .pointerInput(widget) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingId = widget
                                dragOffset = 0f
                                targetIndex = currentItems.indexOf(widget)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                commit()
                                draggingId = null
                                dragOffset = 0f
                                targetIndex = -1
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = 0f
                                targetIndex = -1
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val f = currentItems.indexOf(widget)
                                if (f >= 0) targetIndex = resolveTarget(f)
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

    // 外部改了布局（加回组件等）时同步本地列表；拖动中不动，避免打断手势
    LaunchedEffect(visible, draggingId) {
        if (draggingId == null && items.toList() != visible) {
            items.clear()
            items.addAll(visible)
        }
    }
}

private val ItemSpacing = 12.dp
private val LiftElevation = 10.dp
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
