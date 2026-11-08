package io.github.touhougo.chogumi.core.ui.moderation

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.userReportTargetOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 举报面板：确认在标题行右侧，没有底栏和「取消」（下滑或点遮罩就是取消）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ReportSheetTitleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `「提交」在标题行里且没有「取消」`() {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    ReportSheet(
                        state = ReportState(userReportTargetOf(7), "Fixture User", reason = 1),
                        onSelectReason = {},
                        onComment = {},
                        onSubmit = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val titleRow = composeRule.onNodeWithTag(ReportTitleRowTag).fetchSemanticsNode().boundsInRoot
        val submit = composeRule.onNodeWithText(context.getString(R.string.report_submit)).fetchSemanticsNode().boundsInRoot
        assertTrue("「提交」应在标题行里：标题行 $titleRow，提交 $submit", titleRow.contains(submit.center))
        composeRule.onAllNodesWithText(context.getString(R.string.cancel)).assertCountEquals(0)
    }
}
