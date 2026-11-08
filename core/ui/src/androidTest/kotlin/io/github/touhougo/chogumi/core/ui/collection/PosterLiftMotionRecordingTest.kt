package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionMarkFailure
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.MarkOrigin
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * 长按标记时封面圆角的**逐帧落盘**：封面上压着名次那样的顶部渐变托底，按住 → 环打开封面浮起 → 点遮罩收起封面落回，
 * 测试时钟一帧一帧推进，每帧截一张写到 [saveScreenshot] 的 `poster-lift/`（取下来在宿主上拼成序列看圆角怎么变）。
 */
@VisualCapture
class PosterLiftMotionRecordingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private object NoMarker : CollectionMarker {
        override val marks = MutableStateFlow(CollectionMarks())
        override val failures: Flow<CollectionMarkFailure> = emptyFlow()
        override fun mark(origin: MarkOrigin, subjectId: Int, type: CollectionType) = Unit
        override fun undo(subjectId: Int) = Unit
    }

    private object NoEditing : CollectionEditing {
        override suspend fun remove(subjectId: Int): Outcome<Unit> = error("此测试不经收藏面板移除")

        override fun observeWork(subjectId: Int): Flow<Load<CollectionWork>> = emptyFlow()
        override suspend fun save(work: CollectionWork, edits: CollectionEdits): CollectionSaveResult = error("不经这里保存")
    }

    @Test
    fun 按住浮起落回() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                val state = remember { mutableStateOf(PosterMarkState()) }
                val controller = remember {
                    PosterMarkController(scope, { state.value }, { change -> state.value = change(state.value) }, FailureNotices(), NoMarker, NoEditing, MutableStateFlow(LocalDate.of(2026, 10, 12)))
                }
                PosterMarkHost(state.value, controller, isLoggedIn = true, onLogin = {}, remember { SnackbarHostState() }, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PosterCard(
                            imageUrl = null,
                            title = "排行榜里的一部",
                            style = PosterCardStyle.SHELF,
                            onClick = {},
                            mark = rememberPosterMark(SUBJECT, SubjectType.ANIME),
                            modifier = Modifier.width(108.dp).testTag(CARD),
                            coverOverlay = {
                                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))))
                                Text("1", style = MaterialTheme.typography.headlineMediumEmphasized, color = Color.White, modifier = Modifier.align(Alignment.TopStart))
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CARD).performTouchInput { down(center) }
        shoot("lift", FRAMES_HELD)
        // 环开着时页面对读屏隐藏，松手找未合并的树
        composeRule.onNodeWithTag(CARD, useUnmergedTree = true).performTouchInput { up() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().performTouchInput { click(Offset(4f, 4f)) }
        shoot("land", FRAMES_LANDING)
    }

    private fun shoot(name: String, frames: Int) {
        repeat(frames) { frame ->
            composeRule.mainClock.advanceTimeBy(FRAME_MILLIS)
            saveScreenshot(FOLDER, "${name}_%03d".format(frame), composeRule.onRoot().captureToImage().asAndroidBitmap())
        }
    }

    private companion object {
        const val SUBJECT = 42
        const val CARD = "card"
        const val FOLDER = "poster-lift"
        const val FRAMES_HELD = 50
        const val FRAMES_LANDING = 40
        const val FRAME_MILLIS = 16L
    }
}
