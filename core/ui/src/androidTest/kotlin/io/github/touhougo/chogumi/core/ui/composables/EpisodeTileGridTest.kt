package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
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

/**
 * 章节方格的交互契约：一次改动超过 15 集先确认、再点同一格才写；碰了别的格子或进度被别处改了就放弃确认；
 * 点「看到这里」是退一集；未播出的格子只提示不写；读屏能点、能长按；分段导航条切段。
 */
@RunWith(AndroidJUnit4::class)
class EpisodeTileGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026, 10, 11)
    private val writes = mutableListOf<Double>()
    private val longPressed = mutableListOf<Int>()
    private var watchStates by mutableStateOf(watched(1, 2))

    /** 写入时顺手把进度改成「看到第几集」，像 ViewModel 的乐观写入那样。 */
    private var applyWrites = false

    /** [count] 集正片，前 [aired] 集已播，其余 10 月 20 日播出。 */
    private fun episodes(count: Int = 40, aired: Int = 30): List<EpisodeBo> = (1..count).map { sort ->
        EpisodeBo(
            id = 1000 + sort,
            subjectId = 8,
            type = EpisodeType.NORMAL,
            name = "ep$sort",
            nameCn = "",
            sort = sort.toDouble(),
            airdate = if (sort <= aired) "2026-09-01" else "2026-10-20",
        )
    }

    private fun watched(vararg sorts: Int): Map<Int, EpisodeStatus> = sorts.associate { (1000 + it) to EpisodeStatus.DONE }

    private fun setGrid(episodes: List<EpisodeBo> = episodes()) {
        composeRule.setContent {
            ChogumiTheme {
                val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EpisodeTileGrid(
                        subjectType = SubjectType.ANIME,
                        episodes = episodes,
                        watchStates = watchStates,
                        today = today,
                        pendingCommit = null,
                        offerMarkDone = false,
                        onWatchUpTo = gate.guard { target: io.github.touhougo.chogumi.core.model.EpisodeProgressTarget ->
                            val sort = when (target) {
                                io.github.touhougo.chogumi.core.model.EpisodeProgressTarget.Empty -> 0.0
                                is io.github.touhougo.chogumi.core.model.EpisodeProgressTarget.Episode -> target.sort
                            }
                            writes += sort
                            if (applyWrites) watchStates = watched(*(1..sort.toInt()).toList().toIntArray())
                        },
                        onToggleSpecial = gate.guard { _: EpisodeBo -> },
                        onMarkDone = gate.guard {},
                        onEpisodeLongPress = { longPressed += it.id },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun tile(sort: Int) = composeRule.onAllNodesWithTag(EpisodeTileTag)[sort - 1]

    private fun confirmTitle(sort: Int) = context.getString(R.string.episode_jump_forward_title, sort.toString())

    @Test
    fun 十五集以内一点即成_点看到这里退一集() {
        setGrid()
        tile(10).performClick()
        composeRule.runOnIdle { watchStates = watched(*(1..10).toList().toIntArray()) }
        tile(10).performClick()
        composeRule.runOnIdle { assertEquals(listOf(10.0, 9.0), writes) }
    }

    private fun stateOf(sort: Int, state: Int) = context.getString(
        R.string.episode_tile_desc,
        context.getString(R.string.progress_episode, sort.toString()),
        context.getString(state),
    )

    @Test
    fun 一下标好几集时格子从前往后逐格换上新样子() {
        applyWrites = true
        setGrid()
        composeRule.mainClock.autoAdvance = false
        tile(10).performClick() // 看到第 2 集时点第 10 集：标上 3–10
        composeRule.mainClock.advanceTimeByFrame()
        tile(3).assertContentDescriptionEquals(stateOf(3, R.string.episode_state_watched))
        tile(10).assertContentDescriptionEquals(stateOf(10, R.string.episode_state_aired))
        composeRule.mainClock.advanceTimeBy(500)
        tile(10).assertContentDescriptionEquals(stateOf(10, R.string.episode_state_current))
    }

    @Test
    fun 别处来的进度变化直接过渡_不排队() {
        setGrid()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { watchStates = watched(*(1..10).toList().toIntArray()) }
        composeRule.mainClock.advanceTimeByFrame()
        tile(10).assertContentDescriptionEquals(stateOf(10, R.string.episode_state_current))
    }

    @Test
    fun 超过十五集先确认_再点同一格才写() {
        setGrid()
        tile(21).performClick() // 看到第 2 集时点第 21 集：一次标 19 集
        composeRule.onNodeWithText(confirmTitle(21)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("确认之前不写", emptyList<Double>(), writes) }

        tile(21).performClick()
        composeRule.onNodeWithText(confirmTitle(21)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf(21.0), writes) }
    }

    @Test
    fun 确认时点了别的格子_放弃确认并照常处理那一格() {
        setGrid()
        tile(21).performClick()
        tile(5).performClick() // 一次标 3 集：一点即成
        composeRule.onNodeWithText(confirmTitle(21)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf(5.0), writes) }

        tile(21).performClick() // 放弃过的确认不会被再点一次提交，而是重新问
        composeRule.onNodeWithText(confirmTitle(21)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(5.0), writes) }
    }

    @Test
    fun 确认时碰了方格以外的地方_放弃确认() {
        setGrid()
        tile(21).performClick()
        composeRule.onNodeWithText(context.getString(R.string.episode_summary_current, "2")).performClick()
        composeRule.onNodeWithText(confirmTitle(21)).assertDoesNotExist()

        tile(21).performClick()
        composeRule.onNodeWithText(confirmTitle(21)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<Double>(), writes) }
    }

    @Test
    fun 确认时进度被别处改了_确认作废() {
        setGrid()
        tile(21).performClick()
        composeRule.runOnIdle { watchStates = watched(1, 2, 3) }
        composeRule.onNodeWithText(confirmTitle(21)).assertDoesNotExist()

        tile(21).performClick() // 按新进度重新问，不是提交旧的那一次
        composeRule.onNodeWithText(confirmTitle(21)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<Double>(), writes) }
    }

    @Test
    fun 未播出的格子只提示哪天播_不写() {
        setGrid()
        tile(31).performClick()
        val locale = context.resources.configuration.locales[0]
        val tip = context.getString(R.string.episode_unaired_tip, "31", formatMonthDay(LocalDate.of(2026, 10, 20), locale))
        composeRule.onNodeWithText(tip).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<Double>(), writes) }
    }

    @Test
    fun 未播出提示到时消失时正按着那一格_长按照样生效() {
        setGrid()
        tile(31).performClick() // 提示出现，1.5 秒后消失
        composeRule.mainClock.advanceTimeBy(1_300)
        tile(31).performTouchInput { longClick() } // 按住途中提示到时消失：这一格的修饰符链不变，长按不被取消
        composeRule.runOnIdle {
            assertEquals(listOf(1031), longPressed)
            assertEquals(emptyList<Double>(), writes)
        }
    }

    @Test
    fun 长按出单集详情_并放弃确认() {
        setGrid()
        tile(21).performClick()
        tile(21).performTouchInput { longClick() }
        composeRule.onNodeWithText(confirmTitle(21)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(1021), longPressed)
            assertEquals(emptyList<Double>(), writes)
        }
    }

    @Test
    fun 读屏念集号与状态_能点也能长按() {
        setGrid()
        val description = context.getString(
            R.string.episode_tile_desc,
            context.getString(R.string.progress_episode, "1"),
            context.getString(R.string.episode_state_watched),
        )
        tile(1)
            .assertContentDescriptionEquals(description)
            .assert(hasClickAction())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun 离屏期间章节列表变了_回来落在下一集所在的段_不恢复旧段() {
        var shown by mutableStateOf(true)
        var list by mutableStateOf(episodes(count = 120, aired = 120))
        watchStates = watched(*(1..30).toList().toIntArray())
        composeRule.setContent {
            ChogumiTheme {
                val holder = rememberSaveableStateHolder()
                val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 像左右翻页翻走的选项卡：离开组合，存档留在页面的存档里
                    if (shown) {
                        holder.SaveableStateProvider("episodes") {
                            EpisodeTileGrid(
                                subjectType = SubjectType.ANIME,
                                episodes = list,
                                watchStates = watchStates,
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
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.episode_range_desc, "101", "120", 0)).performClick()
        composeRule.runOnIdle { shown = false }
        // 离开期间章节到齐了、进度也往前走了：250 集，看到第 230 集
        composeRule.runOnIdle {
            list = episodes(count = 250, aired = 250)
            watchStates = watched(*(1..230).toList().toIntArray())
        }
        composeRule.runOnIdle { shown = true }
        tile(1).assertContentDescriptionEquals(stateOf(201, R.string.episode_state_watched))
    }

    @Test
    fun 超过一百集出分段导航条_点第二段换到那一段() {
        watchStates = watched(*(1..30).toList().toIntArray())
        setGrid(episodes(count = 120, aired = 120))
        val second = context.getString(R.string.episode_range_desc, "101", "120", 0)
        composeRule.onNodeWithContentDescription(second)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab))
            .performClick()
        val first = context.getString(
            R.string.episode_tile_desc,
            context.getString(R.string.progress_episode, "101"),
            context.getString(R.string.episode_state_aired),
        )
        tile(1).assertContentDescriptionEquals(first)
    }
}
