package io.github.touhougo.chogumi.shell

import androidx.activity.compose.ReportDrawn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.BuildConfig
import io.github.touhougo.chogumi.core.glass.GlassBarMinimize
import io.github.touhougo.chogumi.core.glass.GlassLayer
import io.github.touhougo.chogumi.core.glass.glassSource
import io.github.touhougo.chogumi.core.glass.glassBarScroll
import io.github.touhougo.chogumi.core.glass.rememberGlassBarMinimize
import io.github.touhougo.chogumi.core.glass.rememberGlassLayer
import io.github.touhougo.chogumi.core.model.SignedInAccount
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.LocalInAppLinkHandler
import io.github.touhougo.chogumi.core.ui.common.LocalCoverQuality
import io.github.touhougo.chogumi.core.ui.common.LocalFriends
import io.github.touhougo.chogumi.core.ui.common.LocalReadingTextSize
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequests
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberAppNavigator
import io.github.touhougo.chogumi.core.ui.common.navigation.topLevelDestinationsFor
import io.github.touhougo.chogumi.core.ui.composables.ContentLoadingIndicator
import io.github.touhougo.chogumi.core.ui.composables.LocalNow
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.theme.LocalWindowWidthSizeClass

@Composable
fun AppShell(
    mainViewModel: ShellViewModel,
    updates: AppUpdateViewModel,
    windowSizeClass: WindowSizeClass,
    progressEntryRequests: ProgressEntryRequests,
) {
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    if (state.initializing) return
    if (state.slowStart) {
        SlowStartNotice(onRetry = mainViewModel::retryInitialization)
        return
    }
    if (state.restoreFailed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            RetryError(stringResource(R.string.startup_restore_failed), onRetry = mainViewModel::retryInitialization, Modifier.fillMaxWidth())
        }
        ReportDrawn()
        return
    }
    // 登出、登录、换号时抛弃整套页面（登录成功后也不留登录页在栈中）；账号没变就不动页面。
    AccountPageScope(state.account) {
        // 导航状态只随账号作用域重建，不随体验模式重建：栈按全部顶级目的地建立（见 TopLevelDestination.routes），
        // 模式只影响显示哪几个 tab——模式开关就在设置页上，重建会把设置页自己从栈里抹掉。
        val navigator = rememberAppNavigator()
        // 页面面板是模态的，开着时盖住底栏：手机底栏让位（M3 modal bottom sheet；面板画在页面层里）。
        // 平板导航轨常驻、不让位（见 TopLevelNavigationRail），面板只盖页面区。
        val sheets = remember { PageSheetRegistry() }
        val showBottomBar by remember { derivedStateOf { navigator.atTabRoot && !sheets.anyOpen } }

        // 体验模式（完整 / 专注）→ 显示哪几个 tab：全应用唯一的派生与注入点。
        val destinations = remember(state.features) { topLevelDestinationsFor(state.features) }

        // 切到专注模式时用户可能正停在被隐藏的 tab（社区）上：回到首页。
        LaunchedEffect(destinations) {
            if (destinations.none { it.route == navigator.currentTab }) {
                navigator.navigate(Route.Home)
            }
        }

        // 从别的应用点进来的链接：导航（随账号作用域）建好之后才收，换号后由新的导航接着收。
        LaunchedEffect(navigator) {
            mainViewModel.linkRoutes.collect(navigator::navigate)
        }

        val context = LocalContext.current
        val now = mainViewModel.now.collectAsStateWithLifecycle()
        val friends by mainViewModel.friends.collectAsStateWithLifecycle()
        CompositionLocalProvider(
            LocalNow provides now,
            LocalInAppLinkHandler provides navigator::navigate,
            LocalUriHandler provides remember(context) { CustomTabUriHandler(context) },
            LocalWindowWidthSizeClass provides windowSizeClass.widthSizeClass,
            // 渲染层唯一的可见性取值来源（见 LocalAppFeatures）。
            LocalAppFeatures provides state.features,
            LocalReadingTextSize provides state.readingTextSize,
            LocalCoverQuality provides state.coverQuality,
            LocalFriends provides friends,
        ) {
            // 设置里的「玻璃效果」开着时底栏是玻璃胶囊（GlassNavigationBar.kt），页面区录进这一层供它采样；关着时是 null
            val glass = rememberGlassLayer()
            // 往下读时玻璃底栏收成左下角一颗圆钮：页面区的滚动交给它（玻璃关着时不接）
            val minimize = rememberGlassBarMinimize()
            ShellLayout(
                navigation = topLevelNavigationFor(windowSizeClass.widthSizeClass),
                glass = glass,
                minimize = minimize.takeIf { glass != null },
                bottomBar = {
                    if (glass != null) {
                        GlassNavigationBar(
                            glass = glass,
                            minimize = minimize,
                            currentTab = navigator.currentTab,
                            destinations = destinations,
                            isVisible = showBottomBar,
                            onSelect = navigator::selectTab,
                            unreadCount = state.unreadCount,
                        )
                    } else {
                        TopLevelNavigationBar(
                            currentTab = navigator.currentTab,
                            destinations = destinations,
                            isVisible = showBottomBar,
                            onSelect = navigator::selectTab,
                            unreadCount = state.unreadCount,
                        )
                    }
                },
                rail = {
                    TopLevelNavigationRail(
                        currentTab = navigator.currentTab,
                        destinations = destinations,
                        onSelect = navigator::selectTab,
                        unreadCount = state.unreadCount,
                    )
                },
            ) { bottomPadding ->
                AppEntries(navigator, bottomPadding, state.account, sheets, progressEntryRequests)
            }
            AppUpdatePromptHost(updates)
            ReportDrawn()
        }
    }
}

/** 更新提示框：画在页面之上，App 进来之后任何页面上都可能弹出（自动检查到点、设置里「立即检查」）。 */
@Composable
private fun AppUpdatePromptHost(viewModel: AppUpdateViewModel) {
    // 跟着生命周期收：下载在后台下好时，回到前台才交给系统安装器（后台不能开别的应用的页面）
    val prompt by viewModel.uiState.collectAsStateWithLifecycle()
    AppUpdateDialog(prompt, BuildConfig.VERSION_NAME, viewModel)
}

/**
 * 页面的账号作用域，身份只看 [account] 的 userId（游客为 null）：它一变（登出、登录、直接换成另一个号），整套导航、
 * 保存状态与页面 ViewModel 一起丢掉；userId 不变时用户名、未读数、体验模式怎么变都保留页面。
 */
@Composable
internal fun AccountPageScope(account: SignedInAccount?, content: @Composable () -> Unit) {
    key(account?.userId) { content() }
}

/**
 * 慢启动提示：本地会话与偏好超过 [SLOW_START_MILLIS] 还没恢复完。不是错误——读完自动进入 App；
 * 读取真卡住时可以重试。这时还没有任何页面，没有可画的骨架，所以用加载指示器（首屏骨架契约针对的是页面首屏）。
 */
@Composable
private fun SlowStartNotice(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContentLoadingIndicator()
        Text(
            text = stringResource(R.string.slow_start_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.retry))
        }
    }
    ReportDrawn()
}

/**
 * 外壳布局：主导航（底栏或导航轨）加页面区。页面区 [content] 不论哪种导航形态都在组合里的**同一个位置**：
 * 页面里的可保存状态（页内标签、开着的面板）按组合位置存取，宽度跨过 compact 时 Activity 重建，
 * 页面区若随导航形态换了位置，存下的状态就找不回来，页面全部回到初始状态。所以两种形态共用一个 Scaffold，
 * 只在它前面加不加导航轨、底栏槽里放不放底栏。玻璃效果开着时页面区录进 [glass]，底栏槽里的玻璃底栏从它采样；页面区的滚动交给
 * [minimize]，决定玻璃底栏收不收成圆钮。
 */
@Composable
internal fun ShellLayout(
    navigation: TopLevelNavigationKind,
    glass: GlassLayer?,
    minimize: GlassBarMinimize?,
    bottomBar: @Composable () -> Unit,
    rail: @Composable () -> Unit,
    content: @Composable (bottomPadding: Dp) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // 导航轨常驻：页面区宽度在任何页面都不变，进出详情页不会先少一块、后补一块。
        if (navigation == TopLevelNavigationKind.RAIL) rail()
        Scaffold(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
            bottomBar = { if (navigation == TopLevelNavigationKind.BOTTOM_BAR) bottomBar() },
        ) { padding ->
            // 只传一次底栏占位，子页不重复补系统导航 inset；导航轨形态没有底栏，占位为 0。
            // 玻璃底栏从页面区采样（[glass] 为 null 时这层什么也不做）；这个 Box 两种情况下都在，页面区的组合位置不随开关变
            Box(Modifier.glassSource(glass).glassBarScroll(minimize)) { content(padding.calculateBottomPadding()) }
        }
    }
}

/** 主导航的形态。 */
internal enum class TopLevelNavigationKind { BOTTOM_BAR, RAIL }

/**
 * 窗口宽度 → 主导航形态，**唯一判定点**。From M3（navigation.md）：compact 窗口用导航栏，
 * medium 及以上用导航轨（「Compact windows should always use a navigation bar」；导航轨「for medium window size classes and larger」）。
 */
internal fun topLevelNavigationFor(width: WindowWidthSizeClass): TopLevelNavigationKind =
    if (width == WindowWidthSizeClass.Compact) TopLevelNavigationKind.BOTTOM_BAR else TopLevelNavigationKind.RAIL
