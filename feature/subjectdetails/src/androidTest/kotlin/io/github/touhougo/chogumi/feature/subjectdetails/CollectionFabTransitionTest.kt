package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test

/**
 * 收藏 FAB 的过渡按画出来的样子走：忙闲不变、只换文案（没收藏 →「在看」）时，旧文案退场、新文案进场，
 * 过渡途中两份内容同时在屏上；不是同一份内容里文字瞬间换掉。
 */
class CollectionFabTransitionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun text(res: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(res)

    @Test
    fun 忙闲不变只换文案时新旧内容一起过渡() {
        var collection by mutableStateOf<UserCollectionBo?>(null)
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                CollectionFab(
                    state = CollectionFabState.READY,
                    collection = collection,
                    subjectType = SubjectType.ANIME,
                    onClick = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        collection = UserCollectionBo(subjectId = 8, subjectType = SubjectType.ANIME, type = CollectionType.DOING)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(text(R.string.edit_collection)).assertExists()
        composeRule.onNodeWithText(text(CollectionType.DOING.labelResFor(SubjectType.ANIME))).assertExists()

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(R.string.edit_collection)).assertDoesNotExist()
    }
}
