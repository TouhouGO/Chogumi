package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 个人页收藏网格 / 好友网格的骨架格（[PosterCardSkeleton]、[PersonGridItemSkeleton]）
 * 与真实格（[PosterCard]、[PersonGridItem]）的**几何同源**：内容出现时，骨架里已画出的东西不许挪位置。
 *
 * 做法同 [SkeletonGeometryTest]：同一个窗口里先渲染骨架、读锚点，再翻转 state 换成真实内容，
 * 上、下、左三边逐一比较（容差 0.5dp）。每组配一条 fontScale = 2 的用例：文字行高随系统字号缩放，
 * 写死 dp 的占位在 1 倍字号下可能碰巧对上，放大后就会露馅。
 *
 * 人物卡的名字居中对齐，色条宽度与真实文字宽度不同，左缘不可比；名字行的高度由「整卡」的下缘锁住。
 *
 * 格宽取 114dp：400dp 宽的屏幕上 `ContentGrid(GridCell.Poster)` 排 3 列时的单格宽度。
 */
@RunWith(AndroidJUnit4::class)
class GridSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    private val name = "芙莉"
    // 长名字在两种字号下都占满真实组件的 maxLines，锁住加载后的最大高度。
    private val longName = "足够长的人物名称用于占满两行"

    private fun setContent(fontScale: Float, width: Dp = 114.dp, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(width)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun flipToReal() {
        composeRule.runOnIdle { showReal = true }
        composeRule.waitForIdle()
    }

    private fun assertPosterCard(fontScale: Float) {
        setContent(fontScale) {
            if (showReal) {
                PosterCard(imageUrl = null, title = longName, style = PosterCardStyle.GRID, onClick = null, mark = null, modifier = Modifier.testTag(CARD))
            } else {
                PosterCardSkeleton(PosterCardStyle.GRID, Modifier.testTag(CARD))
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor(
                    "整卡",
                    { composeRule.onNodeWithTag(CARD, useUnmergedTree = true) },
                    { composeRule.onNodeWithTag(CARD, useUnmergedTree = true) },
                ),
                SkeletonAnchor(
                    "海报框",
                    { composeRule.onNodeWithTag(SkeletonPosterTag, useUnmergedTree = true) },
                    { composeRule.onNodeWithTag("subject_cover_placeholder", useUnmergedTree = true) },
                ),
            ),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 作品网格卡骨架与真实卡同高_同海报框() = assertPosterCard(fontScale = 1f)

    @Test
    fun 字体放大两倍时作品网格卡骨架与真实卡同高_同海报框() = assertPosterCard(fontScale = 2f)

    private fun assertShortSubjectKeepsHeight(fontScale: Float) {
        setContent(fontScale) {
            if (showReal) {
                PosterCard(imageUrl = null, title = name, style = PosterCardStyle.GRID, onClick = null, mark = null, modifier = Modifier.testTag(CARD))
            } else {
                PosterCardSkeleton(PosterCardStyle.GRID, Modifier.testTag(CARD))
            }
        }
        val skeleton = composeRule.onNodeWithTag(CARD, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertSkeletonMatchesReal(
            anchors = listOf(SkeletonAnchor(
                "短作品名海报框",
                { composeRule.onNodeWithTag(SkeletonPosterTag, useUnmergedTree = true) },
                { composeRule.onNodeWithTag("subject_cover_placeholder", useUnmergedTree = true) },
            )),
            flipToReal = ::flipToReal,
        )
        val real = composeRule.onNodeWithTag(CARD, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // 标题固定占两行：一行的短标题出现，卡片也不变高（minLines 按拉丁字母补行时会矮约 1dp）
        assertEquals("短标题出现卡片不变高：$skeleton → $real", skeleton.bottom.value, real.bottom.value, 0.5f)
    }

    @Test
    fun 短作品名出现时卡片不变高() = assertShortSubjectKeepsHeight(1f)

    @Test
    fun 两倍字号时短作品名出现卡片不变高() = assertShortSubjectKeepsHeight(2f)

    private fun assertPersonCard(fontScale: Float) {
        setContent(fontScale) {
            if (showReal) {
                PersonGridItem(title = longName, imageUrl = null, onClick = null, modifier = Modifier.testTag(CARD))
            } else {
                PersonGridItemSkeleton(Modifier.testTag(CARD))
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor(
                    "整卡",
                    { composeRule.onNodeWithTag(CARD, useUnmergedTree = true) },
                    { composeRule.onNodeWithTag(CARD, useUnmergedTree = true) },
                ),
                SkeletonAnchor(
                    "头像框",
                    { composeRule.onNodeWithTag(PersonAvatarTag, useUnmergedTree = true) },
                    { composeRule.onNodeWithTag(PersonAvatarTag, useUnmergedTree = true) },
                    // 头像在列内水平居中，居中的位置随名字（按字换行后的行长）而变，属于数据形态；骨架只有一份。
                    // 纵向（top/bottom）与「整卡」的左缘仍严格比较。
                    compareLeft = false,
                ),
            ),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 人物竖卡骨架与真实卡同高_同头像框() = assertPersonCard(fontScale = 1f)

    @Test
    fun 字体放大两倍时人物竖卡骨架与真实卡同高_同头像框() = assertPersonCard(fontScale = 2f)

    private fun assertShortNameKeepsHeight(fontScale: Float) {
        setContent(fontScale) {
            if (showReal) {
                PersonGridItem(title = name, imageUrl = null, onClick = null, modifier = Modifier.testTag(CARD))
            } else {
                PersonGridItemSkeleton(Modifier.testTag(CARD))
            }
        }
        val skeleton = composeRule.onNodeWithTag(CARD, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertSkeletonMatchesReal(
            anchors = listOf(SkeletonAnchor(
                "短名字头像框",
                { composeRule.onNodeWithTag(PersonAvatarTag, useUnmergedTree = true) },
                { composeRule.onNodeWithTag(PersonAvatarTag, useUnmergedTree = true) },
                // 短名字的头像居中位置与骨架不同（数据决定），这里只锁纵向不下移。
                compareLeft = false,
            )),
            flipToReal = ::flipToReal,
        )
        val real = composeRule.onNodeWithTag(CARD, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // 名字固定占两行：一行的短名字出现，卡片也不变高
        assertEquals("短名字出现卡片不变高：$skeleton → $real", skeleton.bottom.value, real.bottom.value, 0.5f)
    }

    @Test
    fun 短人物名出现时卡片不变高() = assertShortNameKeepsHeight(1f)

    @Test
    fun 两倍字号时短人物名出现卡片不变高() = assertShortNameKeepsHeight(2f)

    private fun assertFollowingPersonRow(fontScale: Float) {
        setContent(fontScale, width = 400.dp) {
            ContentGrid(cell = GridCell.Person, modifier = Modifier.height(640.dp)) {
                items(6) { index ->
                    val modifier = Modifier.testTag("person_row_$index")
                    if (showReal) PersonGridItem(longName, null, null, modifier)
                    else PersonGridItemSkeleton(modifier)
                }
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(0, 3).map { index -> SkeletonAnchor(
                "人物网格第 ${index / 3 + 1} 行首格",
                { composeRule.onNodeWithTag("person_row_$index", useUnmergedTree = true) },
                { composeRule.onNodeWithTag("person_row_$index", useUnmergedTree = true) },
            ) },
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 长人物名出现时后续网格行不下移() = assertFollowingPersonRow(1f)

    @Test
    fun 两倍字号时长人物名出现后续网格行不下移() = assertFollowingPersonRow(2f)

    private companion object {
        const val CARD = "grid_geometry_card"
    }
}
