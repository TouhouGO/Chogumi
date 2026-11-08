package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.EpisodeBo
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 曲目仍是同一张懒列表；固定视口只组合屏上曲目，曲序与分碟不改变行身份。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class TracksLazyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `千首曲目首屏不组合离屏行`() {
        val tracks = (1..1000).map { track(it) }
        lateinit var list: LazyListState
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                LazyColumn(state = list, modifier = Modifier.width(380.dp).height(240.dp)) {
                    tracksSection(AsyncList(Load.Ready(tracks), onRetry = {}))
                }
            }
        }
        compose.waitForIdle()
        val composed = compose.onAllNodes(hasText("曲名", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue(composed in 1 until tracks.size / 2, "首屏不能全量组合1000首曲目，实际组合 $composed")
        compose.onNodeWithText("曲名1000", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1000, list.layoutInfo.totalItemsCount, "每首是独立懒条目，单碟不画碟标题")
        assertEquals("track-1", list.layoutInfo.visibleItemsInfo.first().key)
        compose.runOnIdle { list.requestScrollToItem(999) }
        compose.waitForIdle()
        assertEquals("track-1000", list.layoutInfo.visibleItemsInfo.last().key)
    }

    @Test
    fun `分碟标题与曲目独立保留稳定身份和排序`() {
        val tracks = listOf(track(4).copy(disc = 2, sort = 2.0), track(2), track(3).copy(disc = 2, sort = 1.0), track(1))
        lateinit var list: LazyListState
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                LazyColumn(state = list, modifier = Modifier.width(380.dp).height(800.dp)) {
                    tracksSection(AsyncList(Load.Ready(tracks), onRetry = {}))
                }
            }
        }
        compose.waitForIdle()
        assertEquals(listOf("track-disc-1", "track-1", "track-2", "track-disc-2", "track-3", "track-4"), list.layoutInfo.visibleItemsInfo.map { it.key })
        compose.onNodeWithText("第 1 碟").assertExists()
        compose.onNodeWithText("第 2 碟").assertExists()
    }

    private fun track(id: Int) = EpisodeBo(id = id, subjectId = 1, type = 0, name = "track$id", nameCn = "曲名$id", sort = id.toDouble(), disc = 1)
}
