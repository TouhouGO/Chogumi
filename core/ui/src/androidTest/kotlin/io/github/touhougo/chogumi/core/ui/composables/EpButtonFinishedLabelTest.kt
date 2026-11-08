package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 进度看完时 EP 按钮写「标为看过 / 读过」：说法随条目类型分动词，书籍不写成「看过」。 */
@RunWith(AndroidJUnit4::class)
class EpButtonFinishedLabelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun finishedButton(type: SubjectType) {
        composeRule.setContent {
            ChogumiTheme {
                EpButton(state = EpButtonState.Finished, onNext = {}, onMarkFinished = {}, showTarget = true, subjectType = type)
            }
        }
    }

    @Test
    fun 书籍读完写标为读过() {
        finishedButton(SubjectType.BOOK)
        val read = context.getString(R.string.collection_mark_as, context.getString(R.string.collection_done_book))
        composeRule.onNodeWithText(read).assertExists()
    }

    @Test
    fun 动画看完写标为看过() {
        finishedButton(SubjectType.ANIME)
        val watched = context.getString(R.string.collection_mark_as, context.getString(R.string.collection_done_anime))
        composeRule.onNodeWithText(watched).assertExists()
    }
}
