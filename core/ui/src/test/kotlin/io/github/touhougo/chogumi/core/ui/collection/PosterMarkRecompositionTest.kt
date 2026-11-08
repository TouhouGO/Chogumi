package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.testing.CompositionWork
import io.github.touhougo.chogumi.core.ui.testing.CountingCompositions
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 每张海报只读自己那一部的标记：别的作品被标、页面的标记状态别处变了，这张海报不重组。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class PosterMarkRecompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `别的作品被标_重组的作用域数与页上有几张海报无关`() {
        val marker = RecordingMarker()
        val work = CompositionWork()
        val posters = mutableStateOf(1)
        composeRule.setContent {
            CountingCompositions(work) {
                ChogumiTheme(darkTheme = false) {
                    val state = remember { mutableStateOf(PosterMarkState()) }
                    val controller = remember {
                        PosterMarkController(scope, { state.value }, { change -> state.value = change(state.value) }, FailureNotices(), marker, RecordingEditing(), MutableStateFlow(LocalDate.of(2026, 10, 12)))
                    }
                    PosterMarkHost(state.value, controller, true, {}, remember { SnackbarHostState() }, Modifier.fillMaxSize()) {
                        Posters(posters.value)
                    }
                }
            }
        }

        fun markOther(type: CollectionType): Int {
            composeRule.waitForIdle()
            work.reset()
            composeRule.runOnIdle { marker.marks.value = CollectionMarks(types = mapOf(OTHER to type)) }
            composeRule.waitForIdle()
            return work.entered
        }

        val one = markOther(CollectionType.WISH)
        composeRule.runOnIdle { posters.value = 5 }
        val five = markOther(CollectionType.DONE)

        assertEquals(one, five)
    }

    /** 一行 [count] 张可长按标记的海报，作品 1..[count]。单独一个可组合项：页面这一层重组时它按参数跳过，数到的只是海报自己。 */
    @Composable
    private fun Posters(count: Int) {
        Row {
            repeat(count) { index ->
                PosterCard(
                    imageUrl = null,
                    title = "作品$index",
                    style = PosterCardStyle.SHELF,
                    onClick = {},
                    mark = rememberPosterMark(index + 1, SubjectType.ANIME),
                    modifier = Modifier.width(60.dp),
                )
            }
        }
    }

    private companion object {
        /** 页上没有的那一部。 */
        const val OTHER = 999
    }
}
