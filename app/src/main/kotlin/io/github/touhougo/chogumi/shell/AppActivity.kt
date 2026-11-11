package io.github.touhougo.chogumi.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.DisplayScaleRepository
import io.github.touhougo.chogumi.core.data.repository.SmileyAssetStore
import io.github.touhougo.chogumi.core.model.DisplayScale
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.reader.LocalSmileyFiles
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.ui.theme.darkThemeOf
import io.github.touhougo.chogumi.core.ui.theme.displayScaleOverride
import io.github.touhougo.chogumi.core.ui.theme.requestRefreshRate
import io.github.touhougo.chogumi.core.ui.image.LocalCoverComputationDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.qualifier.named
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import io.github.touhougo.chogumi.core.ui.common.navigation.InboxRequests
import io.github.touhougo.chogumi.core.ui.common.navigation.InboxTab
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequests
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.inAppRoute
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AppActivity : ComponentActivity() {

    private val viewModel: ShellViewModel by viewModel()
    private val updates: AppUpdateViewModel by viewModel()
    private val authRepository: AuthRepository by inject()
    private val authConfig: AuthConfig by inject()

    /** 按需表情的下载能力；由外壳经 CompositionLocal 下发给渲染层（渲染层不该知道 DI 的存在）。 */
    private val smileyAssetStore: SmileyAssetStore by inject()
    private val progressEntryRequests: ProgressEntryRequests by inject()
    private val inboxRequests: InboxRequests by inject()
    private val computationDispatcher: CoroutineDispatcher by inject(named("computationDispatcher"))
    private val displayScale: DisplayScaleRepository by inject()

    /** 这个 Activity 创建时用的界面大小存值；存值一变就重建，新密度从重建后的第一帧起生效。 */
    private var appliedDisplayScale = DisplayScale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appliedDisplayScale = displayScale.percent.value
        newBase.displayScaleOverride(appliedDisplayScale)?.let(::applyOverrideConfiguration)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.uiState.value.initializing }
        lifecycleScope.launch {
            displayScale.percent.first { it != appliedDisplayScale }
            recreate()
        }

        // 仅冷启动处理进来的地址：Activity 重建（旋转/进程恢复）会重投同一 base intent，重放会毁 token、再推一遍页面
        if (savedInstanceState == null) {
            intent?.let(::handleIntent)
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val isDark = darkThemeOf(state.themeMode)

            // 系统栏随 App 的明暗走（用户可以不跟随系统）：状态栏透明，导航栏半透明遮罩
            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { isDark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { isDark },
                )
            }
            LaunchedEffect(state.refreshRate) { requestRefreshRate(state.refreshRate) }

            ChogumiTheme(darkTheme = isDark, themePreferences = state.themePreferences) {
                CompositionLocalProvider(
                    LocalSmileyFiles provides smileyAssetStore,
                    LocalCoverComputationDispatcher provides computationDispatcher,
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AppShell(viewModel, updates, windowSizeClass, progressEntryRequests, inboxRequests)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshNotifications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 进来的意图：点开系统通知时带着提醒 id（标为已读）或要打开的铃铛面板页（[SystemNotices.EXTRA_INBOX]：切到社区、打开面板），
     * 然后是地址（[handleLink]）。
     */
    private fun handleIntent(intent: Intent) {
        val noticeId = intent.getIntExtra(SystemNotices.EXTRA_NOTICE_ID, 0)
        if (noticeId > 0) viewModel.markNoticeRead(noticeId, intent.getStringExtra(SystemNotices.EXTRA_NOTICE_PROOF))
        InboxTab.entries.firstOrNull { it.name == intent.getStringExtra(SystemNotices.EXTRA_INBOX) }?.let { tab ->
            viewModel.openLink(Route.Community)
            inboxRequests.open(tab)
        }
        handleLink(intent.data)
    }

    /**
     * [LinkActivity] 交来的地址（桌面图标进来没有地址）。OAuth 回调 `{redirectUri}?code=…&state=…` 去换令牌，换令牌在 App 级作用域里，
     * Activity 重建不打断；Bangumi 网页链接按 [inAppRoute] 打开对应的页，应用里没有的页交给浏览器。
     */
    private fun handleLink(uri: Uri?) {
        if (uri == null) return
        if (uri.buildUpon().clearQuery().build().toString() == authConfig.redirectUri) {
            val code = uri.getQueryParameter("code") ?: return
            val state = uri.getQueryParameter("state") ?: return
            authRepository.handleCallback(code, state)
            return
        }
        val url = uri.toString()
        val route = inAppRoute(url)
        if (route != null) viewModel.openLink(route) else CustomTabUriHandler(this).openUri(url)
    }

    private companion object {
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
        const val LIGHT_SCRIM = 0xE6FFFFFF.toInt()
        const val DARK_SCRIM = 0x801B1B1B.toInt()
    }
}
