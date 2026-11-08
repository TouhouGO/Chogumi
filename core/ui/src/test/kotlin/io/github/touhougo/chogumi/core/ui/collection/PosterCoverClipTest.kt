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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 封面上压满的内容（名次的渐变托底这一类）和封面图一起裁进封面的形状：静止、按住的每一帧四角都是圆的，
 * 不会有一层直角的东西露在圆角外面（浮起、落回在仪器测试 PosterCoverLiftTest）。压的内容是纯红，四角取样处只要偏红就是漏裁。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterCoverClipTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val redFill: @Composable BoxScope.() -> Unit = { Box(Modifier.matchParentSize().background(Color.Red)) }

    @Test
    fun `不能长按的封面_压满的内容四角被裁圆`() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                    PosterCard(
                        imageUrl = null,
                        title = "作品",
                        style = PosterCardStyle.SHELF,
                        onClick = {},
                        mark = null,
                        modifier = Modifier.width(CARD_WIDTH),
                        coverOverlay = redFill,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertCornersRound(coverBounds(), "静止")
    }

    @Test
    fun `可长按标记的封面_静止与按住的每一帧四角都被裁圆`() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                val state = remember { mutableStateOf(PosterMarkState()) }
                val controller = remember {
                    PosterMarkController(scope, { state.value }, { change -> state.value = change(state.value) }, FailureNotices(), RecordingMarker(), RecordingEditing(), MutableStateFlow(LocalDate.of(2026, 10, 12)))
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

        // 按住（还没到长按时长）：圆角往 16 变形，压满的内容跟着裁
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CARD).performTouchInput { down(center) }
        repeat(FRAMES_PRESSED) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            assertCornersRound(cover, "按住第 $frame 帧")
        }
        composeRule.onNodeWithTag(CARD).performTouchInput { cancel() }
        composeRule.mainClock.autoAdvance = true
    }

    private fun coverBounds(): DpRect =
        composeRule.onNodeWithTag(COVER_TAG, useUnmergedTree = true).getBoundsInRoot()

    /**
     * 封面四角各往里 1dp 处取样（不管圆角是 8 还是 16，这一点都在圆角外）：不能偏红。封面正中必须是红的——压的内容确实画了。
     * 浮起时封面放大 1.06、圆角跟着放大，这一点仍落在放大后的圆角外。
     */
    private fun assertCornersRound(cover: DpRect, moment: String) {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val density = composeRule.density.density
        fun at(x: Float, y: Float) = pixels.colorAt(x * density, y * density)
        val inset = 1f
        val corners = listOf(
            "左上" to at(cover.left.value + inset, cover.top.value + inset),
            "右上" to at(cover.right.value - inset, cover.top.value + inset),
            "左下" to at(cover.left.value + inset, cover.bottom.value - inset),
            "右下" to at(cover.right.value - inset, cover.bottom.value - inset),
        )
        corners.forEach { (name, color) -> assertTrue("$moment：封面$name 角露出了直角（取样 $color）", !color.isReddish()) }
        val center = at((cover.left.value + cover.right.value) / 2, (cover.top.value + cover.bottom.value) / 2)
        assertTrue("$moment：封面正中应是压上去的红（取样 $center）", center.isReddish())
    }

    private fun PixelMap.colorAt(x: Float, y: Float): Color = this[x.toInt().coerceIn(0, width - 1), y.toInt().coerceIn(0, height - 1)]

    /** 红压在遮罩下也仍明显偏红；白底压在遮罩下只是变灰。 */
    private fun Color.isReddish(): Boolean = red - green > 0.3f && red - blue > 0.3f

    private companion object {
        const val SUBJECT = 42
        const val CARD = "card"
        const val COVER_TAG = "subject_cover_placeholder"
        val CARD_WIDTH = 108.dp

        /** 按住 16 帧（约 260ms）：还没到长按时长，环不会打开。 */
        const val FRAMES_PRESSED = 16
    }
}
