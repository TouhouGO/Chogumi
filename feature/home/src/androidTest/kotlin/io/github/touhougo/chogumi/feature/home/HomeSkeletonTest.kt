package io.github.touhougo.chogumi.feature.home

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Failure
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 首页骨架的「待看」行必须与真实横滑同源：卡片向右依次排开，
 * 超出屏幕的卡片只是被裁掉，不会往回挤压到前一张上。
 *
 * 容差 0.5dp 来自像素取整。相邻间距不能写死成 [ToWatchItemSpacing]：官方 uncontained carousel
 * 会按容器宽度把条目撑到比 itemWidth 略宽，卡片本身仍是 [ToWatchItemWidth]，所以左缘到左缘的步长
 * 才是稳定量（不小于卡片宽 + 条目间距，且各卡片相等）。
 */
@RunWith(AndroidJUnit4::class)
class HomeSkeletonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(HomeUiState(today = HomeSkeletonFixtures.today, progress = Load.Pending))

    /** 主题里 bodyLarge 的字号（sp）：SkeletonText 的色条高 = 字号，字体比例 1 时 sp 与 dp 数值相同。 */
    private var bodyLargeFontSize = 0f
    private var retries = 0

    private fun setContent(fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    bodyLargeFontSize = MaterialTheme.typography.bodyLarge.fontSize.value
                    TodayHomeContent(
                        state = state,
                        onRefresh = { retries++ },
                        topPadding = 0.dp,
                        onSubjectClick = {},
                        onEdit = {},
                        onOpenProgress = {},
                        onContinueFilterChange = {},
                        onEpButtonTap = {},
                        onMarkFinished = {},
                        onFeedClick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun 好友等待显示骨架_失败重试可点_专注隐藏() {
        state = HomeUiState(today = HomeSkeletonFixtures.today, progress = Load.Ready(Unit), showFriends = true)
        setContent()
        composeRule.onNodeWithTag("home_friends_loading").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(friends = Load.Failed(Failure.Offline)) }
        composeRule.onNodeWithText("网络连接失败，请检查网络").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.runOnIdle { state = state.copy(showFriends = false) }
        composeRule.onNodeWithText("好友在看").assertDoesNotExist()
    }

    private fun cards(tagPrefix: String): List<DpRect> =
        (0..2).map { composeRule.onNodeWithTag(tagPrefix + it).getUnclippedBoundsInRoot() }

    @Test
    fun 骨架待看行占位卡片左缘严格递增且步长一致不重叠() {
        setContent()

        val skeleton = cards(HomeSkeletonCardTagPrefix)
        val minimumStep = ToWatchItemWidth.value + ToWatchItemSpacing.value
        val steps = skeleton.zipWithNext { previous, next -> next.left.value - previous.left.value }
        for ((index, step) in steps.withIndex()) {
            assertTrue("第 ${index + 2} 张左缘必须严格在前一张左缘右侧：$skeleton", step > 0f)
            assertTrue("相邻占位卡片不得重叠，步长应不小于卡片宽 + itemSpacing（$minimumStep）：$skeleton", step >= minimumStep - 0.5f)
        }
        assertEquals("各占位卡片的步长必须一致：$skeleton", steps[0], steps[1], 0.5f)
    }

    @Test
    fun 骨架占位卡片与真实横滑的卡片同位置同宽度() {
        setContent()
        val skeleton = cards(HomeSkeletonCardTagPrefix)

        composeRule.runOnIdle { state = HomeUiState(today = HomeSkeletonFixtures.today, progress = Load.Ready(Unit), toWatch = (1..3).map(HomeSkeletonFixtures::toWatchItem)) }
        composeRule.waitForIdle()
        val real = cards(HomeToWatchCardTagPrefix)

        for ((index, pair) in skeleton.zip(real).withIndex()) {
            val (placeholder, content) = pair
            assertEquals("第 ${index + 1} 张左缘骨架与真实内容必须一致：$skeleton / $real", content.left.value, placeholder.left.value, 0.5f)
            assertEquals("第 ${index + 1} 张宽度骨架与真实内容必须一致：$skeleton / $real", content.right.value - content.left.value, placeholder.right.value - placeholder.left.value, 0.5f)
        }
    }

    /** 骨架与真实内容共用的几何锚点：[skeletonTag] / [realTag] 指向两边各自的节点（多数情况下是同一个 tag）。 */
    private fun anchor(name: String, skeletonTag: String, realTag: String = skeletonTag) =
        SkeletonAnchor(
            name,
            { composeRule.onAllNodesWithTag(skeletonTag, useUnmergedTree = true)[0] },
            { composeRule.onAllNodesWithTag(realTag, useUnmergedTree = true)[0] },
        )

    private val anchors = listOf(
        anchor("待看标题行", HomeToWatchHeaderTag),
        anchor("第一张海报的顶（待看第一张卡片）", HomeSkeletonCardTagPrefix + 0, HomeToWatchCardTagPrefix + 0),
        anchor("第一张卡片的 EP 按钮", HomeToWatchEpTagPrefix + 0),
        anchor("时间表标题行", HomeScheduleHeaderTag),
        anchor("时间表第一张卡片", HomeScheduleCardTagPrefix + "0_0"),
        anchor("继续标题行", HomeContinueHeaderTag),
        anchor("继续第一行", HomeContinueRowTagPrefix + 0),
    )

    /**
     * 内容出现时骨架里已画出的东西不许向下（或任何方向）挪：逐个锚点比较上、下、左。
     * 不一致的攒到最后一次性报告；骨架里找不到的锚点（如漏画了整个区块）也算一条。
     */
    private fun assertAnchorsStayPut(fontScale: Float) {
        setContent(fontScale)
        assertSkeletonMatchesReal(
            anchors = anchors,
            flipToReal = {
                composeRule.runOnIdle { state = HomeSkeletonFixtures.fullState() }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 三个区块都有数据时_内容出现不改变骨架里各锚点的位置() = assertAnchorsStayPut(fontScale = 1f)

    @Test
    fun 字体放大两倍时三个区块都有数据_内容出现不改变骨架里各锚点的位置() = assertAnchorsStayPut(fontScale = 2f)

    /** 在 [row] 行从左往右找第一个与背景（最左列像素）不同的像素的 x。 */
    private fun leftmostPlaceholderX(pixels: PixelMap, row: Int): Int {
        val background = pixels[0, row]
        val x = (0 until pixels.width).firstOrNull { x ->
            val c = pixels[x, row]
            maxOf(abs(c.red - background.red), abs(c.green - background.green), abs(c.blue - background.blue)) > 4f / 255f
        }
        return checkNotNull(x) { "第 $row 行找不到占位色像素" }
    }

    /** 第一张卡片里某个占位块「贴着上沿一行」比「腰部一行」靠右多少 px；圆角越完整越大，切成直边则约 0。 */
    private fun cornerInsetPx(topDp: Float, heightDp: Float, topRowOffsetPx: Int): Int {
        val density = composeRule.density.density
        val topRow = (topDp * density).roundToInt() + topRowOffsetPx
        val midRow = ((topDp + heightDp / 2f) * density).roundToInt()
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        return leftmostPlaceholderX(pixels, topRow) - leftmostPlaceholderX(pixels, midRow)
    }

    /**
     * 海报占位只是矩形色块，圆角全靠框架里的 maskClip：贴着上沿 2px 的一行必须比腰部靠右一大截。
     * 框架丢了 maskClip，海报就是方角，这里的内缩约为 0。
     */
    @Test
    fun 骨架第一张海报占位的左上是圆角_框架走了maskClip() {
        setContent()
        val card = cards(HomeSkeletonCardTagPrefix).first()
        val inset = cornerInsetPx(card.top.value, ToWatchItemWidth.value * 4f / 3f, topRowOffsetPx = 2)
        val minimumInset = (PosterMinCornerInsetDp * composeRule.density.density).roundToInt()
        assertTrue("海报占位左上圆角应让上沿比腰部靠右至少 ${minimumInset}px，实际 ${inset}px（接近 0 说明是方角）", inset >= minimumInset)
    }

    /**
     * 官方 carousel 的条目遮罩是矩形，第一张卡片的起始边会被它切掉 2.7dp 左右（448dp 屏）。
     * 海报 28dp 圆角被切掉一点肉眼难辨，但下方文字占位只有 4dp 圆角，切掉就直接变成直角。
     * 所以量「标题占位条」：圆角完整（内容列跟着遮罩起始边平移）时上沿比腰部靠右约 2dp，
     * 左缘被切平（没有平移）时两者落在同一列。
     */
    @Test
    fun 骨架第一张卡片的标题占位左端圆角完整_没有被遮罩切平() {
        setContent()
        // 标题占位是一整行高的 SkeletonText，色条（高 = 字号）在行里垂直居中
        val title = composeRule.onAllNodesWithTag(HomeSkeletonTitleTag, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        val barTop = title.top.value + (title.bottom.value - title.top.value - bodyLargeFontSize) / 2f
        val inset = cornerInsetPx(barTop, bodyLargeFontSize, topRowOffsetPx = 1)
        val minimumInset = (TitleMinCornerInsetDp * composeRule.density.density).roundToInt()
        assertTrue("标题占位左端上沿应比腰部靠右至少 ${minimumInset}px，实际 ${inset}px（接近 0 说明左缘被切成直边）", inset >= minimumInset)
    }

    private companion object {
        /** 28dp 圆角贴上沿 2px 处内缩约 22dp，方角约 0；取 8dp 远离两头。 */
        const val PosterMinCornerInsetDp = 8f

        /** 4dp 圆角贴上沿一行内缩约 2dp，切平则约 0；取 1.2dp 远离两头。 */
        const val TitleMinCornerInsetDp = 1.2f
    }
}
