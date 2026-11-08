package io.github.touhougo.chogumi.feature.profile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.IndexMembership
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 收录面板的画法：勾选框跟着 [isPicked] 走，没有改动时「保存」不可点；「新建目录」点开是名称输入框，名称为空时不能创建。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w448dp-h996dp")
class IndexPickerSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private var state by mutableStateOf(
        IndexPickerUiState(
            indexes = Load.Ready(
                listOf(
                    IndexMembership(IndexBo(id = 1, title = "公开 test", total = 3), relatedId = 9001),
                    IndexMembership(IndexBo(id = 2, title = "私密 test", total = 0), relatedId = null),
                ),
            ),
        ),
    )
    private val created = mutableListOf<Pair<String, Boolean>>()

    private fun show() {
        val actions = IndexPickerActions(
            toggle = { id -> state = state.copy(toggled = if (id in state.toggled) state.toggled - id else state.toggled + id) },
            save = {},
            create = { title, isPrivate, _ -> created += title to isPrivate },
            retry = {},
        )
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    IndexPickerSheetContent(state, FailureNotices(), actions, onDismiss = {})
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `已收录的目录勾上_没有改动时保存不可点_点一行后翻转并可保存`() {
        show()
        composeRule.onNodeWithTag("${IndexPickRowTagPrefix}1").assertIsSelected()
        composeRule.onNodeWithTag("${IndexPickRowTagPrefix}2").assertIsNotSelected()
        composeRule.onNodeWithTag(IndexPickerSaveTag).assertIsNotEnabled()

        composeRule.onNodeWithTag("${IndexPickRowTagPrefix}2").performClick()

        composeRule.onNodeWithTag("${IndexPickRowTagPrefix}2").assertIsSelected()
        composeRule.onNodeWithTag(IndexPickerSaveTag).assertIsEnabled()
    }

    @Test
    fun `新建目录点开是名称输入框_名称为空时不能创建_填了名称带着私密创建`() {
        show()
        composeRule.onNodeWithTag(IndexPickerNewTag).performClick()
        val create = composeRule.onNodeWithText(context.getString(R.string.index_pick_create))
        create.assertIsNotEnabled()

        composeRule.onNodeWithTag(IndexPickerNameTag).performTextInput("新目录")
        composeRule.onNodeWithText(context.getString(R.string.private_collection)).performClick()
        create.assertIsEnabled().performClick()

        assertEquals(listOf("新目录" to true), created)
    }
}
