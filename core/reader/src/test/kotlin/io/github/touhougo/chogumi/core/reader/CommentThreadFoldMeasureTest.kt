package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回归：短楼中楼不得因为内在尺寸探测而出现「展开全文」，也不得在无输入时自行改变高度。
 *
 * 这条用例跑在宿主（Robolectric）上、进 CI，钉住的是**测量层面的反馈环**而不是某段文案：
 * 楼中楼的 `IntrinsicSize.Min` 会经 `Row` 的固有尺寸路径命中楼层折叠的 `Modifier.layout`，
 * 让它拿着合成出来的超大高度回写溢出状态；状态一变、下一帧高度就变，于是每帧翻转。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommentThreadFoldMeasureTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 每帧记录一次；只追加，不覆盖，避免用「碰巧稳定」的末态冒充收敛。 */
    private val sizes = AtomicReference<List<IntSize>>(emptyList())

    @Test
    fun `短楼中楼不出现展开全文且高度不为输入而变`() {
        val thread = CommentThreadBo(
            anchor = item(1300, "主楼"),
            replies = listOf(item(1301, "回复"), item(1302, "收到")),
        )
        val expandLabel = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.getString(R.string.comment_expand_full)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChogumiTheme {
                // 360dp 固定视口只是测试设计决定（not in M3）。
                Box(Modifier.width(360.dp).onSizeChanged { sizes.set(sizes.get() + it) }) {
                    CommentThreadItem(thread = thread, floorNumber = "#13", onAvatarClick = {})
                }
            }
        }

        // 固定帧预算的手动推进：不 waitForIdle、不轮询某个瞬态。
        val seen = mutableListOf<String>()
        repeat(40) {
            composeRule.mainClock.advanceTimeByFrame()
            val bodies = composeRule.onAllNodesWithText("主楼", useUnmergedTree = true).fetchSemanticsNodes()
            val expand = composeRule.onAllNodesWithText(expandLabel, useUnmergedTree = true).fetchSemanticsNodes()
            seen += "帧$it:正文=${bodies.size}展开=${expand.size}"
        }

        val trace = seen.joinToString("\n")
        val observed = sizes.get()
        assertTrue("没有测到已放置的正文：\n$trace", seen.any { it.contains("正文=1") })
        assertFalse("短正文被误判为溢出，出现「展开全文」：\n$trace", seen.any { it.endsWith("展开=1") })
        assertEquals(
            "无输入的短楼中楼高度发生了变化：\n$observed",
            1,
            observed.distinct().size,
        )
    }

    private fun item(id: Int, content: String) = CommentItemBo(
        id = id,
        mainId = 1300,
        relatedId = 0,
        creatorId = id,
        creatorNickname = "甲",
        creatorUsername = "u$id",
        creatorAvatar = null,
        content = content,
        createdAt = 0L,
        state = CommentState.NORMAL,
    )
}
