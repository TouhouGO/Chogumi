package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.PendingMark
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.commitClockNanos
import io.github.touhougo.chogumi.core.ui.composables.CarouselSlot
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 海报长按标记在页面上的样子与手势：角标是「我」的状态、窗口里换成可撤销的胶囊；按住朝方向一滑松手即标；
 * 没离开中心就松手环留着可点；封面不完整时先变完整再开环；未登录长按去登录、不弹环；「管理」去取那一部开收藏面板。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class PosterMarkHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val marker = RecordingMarker()
    private val editing = RecordingEditing()
    private var loginRequests = 0
    private var cardClicks = 0

    private fun show(isLoggedIn: Boolean = true, card: @Composable () -> Unit = { ShelfCard() }) {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                val state = remember { mutableStateOf(PosterMarkState()) }
                val controller = remember {
                    PosterMarkController(scope, { state.value }, { change -> state.value = change(state.value) }, FailureNotices(), marker, editing, MutableStateFlow(LocalDate.of(2026, 10, 12)))
                }
                PosterMarkHost(state.value, controller, isLoggedIn, { loginRequests++ }, remember { SnackbarHostState() }, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { card() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun ShelfCard() {
        PosterCard(
            imageUrl = null,
            title = "作品",
            style = PosterCardStyle.SHELF,
            onClick = { cardClicks++ },
            mark = rememberPosterMark(SUBJECT, SubjectType.ANIME),
            modifier = Modifier.width(108.dp).testTag(CARD),
        )
    }

    private fun longPressThen(move: Offset?, tag: String = CARD) {
        composeRule.onNodeWithTag(tag).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            if (move != null) {
                moveBy(move / 2f)
                moveBy(move / 2f)
            }
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `右上角是我的收藏状态_窗口里换成胶囊_点胶囊撤销`() {
        marker.marks.value = CollectionMarks(types = mapOf(SUBJECT to CollectionType.DONE))
        show()
        // 封面上只画状态图标，状态名给读屏
        composeRule.onNodeWithContentDescription("看过").assertIsDisplayed()
        composeRule.onNodeWithText("看过").assertDoesNotExist()

        marker.marks.value = CollectionMarks(
            types = mapOf(SUBJECT to CollectionType.DONE),
            // 提交时刻取「现在」：倒计时细条一帧就走完，测试时钟才能空闲（真正的提交由协调器决定，不看这里）
            pending = PendingMark(SUBJECT, CollectionType.WISH, commitClockNanos()),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("看过").assertDoesNotExist()
        // 真的点一下（不是读屏动作）：胶囊在整块可点的海报里，点它是撤销，不是进详情
        composeRule.onNodeWithContentDescription("撤销标为想看").assertIsDisplayed().performTouchInput { click() }
        composeRule.waitForIdle()
        assertEquals(listOf(SUBJECT), marker.undone)
        assertEquals(0, cardClicks)
    }

    @Test
    fun `按住朝正上一滑松手_标为看过`() {
        show()
        longPressThen(Offset(0f, -300f))
        assertEquals(listOf(SUBJECT to CollectionType.DONE), marker.marked)
    }

    @Test
    fun `按住朝右下一滑松手_标为抛弃_滑多远都行`() {
        show()
        longPressThen(Offset(520f, 300f))
        assertEquals(listOf(SUBJECT to CollectionType.DROPPED), marker.marked)
    }

    @Test
    fun `没离开中心就松手_环留着_点一格选它`() {
        show()
        longPressThen(move = null)
        assertTrue("松手不标", marker.marked.isEmpty())
        composeRule.onNodeWithText("搁置").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SUBJECT to CollectionType.ON_HOLD), marker.marked)
    }

    @Test
    fun `朝左下一滑松手_打开收藏面板去取这一部`() {
        show()
        longPressThen(Offset(-260f, 150f))
        assertTrue("不标状态", marker.marked.isEmpty())
        assertEquals(listOf(SUBJECT), editing.fetched)
    }

    @Test
    fun `未登录长按去登录_不弹环`() {
        show(isLoggedIn = false)
        longPressThen(move = null)
        assertEquals(1, loginRequests)
        composeRule.onNodeWithText("搁置").assertDoesNotExist()
    }

    /**
     * 封面还不完整（封面流侧边那张、没抽出的书脊）：长按先等它变完整再开环；这一笔不跟手，滑了松手也不标，环留着点选。
     */
    @Test
    fun `封面不完整时长按先变完整再开环_这一笔不跟手`() {
        var completed = 0
        show {
            val mark = rememberPosterMark(SUBJECT, SubjectType.ANIME)
            val prelude = remember { PosterMarkPrelude(pending = { completed == 0 }, complete = { completed++ }) }
            Box(Modifier.width(108.dp).testTag(CARD).posterMarkGesture(mark, prelude)) {
                SubjectPoster(url = null, modifier = Modifier.fillMaxWidth(), mark = mark)
            }
        }
        longPressThen(Offset(0f, -300f))
        assertEquals("先变完整", 1, completed)
        assertTrue("不跟手：朝上滑了也不标", marker.marked.isEmpty())
        composeRule.onNodeWithText("搁置").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SUBJECT to CollectionType.ON_HOLD), marker.marked)
    }

    /** 轮播里每一项的槽位与它此刻露出来的那一块（槽位自己的坐标）。 */
    private val slots = HashMap<Int, LayoutCoordinates>()
    private val masks = HashMap<Int, () -> Rect>()
    private var layer: PosterMarkLayer? = null

    /** 官方 multi-browse 轮播（与探索页热门同一个槽位）：第 0 项是大项，后面的被遮罩收窄；第 i 项是作品 [SUBJECT] + i。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CarouselPosters() {
        layer = LocalPosterMarkLayer.current
        HorizontalMultiBrowseCarousel(
            state = rememberCarouselState { 6 },
            preferredItemWidth = 160.dp,
            itemSpacing = 8.dp,
            minSmallItemWidth = 56.dp,
            maxSmallItemWidth = 56.dp,
            modifier = Modifier.width(320.dp).height(213.dp),
        ) { index ->
            val info = carouselItemDrawInfo
            masks[index] = { info.maskRect }
            CarouselSlot(MaterialTheme.shapes.extraLarge, Modifier.fillMaxSize().onPlaced { slots[index] = it }) {
                val mark = rememberPosterMark(SUBJECT + index, SubjectType.ANIME)
                Box(Modifier.fillMaxSize().testTag(CARD + index).posterMarkGesture(mark)) {
                    SubjectPoster(url = null, modifier = Modifier.fillMaxSize(), mark = mark)
                }
            }
        }
    }

    /** 第 [index] 项露出来的那一块在 [target] 坐标里的位置；顺带确认它真被收窄了（不然这条测试什么也没测）。 */
    private fun shownPart(index: Int, target: LayoutCoordinates): Rect {
        val slot = slots.getValue(index)
        val mask = masks.getValue(index)()
        assertTrue("第 $index 项被收窄（露出 ${mask.width} / ${slot.size.width} px）", mask.width < slot.size.width - 1f)
        return Rect(target.localPositionOf(slot, mask.topLeft), target.localPositionOf(slot, mask.bottomRight))
    }

    private fun assertInside(what: String, outer: Rect, inner: Rect) {
        assertTrue(
            "$what 在露出来的那一块里：$inner 不在 $outer 里",
            inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f && inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f,
        )
    }

    /** 轮播收窄的那几项：右上角的收藏状态与撤销胶囊都在露出来的那一块里，不被遮罩切掉两头。 */
    @Test
    fun `轮播收窄的那一项_角标与撤销胶囊排在露出来的那一块里`() {
        marker.marks.value = CollectionMarks(
            types = mapOf(SUBJECT + 2 to CollectionType.DONE),
            pending = PendingMark(SUBJECT + 1, CollectionType.WISH, commitClockNanos()),
        )
        show { CarouselPosters() }
        val chip = composeRule.onNodeWithContentDescription("撤销标为想看").fetchSemanticsNode().layoutInfo.coordinates
        val chipRect = slots.getValue(1).localBoundingBoxOf(chip, clipBounds = false)
        assertInside("撤销胶囊", shownPart(1, slots.getValue(1)), chipRect)

        val badge = composeRule.onNodeWithContentDescription("看过").fetchSemanticsNode().layoutInfo.coordinates
        val badgeRect = slots.getValue(2).localBoundingBoxOf(badge, clipBounds = false)
        val shown = shownPart(2, slots.getValue(2))
        assertInside("收藏角标", shown, badgeRect)
        assertTrue("角标贴着露出来的右上角", shown.right - badgeRect.right < badgeRect.width)
    }

    /** 浮起的封面是原位露出来的那一块（同一个大小、同一个位置），落回时不跳成整张。 */
    @Test
    fun `长按轮播收窄的那一项_浮起的只是露出来的那一块`() {
        show { CarouselPosters() }
        longPressThen(move = null, tag = CARD + 1)
        val ring = checkNotNull(layer?.session) { "环打开了" }
        val host = checkNotNull(layer?.coordinates)
        val shown = shownPart(1, host)
        assertEquals(shown.left, ring.cover.left, 0.5f)
        assertEquals(shown.right, ring.cover.right, 0.5f)
        assertEquals(shown.top, ring.cover.top, 0.5f)
        assertEquals(shown.bottom, ring.cover.bottom, 0.5f)
    }

    /**
     * 引导里的演示：在真的那一层放一遍长按环，封面上先是待提交的胶囊、再是提交后的角标，但什么也不交给数据端；
     * 停下就收起环、撤掉画上去的标记。
     */
    @Test
    fun `引导里的演示_封面上画出胶囊再换成角标_不写收藏_停下就撤掉`() {
        var playing by mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        show {
            layer = LocalPosterMarkLayer.current
            val mark = rememberPosterMark(SUBJECT, SubjectType.ANIME)
            PosterMarkDemo(mark, playing)
            Box(Modifier.width(108.dp).testTag(CARD).posterMarkGesture(mark)) {
                SubjectPoster(url = null, modifier = Modifier.fillMaxWidth(), mark = mark)
            }
        }
        // 按下、等长按、环展开、手指滑到看过、停一会儿松手：约 3.5 秒；松手后 4 秒窗口里是胶囊
        composeRule.mainClock.advanceTimeBy(5_000)
        assertTrue("演示不交给数据端", marker.marked.isEmpty())
        composeRule.onNodeWithContentDescription("撤销标为看过").assertIsDisplayed()

        // 窗口过了：胶囊换成提交后的角标
        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.onNodeWithContentDescription("撤销标为看过").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("看过").assertIsDisplayed()

        playing = false
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithContentDescription("看过").assertDoesNotExist()
        assertEquals("环收起了", null, layer?.session)
        assertTrue("演示不交给数据端", marker.marked.isEmpty())
    }

    private companion object {
        const val SUBJECT = 42
        const val CARD = "card"
    }
}
