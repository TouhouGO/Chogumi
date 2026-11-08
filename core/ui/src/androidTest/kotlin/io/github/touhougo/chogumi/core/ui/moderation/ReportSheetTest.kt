package io.github.touhougo.chogumi.core.ui.moderation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.userReportTargetOf
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 举报在途冻结表单，但页内面板仍允许关闭。 */
@RunWith(AndroidJUnit4::class)
class ReportSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 提交在途原因和说明不可改但面板可关闭() {
        var open by mutableStateOf(true)
        var dismisses = 0
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    Box(Modifier.fillMaxSize()) {
                        if (open) {
                            ReportSheet(
                                ReportState(BOB, "Bob", reason = ReportReason.ABUSE.value, comment = "说明", submitting = true),
                                onSelectReason = { error("提交在途不能改原因") },
                                onComment = { error("提交在途不能改说明") },
                                onSubmit = { error("提交在途不能重复发送") },
                                onDismiss = { dismisses++; open = false },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("辱骂、人身攻击").assertIsNotEnabled()
        compose.onNodeWithText("说明").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(ReportTitleRowTag).assertDoesNotExist()
        assertEquals(1, dismisses)
    }

    @Test
    fun 已成功确认态保留致谢和完成按钮() {
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    ReportSheet(
                        ReportState(BOB, "Bob", submittedText = "感谢报告，我们会尽快处理"),
                        onSelectReason = {}, onComment = {}, onSubmit = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("感谢报告，我们会尽快处理").assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsDisplayed()
    }
}

private val BOB = userReportTargetOf(7)
