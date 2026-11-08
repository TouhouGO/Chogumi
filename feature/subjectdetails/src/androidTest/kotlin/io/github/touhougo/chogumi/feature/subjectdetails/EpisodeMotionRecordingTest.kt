package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileGrid
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileTag
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * 章节方格动效的**逐帧落盘**：测试时钟一帧一帧推进，每帧截一张图，写到 [saveScreenshot] 的 `episode-motion/`
 * （取下来在宿主上拼成序列看）。真机上点章节会写用户进度，所以这里是假数据、真点击。
 *
 * 每个用例点一下，之后按 [FRAME_MILLIS] 一帧连拍 [FRAMES] 帧：逐格过场与弹一下、退一集、看完回荡、未播出轻抖、切段进场。
 */
@VisualCapture
class EpisodeMotionRecordingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 9, 28)
    private var episodes by mutableStateOf(episodes(count = 24, aired = 20))
    private var watchStates by mutableStateOf(watched(2))

    /** [count] 集正片，前 [aired] 集已播，其余 10 月 5 日播出。 */
    private fun episodes(count: Int, aired: Int): List<EpisodeBo> = (1..count).map { sort ->
        EpisodeBo(
            id = 1000 + sort,
            subjectId = 8,
            type = EpisodeType.NORMAL,
            name = "ep$sort",
            nameCn = "",
            sort = sort.toDouble(),
            airdate = if (sort <= aired) "2026-09-01" else "2026-10-05",
        )
    }

    /** 看到第 [upTo] 集。 */
    private fun watched(upTo: Int): Map<Int, EpisodeStatus> = (1..upTo).associate { (1000 + it) to EpisodeStatus.DONE }

    private fun setGrid() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                    EpisodeTileGrid(
                        subjectType = SubjectType.ANIME,
                        episodes = episodes,
                        watchStates = watchStates,
                        today = today,
                        pendingCommit = null,
                        offerMarkDone = false,
                        // 像 ViewModel 的乐观写入那样，点了立刻改进度
                        onWatchUpTo = gate.guard { target: io.github.touhougo.chogumi.core.model.EpisodeProgressTarget ->
                            watchStates = when (target) {
                                io.github.touhougo.chogumi.core.model.EpisodeProgressTarget.Empty -> emptyMap()
                                is io.github.touhougo.chogumi.core.model.EpisodeProgressTarget.Episode -> watched(target.sort.toInt())
                            }
                        },
                        onToggleSpecial = gatedNoop1(),
                        onMarkDone = gatedNoop(),
                        onEpisodeLongPress = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun tile(sort: Int) = composeRule.onAllNodesWithTag(EpisodeTileTag)[sort - 1]

    /** 连拍：每推进一帧截一张。 */
    private fun shoot(name: String) {
        repeat(FRAMES) { frame ->
            composeRule.mainClock.advanceTimeBy(FRAME_MILLIS)
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "${name}_%03d".format(frame), bitmap)
        }
    }

    @Test
    fun 逐格过场与弹一下() {
        setGrid()
        tile(10).performClick() // 看到第 2 集时点第 10 集：3–10 从前往后逐格填上
        shoot("cascade")
    }

    @Test
    fun 点看到这里退一集() {
        watchStates = watched(10)
        setGrid()
        tile(10).performClick()
        shoot("revert")
    }

    @Test
    fun 看完回荡() {
        episodes = episodes(count = 12, aired = 12)
        watchStates = watched(9)
        setGrid()
        tile(12).performClick() // 标上 10–12，正片看完
        shoot("wave")
    }

    @Test
    fun 未播出轻抖() {
        setGrid()
        tile(22).performClick()
        shoot("shake")
    }

    @Test
    fun 切段进场() {
        episodes = episodes(count = 120, aired = 120)
        watchStates = watched(30)
        setGrid()
        composeRule.onAllNodes(hasContentDescription("101", substring = true)).onFirst().performClick()
        shoot("enter")
    }

    private companion object {
        const val FOLDER = "episode-motion"
        const val FRAMES = 60
        const val FRAME_MILLIS = 16L
    }
}
