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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.theme.GradientBrush
import dev.aigw.app.ui.theme.HeroBrush
import dev.aigw.core.pool.AccountStatus

/** 账号状态文案：只有凭证失效与手动停用两种异常态，正常即「可用」。 */
fun accountStatusText(account: AccountStatus): String = when {
    account.disabled -> "凭证失效，需重新登录"
    !account.enabled -> "已停用"
    else -> "可用"
}

/** 页头：居中标题 + 副标题，右侧放状态胶囊与页内动作。 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 76.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    modifier = if (onTitleClick != null) {
                        Modifier.clip(CircleShape).clickable(onClick = onTitleClick).padding(horizontal = 12.dp, vertical = 2.dp)
                    } else {
                        Modifier
                    },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke()
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(title, subtitle, leading, actions, onTitleClick)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/**
 * 白底大圆角卡片，带微量紫色氛围阴影。
 *
 * [enterIndex] 非空时卡片带错峰入场动效（第 n 张延迟 40ms·n），用于列表/表单页的首屏。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    enterIndex: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = MaterialTheme.shapes.large,
                ambientColor = Color(0x127C3AED),
                spotColor = Color(0x1A7C3AED),
            )
            .then(if (enterIndex != null) Modifier.staggeredAppear(enterIndex) else Modifier)
            .then(modifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * 首页顶部渐变横幅大卡（Hero）：紫粉渐变背景，白色内容，整合服务状态、接入地址与操作按钮。
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .shadow(
                elevation = 12.dp,
                shape = MaterialTheme.shapes.extraLarge,
                ambientColor = Color(0x507C3AED),
                spotColor = Color(0x507C3AED),
            )
            .clip(MaterialTheme.shapes.extraLarge)
            .background(HeroBrush),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** 「运行中 / 已停止」状态胶囊。 */
@Composable
fun StatusChip(running: Boolean) {
    val container by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(200),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "chipContent",
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) MaterialTheme.colorScheme.tertiary else content))
        Text(
            text = if (running) "运行中" else "已停止",
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

/** Hero 卡内专用的白色「运行中 / 已停止」状态胶囊。 */
@Composable
fun HeroStatusChip(running: Boolean) {
    val bgAlpha = if (running) 0.25f else 0.15f
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = bgAlpha))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (running) Color(0xFF6EE7B7) else Color.White.copy(0.6f)),
        )
        Text(
            text = if (running) "运行中" else "已停止",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

@Composable
fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = true,
    indicatorColor: Color = Color.Unspecified,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (indicatorColor != Color.Unspecified) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 分段/筛选胶囊。 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(200),
        label = "choiceContainer",
    )
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .pressScale()
            .clip(CircleShape)
            .background(container)
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
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onCopy)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** Hero 卡内专用的白色半透明地址行（点即复制）。 */
@Composable
fun HeroAddressTile(label: String, value: String, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onCopy)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
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

/** 全宽描边按钮（「数据管理」的清理动作）。onClick 放最后以支持尾随 lambda。 */
@Composable
fun OutlineActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

/** 带图标的功能行（「我的」页的功能列表）。 */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().pressScale().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun EmptyHint(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/**
 * 额度包进度条：一行「名称 + 剩余/上限」，下方细进度条。
 * 用权重绘制而不是 LinearProgressIndicator，保证在所有背景下颜色可控。
 */
@Composable
fun QuotaPackBar(pack: dev.aigw.core.provider.QuotaPack) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = if (pack.group.isEmpty()) pack.name else "${pack.name}（${pack.group}）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${pack.remain}/${pack.limit}"
                    + if (pack.expireAt > 0) " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(pack.expireAt * 1000)) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val fraction = if (pack.limit > 0) {
                (pack.remain.toFloat() / pack.limit).coerceIn(0f, 1f)
            } else {
                0f
            }
            val barFraction by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(Motion.BAR_FILL_MILLIS, easing = FastOutSlowInEasing),
                label = "quotaBar",
            )
            Box(
                Modifier
                    .fillMaxWidth(barFraction)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            fraction > 0.5f -> MaterialTheme.colorScheme.primary
                            fraction > 0.2f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                    ),
            )
        }
    }
}
