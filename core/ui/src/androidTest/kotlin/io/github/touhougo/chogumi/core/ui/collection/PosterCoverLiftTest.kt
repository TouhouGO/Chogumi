package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionMarkFailure
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.MarkOrigin
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 长按浮起的封面（浮层画的是原位录下的那一份）与原位同一个圆角：按住、浮起、落回的每一帧，压满封面的内容四角都是圆的，
 * 浮起、落回的那一帧不会一下变圆或变直。浮层的录制内容只有真机画得出，所以放在仪器测试里；静止与按住在本地单测
 * （PosterCoverClipTest）。压的内容是纯红，四角取样处只要偏红就是漏裁。
 */
@RunWith(AndroidJUnit4::class)
class PosterCoverLiftTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private object NoMarker : CollectionMarker {
        override val marks = MutableStateFlow(CollectionMarks())
        override val failures: Flow<CollectionMarkFailure> = emptyFlow()
        override fun mark(origin: MarkOrigin, subjectId: Int, type: CollectionType) = Unit
        override fun undo(subjectId: Int) = Unit
    }

    private object NoEditing : CollectionEditing {
        override suspend fun remove(subjectId: Int): Outcome<Unit> = error("此测试不经收藏面板移除")

        override fun observeWork(subjectId: Int): Flow<Load<CollectionWork>> = emptyFlow()
        override suspend fun save(work: CollectionWork, edits: CollectionEdits): CollectionSaveResult = error("不经这里保存")
    }

    private val redFill: @Composable BoxScope.() -> Unit = { Box(Modifier.matchParentSize().background(Color.Red)) }

    @Test
    fun `可长按标记的封面_按住浮起落回的每一帧四角都被裁圆`() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                val state = remember { mutableStateOf(PosterMarkState()) }
                val controller = remember {
                    PosterMarkController(scope, { state.value }, { change -> state.value = change(state.value) }, FailureNotices(), NoMarker, NoEditing, MutableStateFlow(LocalDate.of(2026, 10, 12)))
                }
                PosterMarkHost(state.value, controller, isLoggedIn = true, onLogin = {}, remember { SnackbarHostState() }, Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PosterCard(
                            imageUrl = null,
                            title = "作品",
                            style = PosterCardStyle.SHELF,
                            onClick = {},
                            mark = rememberPosterMark(SUBJECT, SubjectType.ANIME),
                            modifier = Modifier.width(CARD_WIDTH).testTag(CARD),
                            coverOverlay = redFill,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val cover = coverBounds()
        assertCornersRound(cover, "静止")

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CARD).performTouchInput { down(center) }
        // 按住 → 到长按时长环打开、封面浮起 → 浮起走完
        repeat(FRAMES_HELD) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            assertCornersRound(cover, "按住第 $frame 帧")
        }
        // 没离开中心松手：环留着；点遮罩收起，封面落回
        // 环开着时页面对读屏隐藏，松手找未合并的树
        composeRule.onNodeWithTag(CARD, useUnmergedTree = true).performTouchInput { up() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().performTouchInput { click(Offset(4f, 4f)) }
        repeat(FRAMES_LANDING) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            assertCornersRound(cover, "落回第 $frame 帧")
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertCornersRound(cover, "落回之后")
    }

    private fun coverBounds(): DpRect =
        composeRule.onNodeWithTag(COVER_TAG, useUnmergedTree = true).getBoundsInRoot()

    /**
     * 封面此刻画在哪：浮起时放大、圆角跟着放大，所以先从截图里量出红色那一块的外框（上部一行量左右、偏左一列量上下，
     * 都避开环的圆钮），再在外框四角各往里 [CORNER_PROBE] dp 处取样——不管圆角是 8 还是 16，这一点都在圆角外，不能偏红
     * （环开着时下边两角被圆钮压着，只取上边两角）。
     * 封面左上内侧必须是红的：压的内容确实画了。
     */
    private fun assertCornersRound(cover: DpRect, moment: String) {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val density = composeRule.density.density
        val left = cover.left.value * density
        val top = cover.top.value * density
        val width = (cover.right - cover.left).value * density
        val height = (cover.bottom - cover.top).value * density
        val inside = pixels.colorAt(left + INSIDE * density, top + INSIDE * density)
        assertTrue("$moment：封面里应是压上去的红（取样 $inside）", inside.isReddish())

        val rowY = (top + height * 0.15f).toInt()
        val reddishX = ((left - width * 0.2f).toInt()..(left + width * 1.2f).toInt()).filter { pixels.colorAt(it.toFloat(), rowY.toFloat()).isReddish() }
        val colX = (left + width * 0.3f).toInt()
        val reddishY = ((top - height * 0.2f).toInt()..(top + height * 1.2f).toInt()).filter { pixels.colorAt(colX.toFloat(), it.toFloat()).isReddish() }
        val probe = CORNER_PROBE * density
        val corners = listOf(
            "左上" to pixels.colorAt(reddishX.first() + probe, reddishY.first() + probe),
            "右上" to pixels.colorAt(reddishX.last() - probe, reddishY.first() + probe),
            "左下" to pixels.colorAt(reddishX.first() + probe, reddishY.last() - probe),
            "右下" to pixels.colorAt(reddishX.last() - probe, reddishY.last() - probe),
        )
        // 环开着时下边两角压着「管理」「抛弃」两颗圆钮，只看上边两角
        val ringShown = composeRule.onAllNodes(hasText("抛弃") or hasContentDescription("抛弃", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        corners.take(if (ringShown) 2 else 4).forEach { (name, color) -> assertTrue("$moment：封面$name 角露出了直角（取样 $color）", !color.isReddish()) }
    }

    private fun PixelMap.colorAt(x: Float, y: Float): Color = this[x.toInt().coerceIn(0, width - 1), y.toInt().coerceIn(0, height - 1)]

    /** 红压在遮罩下也仍明显偏红；白底压在遮罩下只是变灰。 */
    private fun Color.isReddish(): Boolean = red - green > 0.3f && red - blue > 0.3f

    private companion object {
        const val SUBJECT = 42
        const val CARD = "card"
        const val COVER_TAG = "subject_cover_placeholder"
        val CARD_WIDTH = 108.dp

        /** 外框四角往里取样的距离（dp）：比 8dp 圆角在对角线上让出的 2.3dp 小，又避开边上抗锯齿的那一像素。 */
        const val CORNER_PROBE = 1.5f

        /** 「压的内容画着」的取样点离封面左上角的距离（dp）：在任何圆角之内，也在环的圆钮之外。 */
        const val INSIDE = 14f

        /** 长按时长（500ms）加上浮起的动画，按 16ms 一帧取够。 */
        const val FRAMES_HELD = 60
        const val FRAMES_LANDING = 40
    }
}
