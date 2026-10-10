package dev.aigw.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 全局动效参数：一处调整全局生效。 */
object Motion {
    const val PUSH_MILLIS = 320
    const val TAB_MILLIS = 200
    const val ENTER_MILLIS = 420
    const val STAGGER_MILLIS = 40L
    const val BAR_FILL_MILLIS = 600
}

/** 二级页推拉：进入从右滑入，返回向右滑出（方向由目标是否为 null 决定）。 */
fun AnimatedContentTransitionScope<*>.pushPopSpec(): ContentTransform {
    val pushing = targetState != null
    val enter = slideInHorizontally(
        animationSpec = tween(Motion.PUSH_MILLIS, easing = FastOutSlowInEasing),
    ) { if (pushing) it else -it / 4 } + fadeIn(tween(Motion.PUSH_MILLIS))
    val exit = slideOutHorizontally(
        animationSpec = tween(Motion.PUSH_MILLIS, easing = FastOutSlowInEasing),
    ) { if (pushing) -it / 4 else it } + fadeOut(tween(Motion.PUSH_MILLIS))
    return (enter togetherWith exit)
}

/** 主 Tab 渐进渐远：按索引方向做小幅水平位移 + 淡入淡出。 */
fun <T> AnimatedContentTransitionScope<T>.tabContentSpec(
    index: (T) -> Int,
): ContentTransform {
    val forward = index(targetState) > index(initialState)
    val enter = slideInHorizontally(tween(Motion.TAB_MILLIS)) {
        (if (forward) 1 else -1) * 48
    } + fadeIn(tween(Motion.TAB_MILLIS))
    val exit = slideOutHorizontally(tween(Motion.TAB_MILLIS)) {
        (if (forward) -1 else 1) * 48
    } + fadeOut(tween(Motion.TAB_MILLIS))
    return (enter togetherWith exit)
}

/**
 * 错峰入场：首次组合时淡入 + 上浮，[index] 越大延迟越久（每张 40ms）。
 * 只在第一次出现时播一次，之后重组不重播。
 */
@Composable
fun Modifier.staggeredAppear(index: Int): Modifier {
    var shown by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(Motion.ENTER_MILLIS, delayMillis = (index * Motion.STAGGER_MILLIS).toInt(), easing = FastOutSlowInEasing),
        label = "stagger$index",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 36f
    }
}

/** 按压缩放：按住缩到 0.97，松手 spring 回弹。 */
@Composable
fun Modifier.pressScale(): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 800f),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }
}

/**
 * Q 弹按压动效：按下缩放到 0.85，松手触发 onClick 并以低阻尼弹性超调回弹。
 */
@Composable
fun Modifier.bouncyClick(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = 0.36f, // 超弹阻尼，产生明显的 Q 弹物理振荡感
            stiffness = 450f,
        ),
        label = "bouncyScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            val up = waitForUpOrCancellation()
            pressed = false
            if (up != null) {
                onClick()
            }
        }
    }
}

/** 流光带的跨度（px），与渐变轴向一致。 */
private const val SHIMMER_BAND = 300f

/** 供骨架屏共享的流光进度：一张卡建一个即可，进度由各 [shimmer] 在绘制阶段读取。 */
@Composable
fun rememberShimmerProgress(): State<Float> =
    rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -SHIMMER_BAND,
        targetValue = SHIMMER_BAND * 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )

/**
 * 优雅流光微光动效（Shimmer），用于骨架屏占位。
 *
 * [progress] 由调用方共享（见 [rememberShimmerProgress]），这里只在绘制阶段读取它：
 * 动画每帧仅触发重绘，不再逐帧重组，也不再每帧新建渐变。
 */
@Composable
fun Modifier.shimmer(
    progress: State<Float>,
    shape: Shape = RoundedCornerShape(4.dp),
    baseColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    highlightColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
): Modifier {
    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset.Zero,
        end = Offset(SHIMMER_BAND, SHIMMER_BAND),
    )
    return this.clip(shape).drawBehind {
        translate(progress.value, progress.value) { drawRect(brush) }
    }
}
