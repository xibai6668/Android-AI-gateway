package dev.aigw.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aigw.app.ui.pushPopSpec
import dev.aigw.app.ui.tabContentSpec
import dev.aigw.app.ui.AppUiState
import dev.aigw.app.ui.ApiSettingsScreen
import dev.aigw.app.ui.AppViewModel
import dev.aigw.app.ui.CustomProviderScreen
import dev.aigw.app.ui.CreditCenterScreen
import dev.aigw.app.ui.DataManagementScreen
import dev.aigw.app.ui.HomeScreen
import dev.aigw.app.ui.KeepAliveScreen
import dev.aigw.app.ui.LogsScreen
import dev.aigw.app.ui.MeScreen
import dev.aigw.app.ui.ModelsScreen
import dev.aigw.app.ui.ProviderDetailScreen
import dev.aigw.app.ui.ProviderListScreen
import dev.aigw.app.ui.ProviderCreditsScreen
import dev.aigw.app.ui.ProxyScreen
import dev.aigw.app.ui.SubPage
import dev.aigw.app.ui.TaskCenterScreen
import dev.aigw.app.ui.UsageScreen
import dev.aigw.app.ui.providers.ProviderUiRegistry
import dev.aigw.app.ui.theme.AiGatewayTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: AppViewModel = viewModel(factory = appViewModelFactory())
            val state by viewModel.state.collectAsStateWithLifecycle()
            AiGatewayTheme(dynamicColor = state.dynamicColor) {
                AppRoot(viewModel, state)
            }
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
    Providers("供应商", Icons.Filled.Person),
    Models("模型", Icons.Filled.Storage),
    Logs("记录", Icons.Filled.History),
    Me("我的", Icons.Filled.Settings),
}

@Composable
private fun AppRoot(viewModel: AppViewModel, state: AppUiState) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
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
            is SubPage.CustomProviderEdit ->
                page.key?.let { SubPage.ProviderDetail(ProviderUiRegistry.CUSTOM_PREFIX + it) }
            else -> null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = subPage == null && tab == item,
                        onClick = {
                            subPage = null
                            tab = item
                            if (item == Tab.Models) viewModel.refreshModels()
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(bottom = insets.calculateBottomPadding())) {
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
                    onSave = {
                        viewModel.saveCustomProvider(it)
                        subPage = SubPage.ProviderDetail(it.providerId)
                    },
                    onDelete = {
                        viewModel.removeCustomProvider(it)
                        subPage = null
                    },
                    onBack = {
                        subPage = page.key?.let { SubPage.ProviderDetail(ProviderUiRegistry.CUSTOM_PREFIX + it) }
                    },
                )

                SubPage.Usage -> UsageScreen(state, viewModel) { subPage = null }
                SubPage.TaskCenter -> TaskCenterScreen(state, viewModel) { subPage = null }
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
                SubPage.DataManagement -> DataManagementScreen(state, viewModel) { subPage = null }
                null -> MainTabs(
                    viewModel = viewModel,
                    state = state,
                    tab = tab,
                    onTab = { newTab ->
                        subPage = null
                        tab = newTab
                        if (newTab == Tab.Models) viewModel.refreshModels()
                    },
                    onSubPage = { subPage = it },
                )
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
                openProviders = { onTab(Tab.Providers) },
                openTaskCenter = { onSubPage(SubPage.TaskCenter) },
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
