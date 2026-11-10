package dev.aigw.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.core.pool.AccountStatus

/** 账号状态文案：只有凭证失效与手动停用两种异常态，正常即「可用」。 */
fun accountStatusText(account: AccountStatus): String = when {
    account.disabled -> "凭证失效，需重新登录"
    !account.enabled -> "已停用"
    else -> "可用"
}

/** 编辑式页头：左侧大标题 + 副标题 + 金色短下划线，右侧动作；[leading]（二级页返回）在标题行左侧。 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
) {
    val gold = LocalAppColors.current.goldStart
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    modifier = if (onTitleClick != null) {
                        Modifier.clip(CircleShape).clickable(onClick = onTitleClick).padding(vertical = 2.dp)
                    } else {
                        Modifier
                    },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
        Box(Modifier.size(width = 64.dp, height = 3.dp).background(gold))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 悬浮底栏占用的底部高度。
 *
 * 内容层铺满全屏（让玻璃有像素可采样），避让只能由内容自己让出来；
 * 用 CompositionLocal 传，避免给上百处 PageScaffold 调用加参数。
 */
val LocalBottomContentPadding = staticCompositionLocalOf<Dp> { 0.dp }

/** 统一页面骨架：页头 + 可滚动内容区。 */
@Composable
fun PageScaffold(
    title: String,
    subtitle: String,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 悬浮底栏浮在内容之上，内容需要自己让出底部空间，否则最后一项会被玻璃盖住。
    // 用 CompositionLocal 而不是参数：页面上百处调用，不必逐个传。
    val bottomInset = LocalBottomContentPadding.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(title, subtitle, leading, actions, onTitleClick)
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 滚动到顶部/底部时内容与页头、屏幕边缘直接相接，看着像被切了一刀；
                // 用背景色渐隐过渡（Motion.scrollFade），让长页面有「还能滚」的提示
                .scrollFade()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 14.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/**
 * 白底描边卡片：1px 细线 + 圆角，无阴影。
 *
 * [enterIndex] 非空时卡片带错峰入场动效（第 n 张延迟 40ms·n），用于列表/表单页的首屏。
 * [pressable] 为 true 时整张卡带按压回弹（可点入口专用）：卡片可点却毫无按下反馈时，
 * 点上去像没反应，尤其在低帧率设备上。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    enterIndex: Int? = null,
    pressable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enterIndex != null) Modifier.staggeredAppear(enterIndex) else Modifier)
            .then(if (pressable) Modifier.pressScale() else Modifier)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            .then(modifier),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * 账号分页：把超长账号列表切成每页固定条数，避免一次性组合全部卡片。
 *
 * 页码用不可变 Int 保存（不要用 SnapshotStateList 等快照类型做 rememberSaveable，
 * 本工程已因此类快照在保存时机被读取而闪退过）。
 *
 * [itemContent] 的第一个参数是**页内序号**（当前页从 0 起，而非全局序号）：
 * 直接喂给 `staggeredAppear` 这类按序号线性递增延迟的动效，翻到任意一页都从零开始错峰，
 * 不会因全局序号过大导致末页卡片等上好几秒才出现。
 */
@Composable
fun <T> PagedAccountList(
    items: List<T>,
    modifier: Modifier = Modifier,
    pageSize: Int = 30,
    keyOf: (T) -> String,
    itemContent: @Composable (pageIndex: Int, item: T) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    val pageCount = if (items.isEmpty()) 1 else (items.size + pageSize - 1) / pageSize
    // 列表变短（删账号、切区域）后旧页码可能越界：本帧先夹取显示，再由 effect 写回，
    // 否则列表重新变长时会突然跳回那个已失效的大页码。
    val currentPage = page.coerceIn(0, pageCount - 1)
    LaunchedEffect(pageCount) {
        if (page != currentPage) page = currentPage
    }
    val visible = items.drop(currentPage * pageSize).take(pageSize)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        visible.forEachIndexed { pageIndex, item ->
            // 以 uid 建键：卡片内部的展开/编辑状态跟着账号走，而不是跟着列表位置走
            key(keyOf(item)) {
                itemContent(pageIndex, item)
            }
        }
        // 只有一个页时不渲染分页条，保持既有单页外观，避免视觉噪音
        if (items.size > pageSize) {
            PagerBar(currentPage, pageCount, items.size) { page = it }
        }
    }
}

/** 账号列表底部分页条：上一页 / 页码信息 / 下一页，边界处按钮禁用。 */
@Composable
private fun PagerBar(
    page: Int,
    pageCount: Int,
    total: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PagerChip("上一页", enabled = page > 0) { onSelect(page - 1) }
        Text(
            text = "第 ${page + 1} / $pageCount 页 · 共 $total 个",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        PagerChip("下一页", enabled = page < pageCount - 1) { onSelect(page + 1) }
    }
}

/** 分页条上的胶囊按钮：沿用 ChoiceChip 的描边胶囊样式，禁用时整体降透明度。 */
@Composable
private fun PagerChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    val content = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, content, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 「运行中 / 已停止」状态胶囊：白底描边 + 圆点（运行中金色带浅光环）。 */
@Composable
fun StatusChip(running: Boolean) {
    val gold = LocalAppColors.current.goldStart
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (running) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(gold.copy(alpha = 0.18f)),
                )
            }
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (running) gold else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
        Text(
            text = if (running) "运行中" else "已停止",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 统计单元：小灰标签在上、大号细体数字在下；[indicatorColor] 作数字颜色。 */
@Composable
fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    indicatorColor: Color = Color.Unspecified,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            color = if (indicatorColor != Color.Unspecified) indicatorColor else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 分段/筛选胶囊。选中=黑底白字，未选中=白底细描边。 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        animationSpec = tween(200),
        label = "choiceContainer",
    )
    val content = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .pressScale()
            .clip(CircleShape)
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** 可点击复制的地址条（首页与接入设置共用）。 */
@Composable
fun AddressTile(value: String, onCopy: () -> Unit) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .clickable(onClick = onCopy)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** 二级页面的返回按钮。 */
@Composable
fun BackButton(onBack: () -> Unit) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "返回",
        tint = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onBack)
            .padding(6.dp),
    )
}

/** 全宽描边按钮。onClick 放最后以支持尾随 lambda。 */
@Composable
fun OutlineActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

@Composable
/**
 * 空态 / 错误态提示：标题 + 说明 + 可选的下一步动作。
 *
 * 光说「没有数据」等于把用户晾在那儿；给出 [actionLabel] 时一并渲染按钮，
 * 由调用方决定下一步是什么（去登录、重新拉取、重试）。
 */
fun EmptyHint(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Box(modifier = Modifier.padding(top = 2.dp)) {
                OutlineActionButton(actionLabel, onClick = onAction)
            }
        }
    }
}

/** 小灰大写小节标签（中文 · ENGLISH）。 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
    )
}

/** 小节头：左小灰大写标题 + 右侧可选金色小链接。 */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val goldText = LocalAppColors.current.goldText
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                color = goldText,
                modifier = if (onAction != null) Modifier.clickable(onClick = onAction) else Modifier,
            )
        }
    }
}

/** 编辑式分组头：金色小竖条 + 「index / 标题」小灰大写 + 右侧可选元信息。 */
@Composable
fun EditorialGroupHeader(index: String, title: String, meta: String? = null) {
    val gold = LocalAppColors.current.goldStart
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 4.dp, height = 15.dp).background(gold))
        Text(
            text = "$index / $title",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (meta != null) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 细进度条：用于列表页展示「已用/可用」占比。
 *
 * [fraction] 为 null 表示占比未知（如额度字段缺失）——这时不画条，避免把「不知道」
 * 画成 0%（与本项目「数据来源要诚实」的原则一致）。
 */
@Composable
fun ThinRatioBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val animated by animateFloatAsState(
        targetValue = (fraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(Motion.BAR_FILL_MILLIS, easing = FastOutSlowInEasing),
        label = "thinRatioBar",
    )
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(height)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * 额度包进度条：一行「名称（分组）+ 过期时间」，下方细进度条 + 右侧百分比。
 * 用权重绘制而不是 LinearProgressIndicator，保证在所有背景下颜色可控。
 */
@Composable
fun QuotaPackBar(pack: dev.aigw.core.provider.QuotaPack) {
    val gold = LocalAppColors.current.goldStart
    // 格式化器创建成本不低，按过期时间缓存结果，避免每张额度条每次重组都新建
    val expireText = remember(pack.expireAt) {
        if (pack.expireAt > 0) {
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT,
            ).format(java.util.Date(pack.expireAt * 1000))
        } else {
            ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (pack.group.isEmpty()) pack.name else "${pack.name}（${pack.group}）",
                    style = MaterialTheme.typography.bodySmall,
                )
                // 模型专属配额（如 Gemini 4 Argon 周额度）：不标出来就会被误当成账号总额度
                if (pack.scope.isNotEmpty()) {
                    Text(
                        text = pack.scope,
                        style = MaterialTheme.typography.labelSmall,
                        color = gold,
                    )
                }
            }
            Text(
                text = expireText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fraction = if (pack.limit > 0) {
                (pack.remain.toFloat() / pack.limit).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                val barFraction by animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = tween(Motion.BAR_FILL_MILLIS, easing = FastOutSlowInEasing),
                    label = "quotaBar",
                )
                Box(
                    Modifier
                        .fillMaxWidth(barFraction)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                fraction > 0.5f -> MaterialTheme.colorScheme.onSurface
                                fraction > 0.2f -> gold
                                else -> MaterialTheme.colorScheme.error
                            },
                        ),
                )
            }
            Text(
                text = if (pack.limit > 0) {
                    "${(fraction * 100).toInt()}% (${pack.remain}/${pack.limit})"
                } else {
                    "—"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一笔积分/额度流水：左侧事件名与时间，右侧带符号的变动值。
 *
 * 供应商无关：小浣熊的积分明细与「额度中心」的流水共用这一处渲染。
 */
@Composable
fun PointsBillRow(bill: dev.aigw.core.provider.PointsBill, modifier: Modifier = Modifier) {
    val time = remember(bill.createdAtMillis) {
        if (bill.createdAtMillis > 0) {
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT,
            ).format(java.util.Date(bill.createdAtMillis))
        } else {
            ""
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = bill.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (time.isNotEmpty()) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = if (bill.points > 0) "+${bill.points}" else bill.points.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                bill.points > 0 -> MaterialTheme.colorScheme.tertiary
                bill.points < 0 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 流水区块的提示行（加载中 / 失败 / 空）；[error] 为 true 时用错误色。 */
@Composable
fun PointsBillsHint(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 对话框内容的标准容器。
 *
 * `AlertDialog` 的 `text` 槽不会滚动，内容超过可用高度就被直接裁掉（标题与按钮还在，
 * 中段却看不见也划不动）。所有内容可变的对话框都应该用它包一层。
 */
@Composable
fun DialogContentColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * 多选底部抽屉：搜索 + 全选/清空 + 复选行，勾完一次性确认。
 *
 * 用抽屉而不是对话框：候选常有上百条（完整模型目录），对话框的高度受限且不可滚动，
 * 列表会被直接裁掉；抽屉里可以拿满屏高度、用 LazyColumn 懒加载，也不会误触到底下的页面。
 *
 * [preselected] 是进来时就已经选中的键（如密钥已有的白名单），[hiddenKeys] 是压根不该出现的键
 * （如已经加进组的成员）。确认时回调保持 [items] 的顺序，避免选择顺序影响结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> MultiSelectSheet(
    title: String,
    subtitle: String,
    items: List<T>,
    keyOf: (T) -> String,
    labelOf: (T) -> String,
    emptyHint: String,
    confirmLabel: (Int) -> String,
    onDismiss: () -> Unit,
    onConfirm: (List<T>) -> Unit,
    metaOf: (T) -> String? = { null },
    preselected: Set<String> = emptySet(),
    hiddenKeys: Set<String> = emptySet(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var keyword by remember { mutableStateOf("") }
    var selected by remember(preselected) { mutableStateOf(preselected) }

    val key = keyword.trim().lowercase()
    val candidates = remember(items, key, hiddenKeys) {
        items.filter { it ->
            keyOf(it) !in hiddenKeys && (key.isEmpty() || labelOf(it).lowercase().contains(key))
        }
    }
    val allSelected = candidates.isNotEmpty() && candidates.all { keyOf(it) in selected }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidates.isNotEmpty()) {
                    TextButton(onClick = {
                        // 只作用于当前搜索结果：搜完关键词再全选，才不会把没看见的条目一起选上
                        val keys = candidates.map(keyOf).toSet()
                        selected = if (allSelected) selected - keys else selected + keys
                    }) { Text(if (allSelected) "清空" else "全选") }
                }
            }

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("搜索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (candidates.isEmpty()) {
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(candidates, key = { keyOf(it) }) { item ->
                        val itemKey = keyOf(item)
                        val checked = itemKey in selected
                        val meta = metaOf(item)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    selected = if (checked) selected - itemKey else selected + itemKey
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + itemKey else selected - itemKey
                                },
                                modifier = Modifier.size(22.dp),
                            )
                            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(
                                    text = labelOf(item),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (meta != null) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选 ${selected.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = { onConfirm(items.filter { keyOf(it) in selected }) },
                    enabled = selected.isNotEmpty(),
                ) { Text(confirmLabel(selected.size)) }
            }
        }
    }
}
