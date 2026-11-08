package io.github.touhougo.chogumi.core.ui.composables

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * [PageScaffold] 的顶栏：始终是页面底色，内容滚到它下面也不换色（只靠柔边分层）；
 * 往下读时让位给吸在它下面的一条（只留一层），整摞回到页面底色；往回一拉就回来。
 */
@RunWith(AndroidJUnit4::class)
// 矮视口让列表一屏放不下、能往上滚；尺寸是测试设计决定（not in M3）。
@Config(sdk = [34], qualifiers = "w360dp-h400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageScaffoldBarColorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var surface = Color.Unspecified

    @Test
    fun `往上滚时顶栏让位给吸在下面的一条，往回一拉顶栏回来`() {
        composeRule.setContent {
            ChogumiTheme {
                PageScaffold(title = TITLE) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text("吸顶行", Modifier.testTag(PINNED))
                        LazyColumn(Modifier.testTag(LIST)) {
                            items((1..60).toList()) { Text("第 $it 行") }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val barHeight = TopAppBarDefaults.TopAppBarExpandedHeight
        assertEquals("滚动前吸顶行在顶栏下面", barHeight.value, pinnedTop().value, 0.5f)

        scrollUp()
        composeRule.onNodeWithText(TITLE).assertIsNotDisplayed()
        assertEquals("往下读时顶栏让位，吸顶行独自在最上面", 0f, pinnedTop().value, 0.5f)

        pullBack()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        assertEquals("往回一拉顶栏回来，吸顶行又在它下面", barHeight.value, pinnedTop().value, 0.5f)
    }

    @Test
    fun `顶栏让位后下面没有吸顶的条，状态栏一截回到页面底色`() {
        val actual = statusStripAfterScrolling { padding ->
            LazyColumn(contentPadding = padding, modifier = Modifier.testTag(LIST)) {
                items((1..60).toList()) { Text("第 $it 行") }
            }
        }
        assertTrue("没有条吸在下面时状态栏一截应是页面底色 $surface，实际 $actual", actual.closeTo(surface))
    }

    @Test
    fun `顶栏让位后下面有吸住的条，状态栏一截也是页面底色`() {
        val actual = statusStripAfterScrolling { padding ->
            val list = rememberLazyListState()
            val edge = rememberStickyHeaderEdge(list)
            LazyColumn(
                state = list,
                contentPadding = padding,
                modifier = Modifier.testTag(LIST).stickyHeaderFade(edge),
                overscrollEffect = edge.overscrollEffect,
            ) {
                stickyHeader(key = "pinned") { Text("吸顶行", Modifier.testTag(PINNED)) }
                items((1..60).toList()) { Text("第 $it 行") }
            }
        }
        assertTrue("顶栏收满后有条吸着，状态栏一截与白底正文同色 $surface，实际 $actual", actual.closeTo(surface))
    }

    /** 往上滚到顶栏让位，取状态栏一截的颜色。 */
    private fun statusStripAfterScrolling(content: @Composable (PaddingValues) -> Unit): Color {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            ChogumiTheme {
                surface = MaterialTheme.colorScheme.surface
                PageScaffold(title = TITLE, content = content)
            }
        }
        // 宿主测试没有系统栏：派发一份状态栏 insets，顶栏收满后才留得下状态栏那一截
        composeRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        composeRule.waitForIdle()

        scrollUp()
        composeRule.onNodeWithText(TITLE).assertIsNotDisplayed()
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        return pixels[pixels.width * 9 / 10, STATUS_BAR_PX / 2]
    }

    @Test
    fun `内容滚到顶栏下面后顶栏仍是页面底色`() {
        composeRule.setContent {
            ChogumiTheme {
                surface = MaterialTheme.colorScheme.surface
                PageScaffold(title = TITLE) { padding ->
                    LazyColumn(contentPadding = padding, modifier = Modifier.testTag(LIST)) {
                        items((1..60).toList()) { Text("第 $it 行") }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("滚动前顶栏应与页面同色 $surface，实际 ${barPixel()}", barPixel().closeTo(surface))

        scrollUp()
        // 顶栏让位之后往回一拉，它回到内容上方，这时内容仍在它下面
        pullBack()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        assertTrue("滚动后顶栏应仍是 $surface，实际 ${barPixel()}", barPixel().closeTo(surface))
    }

    private fun scrollUp() {
        repeat(2) { composeRule.onNodeWithTag(LIST).performTouchInput { swipeUp() } }
        composeRule.waitForIdle()
        val scrolled = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .onFirst().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("列表应已往上滚，滚动量 $scrolled", scrolled > 0f)
    }

    /** 慢慢往下拉一小段（不甩动），只够顶栏回来、列表回不到顶。 */
    private fun pullBack() {
        composeRule.onNodeWithTag(LIST).performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX, centerY + 150.dp.toPx()), durationMillis = 1000)
        }
        composeRule.waitForIdle()
    }

    private fun pinnedTop() = composeRule.onNodeWithTag(PINNED).getBoundsInRoot().top

    /** 顶栏右半边的空白处（没有标题和按钮）。 */
    private fun barPixel(): Color {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        return pixels[pixels.width * 9 / 10, 8]
    }
}

private const val TITLE = "标题"
private const val LIST = "list"
private const val PINNED = "pinned"
private const val STATUS_BAR_PX = 48

private fun Color.closeTo(other: Color): Boolean =
    abs(red - other.red) <= 2f / 255 && abs(green - other.green) <= 2f / 255 &&
        abs(blue - other.blue) <= 2f / 255 && abs(alpha - other.alpha) <= 2f / 255
