package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeCell
import io.github.touhougo.chogumi.core.model.EpisodeCellKind
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 章节方格的读屏描述与提示：「今天」的小点和播出日不能只靠颜色，没有播出日的格子不弹空提示。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class EpisodeTileSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 11)

    /** 第 1 集今天播出，第 2 集 10 月 20 日播出，第 3 集还没有播出日。 */
    private val episodes = listOf("2026-10-11", "2026-10-20", "").mapIndexed { index, airdate ->
        EpisodeBo(
            id = 1000 + index,
            subjectId = 8,
            type = EpisodeType.NORMAL,
            name = "ep$index",
            nameCn = "",
            sort = index + 1.0,
            airdate = airdate,
        )
    }

    private fun showGrid() {
        composeRule.setContent {
            ChogumiTheme {
                val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                EpisodeTileGrid(
                    subjectType = SubjectType.ANIME,
                    episodes = episodes,
                    watchStates = emptyMap(),
                    today = today,
                    pendingCommit = null,
                    offerMarkDone = false,
                    onWatchUpTo = gate.guard { _: io.github.touhougo.chogumi.core.model.EpisodeProgressTarget -> },
                    onToggleSpecial = gate.guard { _: EpisodeBo -> },
                    onMarkDone = gate.guard {},
                    onEpisodeLongPress = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun descriptionOf(index: Int): String =
        composeRule.onAllNodesWithTag(EpisodeTileTag)[index].fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    @Test
    fun 今天播出的格子读出今天播出() {
        showGrid()
        assertTrue(descriptionOf(0), descriptionOf(0).endsWith("今天播出"))
    }

    @Test
    fun 未播出的格子读出哪天播出() {
        showGrid()
        assertTrue(descriptionOf(1), descriptionOf(1).endsWith("10 月 20 日 周二播出"))
    }

    @Test
    fun 没有播出日的未播出格子点了不弹提示() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val tips = TileTips(scope)
        val cell = EpisodeCell(episodes[2], EpisodeCellKind.UNAIRED, airdate = null, airsToday = false, upcomingAirdate = null)

        tips.showUnaired(cell)

        assertNull(tips.unaired)
        assertNull(tips.anchorId)
        scope.cancel()
    }
}
