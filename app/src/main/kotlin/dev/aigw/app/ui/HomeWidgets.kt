package dev.aigw.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aigw.app.ui.theme.DeepInk
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.util.startOfDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页的可选组件：实时调度拓扑、遥测指标网格、消耗趋势。
 *
 * 这三块在首页改版时从主屏移除，改由用户从「+ 添加组件」里自行摆回。
 * 实现照搬改版前的版本，只把入口改成 internal 并去掉与独立组件重复的内嵌块。
 */

/** 趋势图展示天数。 */
private const val HOME_TREND_DAYS = 14

// ---------------------------------------------------------------------- 实时调度拓扑

private data class TopologyProviderBranch(
    val providerId: String,
    val displayName: String,
    val usableAccounts: Int,
    val totalAccounts: Int,
    val weight: Float,
    val hasActiveRequest: Boolean,
    val activeCount: Int,
    val colorIndex: Int,
)

/**
 * 实时调度拓扑卡（数据驱动的供应商分发视图）：
 * - 中枢节点：网关（显示 :port，运行中呼吸发光，停止时静止置灰）
 * - 分支节点：真实有账号的供应商，显示供应商名 + 可用账号数
 * - 分支权重（线宽/流动/亮度）：按最近 50 次请求里该供应商的请求数占比；无请求时退化为可用账号数占比
 * - 在途请求：所在供应商高亮金色并流动光斑，没有在途时不流动、不高亮
 * - 多分支折叠：超过 4 个供应商时前 3 个展示，其余合并为「+N 其它」
 *
 * 与改版前的区别：原来这张卡底部还挂着「最近路由流水」与「上下文容量」两块，
 * 它们如今是首页的独立组件，这里不再重复渲染。
 */
@Composable
internal fun HomeTopologyWidget(state: AppUiState, modifier: Modifier = Modifier) {
    val gold = LocalAppColors.current.goldStart
    val goldEnd = LocalAppColors.current.goldEnd
    val palette = LocalAppColors.current.providerPalette
    val isRunning = state.running

    val recentCalls = remember(state.calls) { state.calls.take(50) }
    val activeRequests = state.activeRequests
    val hasActive = isRunning && activeRequests.isNotEmpty()

    val callCountsByProvider = remember(recentCalls) {
        recentCalls.groupBy { it.providerId.lowercase() }.mapValues { it.value.size }
    }
    val totalRecentCalls = remember(callCountsByProvider) { callCountsByProvider.values.sum() }

    val activeCountsByProvider = remember(activeRequests) {
        activeRequests.groupBy { it.providerId.lowercase() }.mapValues { it.value.size }
    }

    val branches = remember(state.accounts, state.providers, callCountsByProvider, activeCountsByProvider, totalRecentCalls) {
        val groupedAccounts = state.accounts.groupBy { it.providerId.lowercase() }
        if (groupedAccounts.isEmpty()) {
            emptyList()
        } else {
            val totalUsableOverall = state.accounts.count { it.usable }.coerceAtLeast(1)
            val sortedProviderIds = groupedAccounts.keys.sortedByDescending { pid ->
                val callCount = callCountsByProvider[pid] ?: 0
                val usable = groupedAccounts[pid]?.count { it.usable } ?: 0
                // 优先按请求数排序，相同时按可用账号数
                (callCount.toLong() shl 16) + usable
            }

            sortedProviderIds.mapIndexed { index, pid ->
                val accs = groupedAccounts[pid] ?: emptyList()
                val usable = accs.count { it.usable }
                val provInfo = state.providers.firstOrNull { it.id.equals(pid, ignoreCase = true) }
                val dName = provInfo?.displayName?.ifEmpty { pid.uppercase() } ?: pid.uppercase()

                val weight = if (totalRecentCalls > 0) {
                    ((callCountsByProvider[pid] ?: 0).toFloat() / totalRecentCalls).coerceIn(0.08f, 1f)
                } else {
                    (usable.toFloat() / totalUsableOverall).coerceIn(0.08f, 1f)
                }

                TopologyProviderBranch(
                    providerId = pid,
                    displayName = dName,
                    usableAccounts = usable,
                    totalAccounts = accs.size,
                    weight = weight,
                    hasActiveRequest = (activeCountsByProvider[pid] ?: 0) > 0,
                    activeCount = activeCountsByProvider[pid] ?: 0,
                    colorIndex = index,
                )
            }
        }
    }

    val basisCaption = if (totalRecentCalls > 0) {
        "按近 50 次请求分布"
    } else if (state.accounts.isNotEmpty()) {
        "暂无请求，按可用账号分布"
    } else {
        "暂无配置账号"
    }

    // 在途流光：曲线上的光斑进度 0f→1f，只在绘制阶段读取，不逐帧重组。
    // 1.6s 一趟比原来的 1s 慢一些，长曲线上的光斑才不会显得在跳。
    val flowPhase: State<Float> = rememberInfiniteTransition(label = "topologyFlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "topologyFlowPhase",
    )

    SectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.breathingDot(
                    color = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    size = 7.dp,
                    breathing = isRunning,
                    haloSize = 15.dp,
                ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "实时调度拓扑",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = basisCaption,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (activeRequests.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(gold.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${activeRequests.size} 在途",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = goldEnd,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "TODAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    AnimatedContent(
                        targetState = state.today.requests,
                        transitionSpec = {
                            slideInVertically(
                                animationSpec = tween(Motion.TAB_MILLIS, easing = FastOutSlowInEasing),
                            ) { it / 2 } + fadeIn() togetherWith
                                slideOutVertically(
                                    animationSpec = tween(Motion.TAB_MILLIS, easing = FastOutSlowInEasing),
                                ) { -it / 2 } + fadeOut()
                        },
                        label = "todayRequests",
                    ) { reqCount ->
                        Text(
                            text = if (reqCount > 0) formatCount(reqCount) else "—",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = if (reqCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        if (branches.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无可用供应商账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 分支折叠逻辑：最多显示 3 个主要分支，超过则把剩余合并为「+N」
            val maxVisibleBranches = 3
            val displayBranches = if (branches.size > 4) branches.take(maxVisibleBranches) else branches
            val remainingCount = if (branches.size > 4) branches.size - maxVisibleBranches else 0

            // 连线要按各行的真实位置扇出，先把容器、中枢与每一行在 root 坐标系里的矩形量出来
            var containerRect by remember { mutableStateOf<Rect?>(null) }
            var hubRect by remember { mutableStateOf<Rect?>(null) }
            val rowRects = remember { mutableStateMapOf<String, Rect>() }
            var otherRect by remember { mutableStateOf<Rect?>(null) }
            val branchById = remember(branches) { branches.associateBy { it.providerId } }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { containerRect = it.boundsInRoot() },
            ) {
                TopologyWiresCanvas(
                    container = containerRect,
                    hub = hubRect,
                    rows = rowRects,
                    branches = branchById,
                    other = otherRect,
                    isRunning = isRunning,
                    gold = gold,
                    goldEnd = goldEnd,
                    phase = flowPhase,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GatewayHubNode(
                        isRunning = isRunning,
                        port = state.port,
                        gold = gold,
                        goldEnd = goldEnd,
                        hasActive = hasActive,
                        modifier = Modifier.onGloballyPositioned { hubRect = it.boundsInRoot() },
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 44.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        displayBranches.forEach { branch ->
                            val branchColor = palette[branch.colorIndex % palette.size]
                            ProviderBranchRow(
                                branch = branch,
                                branchColor = branchColor,
                                gold = gold,
                                isRunning = isRunning,
                                modifier = Modifier.onGloballyPositioned {
                                    rowRects[branch.providerId] = it.boundsInRoot()
                                },
                            )
                        }

                        if (remainingCount > 0) {
                            val otherUsable = branches.drop(maxVisibleBranches).sumOf { it.usableAccounts }
                            Box(
                                modifier = Modifier
                                    .onGloballyPositioned { otherRect = it.boundsInRoot() }
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = "+$remainingCount 其它 (${otherUsable}可用)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBranchRow(
    branch: TopologyProviderBranch,
    branchColor: Color,
    gold: Color,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val isBranchActive = branch.hasActiveRequest && isRunning
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .drawBehind {
                if (isBranchActive) {
                    drawRoundRect(
                        color = gold.copy(alpha = 0.15f),
                        size = size,
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                }
            }
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (isBranchActive) 1.5.dp else 1.dp,
                color = if (isBranchActive) gold else branchColor.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(branchColor))
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = branch.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBranchActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                    if (branch.activeCount > 0) {
                        Text(
                            text = "(${branch.activeCount}在途)",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            color = gold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = "${branch.usableAccounts}/${branch.totalAccounts} 可用",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 拓扑连线层：从中枢节点右缘向每个分支行的左缘扇出贝塞尔曲线。
 *
 * - 线宽与透明度都取分发权重（0.08–1.0）：旧实现只把权重映射到 1.7–4px 且透明度一致，肉眼分不出。
 * - 有在途请求的分支沿曲线跑一个光斑，进度用 sin 包络在两端淡入淡出，避免端点处突然出现。
 * - 进度以 [State] 传入、在绘制阶段读取，因此每帧只重绘，不重组拓扑卡。
 */
@Composable
private fun BoxScope.TopologyWiresCanvas(
    container: Rect?,
    hub: Rect?,
    rows: Map<String, Rect>,
    branches: Map<String, TopologyProviderBranch>,
    other: Rect?,
    isRunning: Boolean,
    gold: Color,
    goldEnd: Color,
    phase: State<Float>,
) {
    val box = container ?: return
    val hubBox = hub ?: return
    val idleColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.matchParentSize()) {
        val startX = hubBox.right - box.left - 2.dp.toPx()
        val startY = hubBox.center.y - box.top
        val progress = phase.value

        val targets = ArrayList<Triple<Rect, Float, Boolean>>()
        rows.forEach { (pid, rect) ->
            val branch = branches[pid] ?: return@forEach
            targets.add(Triple(rect, branch.weight, branch.hasActiveRequest && isRunning))
        }
        other?.let { targets.add(Triple(it, 0.08f, false)) }

        targets.forEach { (rect, weight, active) ->
            val endX = rect.left - box.left
            val endY = rect.center.y - box.top
            val dx = endX - startX
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(startX + dx * 0.45f, startY, endX - dx * 0.45f, endY, endX, endY)
            }
            val stroke = (1.1f + weight * 2.4f).dp.toPx()
            drawPath(
                path = path,
                color = (if (active) gold else idleColor).copy(alpha = 0.5f + weight * 0.5f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (active) {
                val measure = PathMeasure().apply { setPath(path, false) }
                if (measure.length > 0f) {
                    val envelope = kotlin.math.sin(progress * kotlin.math.PI).toFloat()
                    val center = measure.getPosition(progress * measure.length)
                    drawCircle(
                        color = goldEnd.copy(alpha = 0.28f * envelope),
                        radius = stroke + 4.dp.toPx(),
                        center = center,
                    )
                    drawCircle(
                        color = goldEnd.copy(alpha = envelope),
                        radius = stroke / 2f + 2.dp.toPx(),
                        center = center,
                    )
                }
            }
        }
    }
}

/** 网关中枢节点：运行中时金色描边 + 微发光晕，停止时置灰。 */
@Composable
private fun GatewayHubNode(
    isRunning: Boolean,
    port: Int,
    gold: Color,
    goldEnd: Color,
    hasActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .drawBehind {
                if (isRunning) {
                    val haloAlpha = if (hasActive) 0.22f else 0.10f
                    drawRoundRect(
                        color = gold.copy(alpha = haloAlpha),
                        size = size,
                        cornerRadius = CornerRadius(10.dp.toPx()),
                    )
                }
            }
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (hasActive) 1.5.dp else 1.dp,
                color = if (isRunning) gold else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "AI-GATEWAY",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = if (isRunning) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = ":$port",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                color = if (isRunning) goldEnd else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------- 遥测指标网格

/** 2×3 遥测指标网格。 */
@Composable
internal fun HomeMetricsWidget(state: AppUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProvidersTile(state, Modifier.weight(1f))
            ReadinessTile(state, Modifier.weight(1f))
            ModelsTile(state, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RequestsTile(state, Modifier.weight(1f))
            TodayTokensTile(state, Modifier.weight(1f))
            CreditsTile(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .height(112.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        content()
    }
}

/** 大号细体数字 + 小号后缀。 */
@Composable
private fun BigNumber(value: String, suffix: String? = null, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** 指标卡底部灰色小备注。 */
@Composable
private fun TileNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ProvidersTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "供应商") {
        BigNumber(state.providers.size.toString())
        TileNote(
            if (state.providers.isEmpty()) "暂无供应商" else state.providers.joinToString(" · ") { it.displayName },
        )
    }
}

@Composable
private fun ReadinessTile(state: AppUiState, modifier: Modifier) {
    val total = state.pool.total
    val usable = state.pool.usable
    val percent = if (total > 0) (usable.toFloat() / total * 100).toInt() else 0
    Tile(modifier, "账号就绪") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BigNumber(usable.toString(), suffix = "/ $total")
                TileNote("池内可用账号")
            }
            ReadinessRing(percent, if (total > 0) usable.toFloat() / total else 0f)
        }
    }
}

/** 环形进度：金色弧 + 灰底环，中间百分比。 */
@Composable
private fun ReadinessRing(percent: Int, fraction: Float) {
    val ringColor = LocalAppColors.current.goldStart
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val strokeWidth = 4.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(strokeWidth),
            )
            if (fraction > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelsTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "聚合模型") {
        BigNumber(state.models.size.toString())
        TileNote(
            when {
                state.modelsError.isNotEmpty() -> state.modelsError
                state.models.isEmpty() -> "尚未拉取模型目录"
                else -> "各供应商模型目录合计"
            },
        )
    }
}

@Composable
private fun RequestsTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "今日请求") {
        BigNumber(formatCount(state.today.requests))
        // 增长需要真实昨日数据：daily 末尾是今天，倒数第二个才是昨天。
        val yesterday = if (state.daily.size >= 2) state.daily[state.daily.size - 2] else null
        if (yesterday != null && yesterday.requests > 0) {
            val delta = (state.today.requests - yesterday.requests).toDouble() / yesterday.requests * 100
            Text(
                text = (if (delta >= 0) "↑ " else "↓ ") + String.format(Locale.US, "%.1f%%", kotlin.math.abs(delta)) + " 较昨日",
                style = MaterialTheme.typography.labelSmall,
                color = if (delta >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TodayTokensTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "今日 tokens") {
        BigNumber(formatTokens(state.today.totalTokens))
        TileNote("输入 ${formatTokens(state.today.promptTokens)} · 输出 ${formatTokens(state.today.completionTokens)}")
    }
}

@Composable
private fun CreditsTile(state: AppUiState, modifier: Modifier) {
    val known = state.pool.creditsKnown > 0
    val goldText = LocalAppColors.current.goldText
    Tile(modifier, "已知额度") {
        BigNumber(
            value = if (known) formatCount(state.pool.totalCredits) else "—",
            color = if (known) goldText else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TileNote(if (known) "已同步 ${state.pool.creditsKnown} 个账号" else "尚未同步额度")
    }
}

// ---------------------------------------------------------------------- 消耗趋势

/** 消耗趋势卡：近 14 天输入/输出 tokens 折线。 */
@Composable
internal fun HomeTrendWidget(state: AppUiState) {
    val trend = remember(state.daily) { state.daily.takeLast(HOME_TREND_DAYS) }
    val drawable = remember(trend) {
        trend.size >= 2 && trend.any { it.promptTokens > 0 || it.completionTokens > 0 }
    }
    val gold = LocalAppColors.current.goldStart
    SectionCard {
        SectionHeader("消耗趋势", action = if (trend.isEmpty()) null else "近 ${trend.size} 天")
        if (drawable) {
            HomeTrendChart(trend, startOfDay(System.currentTimeMillis()))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HomeLegendDot(gold, "输入")
                HomeLegendDot(DeepInk, "输出")
            }
        } else {
            Text(
                text = "暂无足够历史，累积几天后显示趋势",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun HomeLegendDot(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeTrendChart(daily: List<UsageStats>, todayStart: Long) {
    val gold = LocalAppColors.current.goldStart
    val gridColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val n = daily.size
    val maxValue = remember(daily) {
        daily.maxOf { maxOf(it.promptTokens, it.completionTokens) }.coerceAtLeast(1L)
    }
    val labels = remember(daily, todayStart) {
        List(n) { index -> homeDayLabel(todayStart - (n - 1 - index) * 86_400_000L) }
    }

    // 复用同一支画笔：绘制阶段只改对齐方式，不再每次重绘都新建
    val axisPaint = remember { android.graphics.Paint() }
    val inFractions = remember(daily, maxValue) {
        FloatArray(n) { daily[it].promptTokens.toFloat() / maxValue }
    }
    val outFractions = remember(daily, maxValue) {
        FloatArray(n) { daily[it].completionTokens.toFloat() / maxValue }
    }
    val xs = remember(n) { FloatArray(n) }
    val inY = remember(n) { FloatArray(n) }
    val outY = remember(n) { FloatArray(n) }
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val gutterLeft = 40.dp.toPx()
        val gutterBottom = 22.dp.toPx()
        val padTop = 6.dp.toPx()
        val padRight = 6.dp.toPx()
        val chartWidth = (size.width - gutterLeft - padRight).coerceAtLeast(1f)
        val chartHeight = (size.height - gutterBottom - padTop).coerceAtLeast(1f)
        val originY = padTop + chartHeight
        val stepX = chartWidth / (n - 1)
        for (i in 0 until n) {
            xs[i] = gutterLeft + i * stepX
            inY[i] = originY - inFractions[i] * chartHeight
            outY[i] = originY - outFractions[i] * chartHeight
        }

        drawLine(gridColor, Offset(gutterLeft, originY), Offset(gutterLeft + chartWidth, originY), strokeWidth = 1f)
        drawHomeSeries(xs, inY, originY, gold)
        drawHomeSeries(xs, outY, originY, DeepInk)

        drawIntoCanvas { canvas ->
            axisPaint.isAntiAlias = true
            axisPaint.color = labelColor.toArgb()
            axisPaint.textSize = 9.sp.toPx()
            axisPaint.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.nativeCanvas.drawText(formatHomeAxis(maxValue), gutterLeft - 6.dp.toPx(), originY, axisPaint)
            axisPaint.textAlign = android.graphics.Paint.Align.CENTER
            val labelY = originY + 14.dp.toPx()
            for (index in listOf(0, (n - 1) / 2, n - 1).distinct()) {
                canvas.nativeCanvas.drawText(labels[index], xs[index], labelY, axisPaint)
            }
        }
    }
}

/** 一条序列：先画半透明填充，再画主线。 */
private fun DrawScope.drawHomeSeries(xs: FloatArray, ys: FloatArray, baseline: Float, color: Color) {
    if (xs.size < 2) return
    val fillPath = Path().apply {
        moveTo(xs[0], baseline)
        for (i in xs.indices) lineTo(xs[i], ys[i])
        lineTo(xs.last(), baseline)
        close()
    }
    drawPath(fillPath, color = color.copy(alpha = 0.12f))
    val linePath = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) lineTo(xs[i], ys[i])
    }
    drawPath(
        linePath,
        color = color,
        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 坐标轴上限缩写：1.2k / 3.4M。 */
private fun formatHomeAxis(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private val HOME_DAY_LABEL_FORMAT = SimpleDateFormat("M/d", Locale.US)

private fun homeDayLabel(millis: Long): String = HOME_DAY_LABEL_FORMAT.format(Date(millis))

// ---------------------------------------------------------------------- 上游配额池

/**
 * 账号的额度包窗口（每个包一条进度条）。
 *
 * 进入首页拉一次；账号集合变化时重拉。额度中心与供应商详情页拉的是同一份数据，
 * 这里只在组件被添加到首页时才会发生。
 */
@Composable
internal fun HomeQuotaWidget(state: AppUiState, viewModel: AppViewModel) {
    val accounts = state.accountsOf("antigravity")
    if (accounts.isEmpty()) return

    LaunchedEffect(accounts.map { it.uid }) {
        accounts.forEach { viewModel.loadCreditPacks("antigravity", it.uid) }
    }

    SectionCard {
        SectionHeader("上游配额池")
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (pack in packs) {
                        QuotaPackBar(pack)
                    }
                }
            }
        }
    }
}
