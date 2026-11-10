package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.InboxTab
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlin.math.ceil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 铃铛面板的真实标题组件：2× 时动作先换行，当前页文字仍可读。 */
@RunWith(AndroidJUnit4::class)
class NotificationHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 两倍字号时通知页名保留且动作在第二行靠右() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectedLabel = context.getString(R.string.notification_title)
        val actionLabel = context.getString(R.string.notification_mark_all_read)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ChogumiTheme {
                    Box(Modifier.width(380.dp)) {
                        NotificationHeader(
                            shown = InboxTab.NOTICES,
                            unreadCount = 1,
                            unreadMessages = 0,
                            markAllEnabled = true,
                            onSelect = {},
                            onMarkAll = {},
                            modifier = Modifier.testTag(HEADER),
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(selectedLabel).assertIsDisplayed().assertIsSelected()
        val layouts = mutableListOf<TextLayoutResult>()
        val layoutAction = compose.onNodeWithText(selectedLabel, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
        assertTrue("页名必须提供可核对的文字布局", layoutAction?.invoke(layouts) == true && layouts.size == 1)
        val layout = layouts.single()
        assertFalse("当前页名不得被裁成省略号", layout.multiParagraph.didExceedMaxLines)
        assertFalse("当前页名不能被限高", layout.didOverflowHeight)
        // 缓存的段落约束可能比文字框宽，直接比较实际画出的每一行，像素向上取整。
        for (line in 0 until layout.lineCount) {
            assertTrue(
                "当前页名字形超出文字框：left=${layout.getLineLeft(line)}, right=${layout.getLineRight(line)}, size=${layout.size}",
                layout.getLineLeft(line) >= 0f && ceil(layout.getLineRight(line)).toInt() <= layout.size.width,
            )
        }
        val choice = compose.onNodeWithText(selectedLabel, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val action = compose.onNode(hasText(actionLabel) and hasClickAction()).getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag(HEADER, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("动作应在页签下一行且靠右：choice=$choice action=$action", action.top >= choice.bottom - 0.5.dp)
        assertTrue("动作应留标题行右内边距：header=$header action=$action", header.right - action.right <= 17.dp)
    }

    private companion object {
        const val HEADER = "notification-header"
    }
}
