package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.ui.composables.ContentGrid
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 个人页骨架预留真实内容的已知上界形态：骨架与真实内容要求几何一致，内容更短时只允许向上收。
 *
 * 做法同 core:ui 的 `SkeletonGeometryTest`：同一个窗口里先渲染骨架、读锚点，再翻转 state 换成真实内容，
 * 上、下、左三边逐一比较（容差 0.5dp）；上界与收缩按各用例声明的约束判断，每组覆盖 fontScale = 2。
 *
 * - 动态：经生产的 [timelineItems] 走一遍「加载中 → 有内容」，对日期分组头与第一条动态行。
 *
 * 收藏网格的骨架格（作品、人物各一）在 core:ui 的 `GridSkeletonGeometryTest`。
 */
@RunWith(AndroidJUnit4::class)
class ProfileSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)
    private var social by mutableStateOf(SocialState())
    private var feed by mutableStateOf(PagedList<TimelineItemBo>())

    private fun setContent(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme { content() }
            }
        }
        composeRule.waitForIdle()
    }

    private fun flipToReal() {
        composeRule.runOnIdle { showReal = true }
        composeRule.waitForIdle()
    }

    private fun sameTag(
        name: String,
        tag: String,
        index: Int? = null,
        compareLeft: Boolean = true,
        compareTop: Boolean = true,
    ) = SkeletonAnchor(
        name,
        { node(tag, index) },
        { node(tag, index) },
        compareLeft = compareLeft,
        compareTop = compareTop,
    )

    private fun node(tag: String, index: Int?) =
        if (index == null) {
            composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        } else {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)[index]
        }

    // ---- 动态 ----

    private fun assertActivity(fontScale: Float) {
        feed = PagedList<TimelineItemBo>(isLoading = true)
        setContent(fontScale) {
            // 与 ProfileContent 里的网格同配置，动态 item 经生产的 timelineItems 产出
            Box(Modifier.size(width = 400.dp, height = 900.dp)) {
                ContentGrid(cell = GridCell.Poster, modifier = Modifier.size(width = 400.dp, height = 900.dp)) {
                    timelineItems(
                        user = ProfileSkeletonFixtures.user,
                        isSelf = true,
                        state = feed,
                        days = timelineDaysOf(feed.items),
                        endText = "",
                        onLoadMore = {},
                        onRetry = {},
                        onOpen = {},
                        onDelete = {},
                        onSubject = {},
                    )
                }
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                sameTag("日期分组头", ProfileTimelineDayHeaderTag),
                sameTag("第一条动态行", ProfileTimelineRowTag, 0),
            ),
            flipToReal = {
                composeRule.runOnIdle { feed = ProfileSkeletonFixtures.singleSubjectFeed }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 动态骨架与真实内容同几何() = assertActivity(fontScale = 1f)

    @Test
    fun 字体放大两倍时动态骨架与真实内容同几何() = assertActivity(fontScale = 2f)

    // ---- 社交网格与目录列表：走生产构建器，避免只测单个组件漏掉 span 与过滤条 ----

    private fun assertSocial(fontScale: Float, short: Boolean = false) {
        setContent(fontScale) {
            ProfileTestGrid {
                socialItems(
                    state = social,
                    isSelf = true,
                    ownerName = "",
                    onSelect = {}, onLoadMore = {}, onRefresh = {}, onUserClick = {},
                    endText = null,
                )
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(sameTag("第一位好友", ProfileSocialItemTag, 0)),
            flipToReal = {
                composeRule.runOnIdle {
                    social = SocialState(
                        following = PagedList(
                            listOf(UserBo(id = 1, username = "alpha", nickname = if (short) "好友" else "用于验证两行预留的好友昵称")),
                            isLoading = false,
                        ),
                    )
                }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 社交网格骨架与真实内容同几何() = assertSocial(1f)

    @Test
    fun 字体放大两倍时社交网格骨架与真实内容同几何() = assertSocial(2f)

    @Test
    fun 短好友昵称也占两行预留与骨架同几何() = assertSocial(1f, short = true)

    @Test
    fun 字体放大两倍时短好友昵称也占两行预留与骨架同几何() = assertSocial(2f, short = true)

    @Composable
    private fun ProfileTestGrid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
        // 只装好友 tab（人物卡），列数同生产的 profileGridCellOf(FRIENDS)
        ContentGrid(cell = GridCell.Person, modifier = Modifier.size(width = 400.dp, height = 900.dp), content = content)
    }
}
