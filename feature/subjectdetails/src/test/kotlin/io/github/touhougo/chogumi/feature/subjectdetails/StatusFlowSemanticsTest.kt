package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.statistics.FlowBranch
import io.github.touhougo.chogumi.core.model.statistics.FlowCard
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 去向流向图的数字只画在画布上：读屏要能从图的描述里听到每个去向的人数与占比。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class StatusFlowSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 描述里有每个去向的人数占比和开始看想看的人数() {
        val card = FlowCard(
            started = 1000,
            branches = listOf(
                FlowBranch(CollectionType.DONE, 700, 0.7),
                FlowBranch(CollectionType.DOING, 200, 0.2),
                FlowBranch(CollectionType.DROPPED, 100, 0.1),
            ),
            wish = 321,
        )

        compose.setContent { ChogumiTheme { StatusFlow(card, SubjectType.ANIME) } }

        val wholeDescription = hasContentDescription("看过 700，70%", substring = true) and
            hasContentDescription("在看 200，20%", substring = true) and
            hasContentDescription("抛弃 100，10%", substring = true) and
            hasContentDescription("开始看 1000", substring = true) and
            hasContentDescription("想看 321", substring = true)
        compose.onNode(wholeDescription).assertExists()
    }
}
