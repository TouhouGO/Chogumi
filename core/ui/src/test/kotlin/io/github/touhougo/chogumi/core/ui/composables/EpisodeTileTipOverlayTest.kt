package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 平板宽度下 12 集正片排成一行，上下都没有方格自己的位置放提示：点未播出的一格，提示浮在版面之上，
 * 下面「特别篇」的标题不动。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w1000dp-h1600dp")
class EpisodeTileTipOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026, 10, 11)

    /** 12 集正片（前 2 集已播，其余 10 月 20 日播出）加一集 OP。 */
    private val episodes = (1..12).map { sort ->
        EpisodeBo(
            id = 1000 + sort,
            subjectId = 8,
            type = EpisodeType.NORMAL,
            name = "ep$sort",
            nameCn = "",
            sort = sort.toDouble(),
            airdate = if (sort <= 2) "2026-09-01" else "2026-10-20",
        )
    } + EpisodeBo(id = 2001, subjectId = 8, type = EpisodeType.OP, name = "op", nameCn = "", sort = 1.0, airdate = "2026-09-01")

    @Test
    fun `一行方格点未播出的格子_提示不撑高方格_特别篇标题不动`() {
        composeRule.setContent {
            ChogumiTheme {
                val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
        }
        composeRule.waitForIdle()
        val tiles = composeRule.onAllNodesWithTag(EpisodeTileTag)
        // 一行：第 1 集与第 12 集在同一高度
        assertEquals(tiles[0].getUnclippedBoundsInRoot().top, tiles[11].getUnclippedBoundsInRoot().top)
        val specials = composeRule.onNodeWithText(context.getString(R.string.episode_specials_title))
        val before = specials.getUnclippedBoundsInRoot()

        tiles[2].performClick()
        val locale = context.resources.configuration.locales[0]
        composeRule.onNodeWithText(context.getString(R.string.episode_unaired_tip, "3", formatMonthDay(LocalDate.of(2026, 10, 20), locale)))
            .assertIsDisplayed()
        assertEquals(before, specials.getUnclippedBoundsInRoot())
    }
}
