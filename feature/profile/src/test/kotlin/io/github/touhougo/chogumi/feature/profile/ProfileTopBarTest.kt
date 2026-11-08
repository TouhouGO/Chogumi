package io.github.touhougo.chogumi.feature.profile

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 用户主页的头部从屏幕顶边画起（横幅铺到状态栏下面）。往上滚时，滚上去的内容必须被顶栏盖住：
 * 状态栏那一截是顶栏的填色（页面底色 `surface`），而不是资料、分段按钮这些内容和时间、电量叠在一起。
 * 头部收满后顶栏让位给分段（往下读时顶上只有分段一层），往回一拉顶栏和设置按钮就回来。
 *
 * 宿主测试没有系统栏，状态栏高度靠给页面派发一份 insets 得到；先断言页面确实读到了这个高度，后面的断言才是在测状态栏。
 */
@RunWith(AndroidJUnit4::class)
// 矮视口让「概览」一屏放不下、能往上滚；尺寸是测试设计决定（not in M3）。
@Config(sdk = [34], qualifiers = "w360dp-h400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileTopBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View
    private var statusBarPx = 0
    private var fillColor = Color.Unspecified

    @Test
    fun `他人主页网页链接用用户名_key为空时用数字id`() {
        assertEquals("https://bgm.tv/user/fixture_user", profilePageUrlOf(UserBo(id = 9, username = "fixture_user", nickname = "Fixture User")))
        assertEquals("https://bgm.tv/user/9", profilePageUrlOf(UserBo(id = 9, username = " ", nickname = "#9")))
    }

    @Test
    fun `往上滚时顶栏让位给分段且状态栏一截仍由顶栏填色盖住，往回一拉设置按钮回到顶栏`() {
        composeRule.setContent {
            view = LocalView.current
            ChogumiTheme {
                fillColor = MaterialTheme.colorScheme.surface
                statusBarPx = WindowInsets.statusBars.getTop(LocalDensity.current)
                OwnProfile()
            }
        }
        composeRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        composeRule.waitForIdle()
        assertEquals("页面读到的状态栏高度", STATUS_BAR_PX, statusBarPx)

        repeat(3) { composeRule.onRoot().performTouchInput { swipeUp() } }
        composeRule.waitForIdle()
        // 内容确实往上滚了：整页网格离开了顶部。
        val scrolled = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .onFirst().fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("整页网格应已往上滚，滚动量 $scrolled", scrolled > 0f)

        val statusBarBottom = with(composeRule.density) { STATUS_BAR_PX.toDp() }
        val barBottom = statusBarBottom + TopAppBarDefaults.TopAppBarExpandedHeight
        val settings = composeRule.onNodeWithContentDescription(view.context.getString(R.string.settings))

        // 顶栏让位：设置按钮跟着收走，分段顶到原来顶栏占的位置里（往下读时顶上只有分段一层）。
        settings.assertIsNotDisplayed()
        // 分段按钮的文字放不下时会省掉，标签始终挂在按钮的 contentDescription 上
        val tabTop = composeRule.onAllNodes(hasContentDescription(view.context.getString(R.string.profile_overview)))
            .onFirst().getBoundsInRoot().top
        assertTrue("分段应顶到顶栏原来的下沿 $barBottom 之上，实际 $tabTop", tabTop < barBottom)
        assertStatusBarFilled()

        // 往回慢慢拉一小段（不甩动）：顶栏先回来，设置按钮完整显示在状态栏之下、顶栏下沿之上。
        composeRule.onRoot().performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX, centerY + 150.dp.toPx()), durationMillis = 1000)
        }
        composeRule.waitForIdle()
        settings.assertIsDisplayed()
        val bounds = settings.getBoundsInRoot()
        assertTrue(
            "设置按钮应在顶栏内（$statusBarBottom 到 $barBottom），实际 $bounds",
            bounds.top >= statusBarBottom && bounds.bottom <= barBottom,
        )
        assertStatusBarFilled()
    }

    /** 状态栏那一截整条是顶栏的填色。 */
    private fun assertStatusBarFilled() {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val y = STATUS_BAR_PX / 2
        listOf(0.05f, 0.5f, 0.95f).forEach { fx ->
            val x = (pixels.width * fx).toInt()
            val actual = pixels[x, y]
            assertTrue("状态栏 ($x, $y) 应是顶栏填色 $fillColor，实际 $actual", actual.closeTo(fillColor))
        }
    }

    /** 看自己的主页，资料已到、停在「概览」。 */
    @Composable
    private fun OwnProfile() {
        val loginGate = rememberLoginGate(isLoggedIn = true, onLogin = {})
        ProfileContent(
            user = UserBo(
                id = 1,
                username = "tester",
                nickname = "测试用户",
                sign = "签名",
                bio = (1..30).joinToString("\n") { "简介第 $it 行" },
            ),
            isProfileRefreshing = false,
            error = null,
            timeline = PagedList<TimelineItemBo>(isLoading = false),
            onRefreshTimeline = {},
            onLoadMoreTimeline = {},
            onOpenTimeline = {},
            onDeleteTimeline = {},
            shelf = ShelfState(),
            social = SocialState(),
            onOpenShelf = {},
            onTimelineShown = {},
            onSelectShelf = {},
            onRefreshShelf = {},
            onLoadMoreShelf = {},
            onOpenSocial = {},
            onSelectSocial = {},
            onLoadMoreSocial = {},
            onRefreshSocial = {},
            career = CareerState(isSelf = true),
            onRetryCareer = {},
            onUserClick = {},
            onSubjectClick = {},
            onCharacterClick = {},
            onPersonClick = {},
            onRetry = {},
            onRefreshProfile = {},
            isSelf = true,
            isLoggedIn = true,
            loginGate = loginGate,
            socialOps = SocialOpsState(),
            snackbarOnSheet = false,
            snackbarHostState = remember { SnackbarHostState() },
            onAddFriend = {},
            onRemoveFriend = {},
            onBlock = {},
            onUnblock = {},
            onOpenReport = {},
            onMessage = {},
            onSettingsClick = {},
            onBack = null,
            onOpenBlogs = {},
            onOpenIndexes = {},
            onOpenCareer = {},
        )
    }

    private companion object {
        const val STATUS_BAR_PX = 48
    }
}

/** 逐通道比较（截图经过一次 8 位量化，容差 2/255）。 */
private fun Color.closeTo(other: Color): Boolean =
    abs(red - other.red) <= 2f / 255 && abs(green - other.green) <= 2f / 255 &&
        abs(blue - other.blue) <= 2f / 255 && abs(alpha - other.alpha) <= 2f / 255
