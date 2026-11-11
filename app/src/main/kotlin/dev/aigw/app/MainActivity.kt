package dev.aigw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aigw.app.service.GatewayTileService
import dev.aigw.app.ui.pushPopSpec
import dev.aigw.app.ui.tabContentSpec
import dev.aigw.app.ui.LocalBottomContentPadding
import dev.aigw.app.ui.AppUiState
import dev.aigw.app.ui.ApiSettingsScreen
import dev.aigw.app.ui.AppViewModel
import dev.aigw.app.ui.AppearanceThemeScreen
import dev.aigw.app.ui.ThemeSettingsScreen
import dev.aigw.app.ui.CustomProviderScreen
import dev.aigw.app.ui.CreditCenterScreen
import dev.aigw.app.ui.HomeScreen
import dev.aigw.app.ui.KeepAliveScreen
import dev.aigw.app.ui.LogsScreen
import dev.aigw.app.ui.MeScreen
import dev.aigw.app.ui.ModelsScreen
import dev.aigw.app.ui.ProviderDetailScreen
import dev.aigw.app.ui.ProviderListScreen
import dev.aigw.app.ui.ProviderCreditsScreen
import dev.aigw.app.ui.ProxyScreen
import dev.aigw.app.ui.ResilienceScreen
import dev.aigw.app.ui.SecurityProtectionScreen
import dev.aigw.app.ui.SubPage
import dev.aigw.app.ui.AccountTransferScreen
import dev.aigw.app.ui.ClientKeysScreen
import dev.aigw.app.ui.ModelPricingScreen
import dev.aigw.app.ui.RequestRewriteScreen
import dev.aigw.app.ui.RoutingGroupsScreen
import dev.aigw.app.ui.TaskCenterScreen
import dev.aigw.app.ui.UsageScreen
import dev.aigw.app.ui.formatBytes
import dev.aigw.app.ui.formatSpeed
import dev.aigw.app.ui.UpdateDownload
import dev.aigw.app.ui.providers.ProviderUiRegistry
import dev.aigw.app.ui.theme.AiGatewayTheme
import dev.aigw.app.ui.theme.AppColors
import dev.aigw.app.ui.theme.LocalAppColors

class MainActivity : ComponentActivity() {

    private var viewModel: AppViewModel? = null

    private val gatewayToggledReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 磁贴启停后通知界面刷新（磁贴无法直接触达 ViewModel）
            viewModel?.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        registerGatewayToggledReceiver()
        setContent {
            val vm: AppViewModel = viewModel(factory = appViewModelFactory())
            viewModel = vm
            val state by vm.state.collectAsStateWithLifecycle()
            AiGatewayTheme(
                dynamicColor = state.dynamicColor,
                themeMode = state.themeMode,
                appColors = AppColors(state.goldStart, state.goldEnd),
                backgroundOverride = state.backgroundOverride,
            ) {
                AppRoot(vm, state)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(gatewayToggledReceiver) }
        viewModel = null
        super.onDestroy()
    }

    private fun registerGatewayToggledReceiver() {
        val filter = IntentFilter(GatewayTileService.ACTION_GATEWAY_TOGGLED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gatewayToggledReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gatewayToggledReceiver, filter)
        }
    }

    private fun appViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(application) as T
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Providers("供应", Icons.Filled.Person),
    Models("模型", Icons.Filled.Storage),
    Logs("记录", Icons.Filled.History),
    Me("我的", Icons.Filled.Settings),
}

@Composable
private fun AppRoot(viewModel: AppViewModel, state: AppUiState) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    // 玻璃采样状态：内容层写入，底栏读取
    val hazeState = remember { HazeState() }
    LaunchedEffect(state.notice) {
        if (state.notice.isNotEmpty()) {
            snackbarHostState.showSnackbar(state.notice)
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(state.busy) {
        if (state.busy.isNotEmpty()) snackbarHostState.showSnackbar(state.busy)
    }

    // 系统返回与页内返回按钮同层级：二级页回它的上级，其余回主 tab
    BackHandler(enabled = subPage != null) {
        subPage = when (val page = subPage) {
            is SubPage.ProviderCredits -> SubPage.CreditCenter
            SubPage.AppearanceTheme -> SubPage.ThemeSettings
            is SubPage.CustomProviderEdit ->
                page.key?.let { SubPage.ProviderDetail(ProviderUiRegistry.CUSTOM_PREFIX + it) }
            else -> null
        }
    }

    CompositionLocalProvider(LocalBottomContentPadding provides if (state.floatingNavBar) 78.dp else 0.dp) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 悬浮底栏时让内容铺到屏幕底部，由导航栏自己层叠在上面；
        // 同时给内容区留出底栏高度，避免最后一屏内容被玻璃盖住。
        contentWindowInsets = if (state.floatingNavBar) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (state.floatingNavBar) {
                FloatingNavBar(tab, subPage, hazeState, onSelect = { item ->
                    subPage = null
                    tab = item
                    if (item == Tab.Models) viewModel.ensureModelsLoaded()
                })
            } else {
                DockedNavBar(tab, subPage, onSelect = { item ->
                    subPage = null
                    tab = item
                    if (item == Tab.Models) viewModel.ensureModelsLoaded()
                })
            }
        },
    ) { insets ->
        Box(
            Modifier
                .fillMaxSize()
                // 悬浮底栏时内容**铺满全屏**，不因 insets 向上抬：
                // 否则底栏背后是一片空白背景，玻璃无像素可采样，看起来就是完全没模糊。
                .then(
                    if (state.floatingNavBar) {
                        Modifier.hazeSource(hazeState)
                    } else {
                        Modifier.padding(bottom = insets.calculateBottomPadding())
                    },
                ),
        ) {
            // 二级页推拉过渡：推入从右滑入，返回向右滑出
            AnimatedContent(
                targetState = subPage,
                transitionSpec = { pushPopSpec() },
                label = "subPage",
            ) { page ->
                when (page) {
                is SubPage.ProviderDetail -> ProviderDetailScreen(
                    providerId = page.providerId,
                    state = state,
                    viewModel = viewModel,
                    onBack = { subPage = null },
                    onEditCustom = { subPage = SubPage.CustomProviderEdit(it.removePrefix("custom:")) },
                )

                is SubPage.CustomProviderEdit -> CustomProviderScreen(
                    existing = state.customProviders.firstOrNull { it.key == page.key },
                    existingKeys = page.key
                        ?.let { key -> state.accountsOf("${ProviderUiRegistry.CUSTOM_PREFIX}$key") }
                        ?.map { viewModel.accountApiKey("${ProviderUiRegistry.CUSTOM_PREFIX}${page.key}", it.uid) }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
                    onSave = {
                        viewModel.saveCustomProvider(it)
                        subPage = SubPage.ProviderDetail(it.providerId)
                    },
                    onDelete = {
                        viewModel.removeCustomProvider(it)
                        subPage = null
                    },
                    onFetchModels = { baseUrl, apiKey, onResult ->
                        viewModel.fetchCustomModels(baseUrl, apiKey, onResult)
                    },
                    onBack = {
                        subPage = page.key?.let { SubPage.ProviderDetail(ProviderUiRegistry.CUSTOM_PREFIX + it) }
                    },
                )

                SubPage.Usage -> UsageScreen(state, viewModel) { subPage = null }
                SubPage.TaskCenter -> TaskCenterScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenProviders = {
                        // 空态里的「去供应商页」：切回供应商 tab，并关掉这个二级页
                        tab = Tab.Providers
                        subPage = null
                    },
                    onBack = { subPage = null },
                )
                SubPage.CreditCenter -> CreditCenterScreen(
                    state = state,
                    onOpen = { subPage = SubPage.ProviderCredits(it) },
                    onBack = { subPage = null },
                )
                is SubPage.ProviderCredits -> ProviderCreditsScreen(
                    providerId = page.providerId,
                    state = state,
                    viewModel = viewModel,
                    onBack = { subPage = SubPage.CreditCenter },
                )
                SubPage.KeepAlive -> KeepAliveScreen(state, viewModel) { subPage = null }
                SubPage.Proxy -> ProxyScreen(state, viewModel) { subPage = null }
                SubPage.ApiSettings -> ApiSettingsScreen(state, viewModel) { subPage = null }
                SubPage.SecurityProtection -> SecurityProtectionScreen(state, viewModel) { subPage = null }
                SubPage.Resilience -> ResilienceScreen(state, viewModel) { subPage = null }
                SubPage.AppearanceTheme -> AppearanceThemeScreen(state, viewModel) { subPage = SubPage.ThemeSettings }
                SubPage.ThemeSettings -> ThemeSettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenColors = { subPage = SubPage.AppearanceTheme },
                    onBack = { subPage = null },
                )
                SubPage.AccountTransfer -> AccountTransferScreen(state, viewModel) { subPage = null }
                SubPage.ClientKeys -> ClientKeysScreen(state, viewModel) { subPage = null }
                SubPage.RoutingGroups -> RoutingGroupsScreen(state, viewModel) { subPage = null }
                SubPage.ModelPricing -> ModelPricingScreen(state, viewModel) { subPage = null }
                SubPage.RequestRewrite -> RequestRewriteScreen(state, viewModel) { subPage = null }
                null -> MainTabs(
                    viewModel = viewModel,
                    state = state,
                    tab = tab,
                    onTab = { newTab ->
                        subPage = null
                        tab = newTab
                        if (newTab == Tab.Models) viewModel.ensureModelsLoaded()
                    },
                    onSubPage = { subPage = it },
                )
                }
            }
        }
    }
    }

    // 「稍后」只在本次会话内生效：关掉后仍可用「检查更新」重新拉出更新弹窗
    var updatePromptDismissed by remember(state.updateInfo?.version) { mutableStateOf(false) }
    val download = state.updateDownload
    val updateBusy = download is UpdateDownload.Probing || download is UpdateDownload.Downloading
    // 当前安装包能否自己装 APK（旧版本含 v2.13.1 缺 REQUEST_INSTALL_PACKAGES，必然装不了）
    val canSelfInstall = viewModel.canInstallUpdate()
    state.updateInfo?.takeIf { !updatePromptDismissed }?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!updateBusy) updatePromptDismissed = true },
            title = { Text("发现新版本 ${info.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!canSelfInstall) {
                        // 旧版本（含 v2.13.1）缺少 REQUEST_INSTALL_PACKAGES，应用内安装必然失败
                        Text(
                            "已更新至 v2.13.1 版本的用户需点击项目仓库下载更新，因为应用内更新有 bug",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "更新日志",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (info.notes.isEmpty()) {
                        Text(
                            "该版本没有提供更新说明。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    // 应用内更新的进度就摆在更新日志下方，不另开页面
                    when (download) {
                        is UpdateDownload.Probing -> Text(
                            "正在测速直连与国内镜像…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        is UpdateDownload.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${download.source} · ${formatBytes(download.downloaded)} / ${formatBytes(download.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            LinearProgressIndicator(
                                progress = {
                                    if (download.total <= 0) 0f
                                    else (download.downloaded.toFloat() / download.total).coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Text(
                                "${formatSpeed(download.bytesPerSecond)} · ${download.threads} 线程并发",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is UpdateDownload.Ready -> Text(
                            if (canSelfInstall) {
                                "安装包已就绪（${download.source}），点「安装」完成更新。"
                            } else {
                                "本版本无法应用内安装，请点下方「到仓库下载」手动更新。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        is UpdateDownload.Failed -> Text(
                            "下载失败：${download.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        null -> Unit
                    }
                }
            },
            confirmButton = {
                when (download) {
                    is UpdateDownload.Ready -> TextButton(onClick = {
                        // 自身包装不了时系统安装器根本不会被唤起，直接给用户可行的出路
                        if (canSelfInstall) viewModel.installUpdate() else viewModel.openReleasePage(info.url)
                    }) { Text(if (canSelfInstall) "安装" else "到仓库下载") }
                    is UpdateDownload.Failed, null -> TextButton(onClick = {
                        if (info.apkUrl == null) viewModel.openReleasePage(info.url) else viewModel.downloadUpdate(info)
                    }) { Text(if (info.apkUrl == null) "前往下载" else "应用内更新") }
                    else -> TextButton(onClick = { viewModel.cancelUpdateDownload() }) { Text("取消下载") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (updateBusy) viewModel.cancelUpdateDownload()
                    updatePromptDismissed = true
                }) { Text("稍后") }
            },
        )
    }
}

/** 贴边底栏（默认）。 */
@Composable
private fun DockedNavBar(tab: Tab, subPage: SubPage?, onSelect: (Tab) -> Unit) {
    val selectedGold = LocalAppColors.current.goldStart
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        Tab.entries.forEach { item ->
            NavigationBarItem(
                selected = subPage == null && tab == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = selectedGold,
                    selectedTextColor = selectedGold,
                    unselectedIconColor = Color(0xFF777777),
                    unselectedTextColor = Color(0xFF777777),
                ),
            )
        }
    }
}
/**
 * 悬浮底栏：iOS 26 液态玻璃胶囊。
 *
 * 形状是**胶囊**（Pill）：圆角半径 = 高度的一半，所以不写固定 dp，而是用 [CircleShape]；
 * 宽度也不铺满，而是按内容收缩后居中——iOS 的悬浮栏就是一条“分频团”，
 * 铺满整屏再加圆角矩形看起来完全不是那个东西。
 */
@Composable
private fun FloatingNavBar(tab: Tab, subPage: SubPage?, haze: HazeState, onSelect: (Tab) -> Unit) {
    val selectedGold = LocalAppColors.current.goldStart
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.30f),
                    spotColor = Color.Black.copy(alpha = 0.40f),
                )
                .clip(CircleShape)
                // 玻璃本体：模糊背景 + 极浅色调。不透明度必须低，
                // 否则实色会把模糊盖住，看上去就是一块普通半透明底。
                .hazeEffect(
                    state = haze,
                    style = HazeStyle(
                        backgroundColor = Color.Transparent,
                        tints = listOf(
                            HazeTint(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = if (isLight) 0.18f else 0.14f),
                            ),
                        ),
                        blurRadius = 40.dp,
                        noiseFactor = 0.04f,
                    ),
                )
                .drawWithContent {
                    drawContent()
                    // 胶囊内描边：玻璃边缘的一圈亮线
                    val border = 1.dp.toPx()
                    drawRoundRect(
                        color = Color.White.copy(alpha = if (isLight) 0.60f else 0.20f),
                        topLeft = Offset(border / 2f, border / 2f),
                        size = Size(size.width - border, size.height - border),
                        cornerRadius = CornerRadius(size.height / 2f),
                        style = Stroke(width = border),
                    )
                    // 顶部高光：胶囊上半部分被照亮，且只在水平方向也渐变，不糊满整条
                    val highlightHeight = size.height * 0.55f
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isLight) 0.55f else 0.18f),
                                Color.Transparent,
                            ),
                            startY = 0f,
                            endY = highlightHeight,
                        ),
                        size = Size(size.width, highlightHeight),
                    )
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { item ->
                    val selected = subPage == null && tab == item
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            // 选中项用一层极淡的亮色“胶囊内胶囊”，而不是改文字颜色
                            .background(
                                if (selected) {
                                    LocalAppColors.current.goldStart.copy(alpha = if (isLight) 0.16f else 0.20f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onSelect(item) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) selectedGold else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        // 只给选中项显示文字，未选中只留图标：这才是 iOS 胶囊栏的形态
                        if (selected) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = selectedGold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 主 Tab 页面：切换时按索引方向做小幅滑移 + 淡入淡出。 */
@Composable
private fun MainTabs(
    viewModel: AppViewModel,
    state: AppUiState,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onSubPage: (SubPage?) -> Unit,
) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = { tabContentSpec { it.ordinal } },
        label = "tab",
    ) { page ->
        when (page) {
            Tab.Home -> HomeScreen(
                state = state,
                viewModel = viewModel,
                openTaskCenter = { onSubPage(SubPage.TaskCenter) },
                openLogs = { onTab(Tab.Logs) },
                openUsage = { onSubPage(SubPage.Usage) },
            )
            Tab.Providers -> ProviderListScreen(
                state = state,
                onOpen = { onSubPage(SubPage.ProviderDetail(it)) },
                onAddCustom = { onSubPage(SubPage.CustomProviderEdit(null)) },
            )
            Tab.Models -> ModelsScreen(state, viewModel)
            Tab.Logs -> LogsScreen(state, viewModel)
            Tab.Me -> MeScreen(state, viewModel, open = { onSubPage(it) })
        }
    }
}
