package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 正片多到分段时，点一段第一格的「看到这里」退到的是上一段的最后一集：写入与逐格过场都按全部正片算，不只看这一段。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class EpisodeTileRangeRevertTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val episodes = (1..150).map {
        EpisodeBo(id = it, subjectId = 8, type = EpisodeType.NORMAL, name = "ep$it", nameCn = "", sort = it.toDouble(), airdate = "2026-01-01")
    }

    @Test
    fun `点第二段第一格的看到这里 退到第一段最后一集`() {
        val written = mutableListOf<EpisodeProgressTarget>()
        composeRule.setContent {
            ChogumiTheme {
                val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                EpisodeTileGrid(
                    subjectType = SubjectType.ANIME,
                    episodes = episodes,
                    watchStates = (1..101).associateWith { EpisodeStatus.DONE },
                    today = LocalDate.of(2026, 10, 27),
                    pendingCommit = null,
                    offerMarkDone = false,
                    onWatchUpTo = gate.guard { target: EpisodeProgressTarget -> written += target },
                    onToggleSpecial = gate.guard { _: EpisodeBo -> },
                    onMarkDone = gate.guard {},
                    onEpisodeLongPress = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 默认停在「看到这里」所在的第二段（101–150），第一格就是第 101 集
        composeRule.onAllNodesWithTag(EpisodeTileTag)[0].performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(EpisodeProgressTarget.Episode(100, 100.0)), written)
    }
}
