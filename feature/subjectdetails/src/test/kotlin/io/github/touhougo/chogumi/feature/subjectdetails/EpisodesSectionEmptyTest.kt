package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileTag
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 章节 tab 没有正片时：只有特别篇的条目照样画出特别篇，一集都没有的条目说一句没有章节。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h800dp")
class EpisodesSectionEmptyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun episode(id: Int, type: Int) =
        EpisodeBo(id = id, subjectId = 8, type = type, name = "e$id", nameCn = "", sort = 1.0, airdate = "2020-01-01")

    private fun show(episodes: List<EpisodeBo>) {
        composeRule.setContent {
            ChogumiTheme {
                val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EpisodesSection(
                        subjectType = SubjectType.ANIME,
                        episodes = AsyncList(Load.Ready(episodes), onRetry = {}),
                        watchStates = emptyMap(),
                        today = LocalDate.of(2026, 10, 19),
                        pendingCommit = null,
                        expectedEpisodes = null,
                        offerMarkDone = false,
                        onWatchUpTo = gate.guard { _: io.github.touhougo.chogumi.core.model.EpisodeProgressTarget -> },
                        onToggleSpecial = gate.guard { _: EpisodeBo -> },
                        onMarkDone = gate.guard {},
                        onEpisodeLongPress = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `只有特别篇的条目 画出特别篇 没有正片方格`() {
        show(listOf(episode(1, EpisodeType.SPECIAL), episode(2, EpisodeType.SPECIAL)))
        composeRule.onNodeWithText(context.getString(R.string.episode_specials_title)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(EpisodeTileTag).assertCountEquals(0)
    }

    @Test
    fun `一集都没有的条目 说一句没有章节`() {
        show(emptyList())
        composeRule.onNodeWithText(context.getString(R.string.detail_episodes_empty)).assertIsDisplayed()
    }
}
