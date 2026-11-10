package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.FakeStatsModuleDao
import io.github.touhougo.chogumi.core.data.repository.StatsLayoutRepository
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.StatsDrawing
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.defaultStatsLayout
import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCard
import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCell
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeList
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeState
import io.github.touhougo.chogumi.core.ui.composables.ArrangeBar
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test

/** 统计的全部真画法、共享整理骨架与标签收展，按设备的实际窗口抓图。 */
@VisualCapture
class StatsModuleCapture {
    @get:Rule val compose = createComposeRule()
    private var dark by mutableStateOf(false)
    private var mode by mutableStateOf("default")
    private var subject by mutableStateOf(StatsCaptureFixtures.anime)
    private val tab get() = StatsCaptureFixtures.tab(subject)
    private var arrange by mutableStateOf(ArrangeState<StatsEditor>())
    private var selectedModule by mutableStateOf<StatsModule?>(null)
    private var actualArranger: StatsArranger? = null
    private var captureList: LazyListState? = null

    @Test
    fun 默认加满整理编辑添加与标签收展() {
        compose.setContent {
            ChogumiTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    PageSheetTestHost {
                        val scope = rememberCoroutineScope()
                        val repository = remember { StatsLayoutRepository(FakeStatsModuleDao()) }
                        val arranger = remember { StatsArranger(scope, repository, { arrange }, { arrange = it(arrange) }, { defaultStatsLayout() }, FailureNotices()) }
                        actualArranger = arranger
                        StatsArrangeSheets(arrange, arranger, tab, snackbar = null)
                        when (mode) {
                            "default", "all" -> {
                                val state = rememberLazyListState()
                                captureList = state
                                LazyColumn(Modifier.fillMaxSize(), state = state) {
                                val rows = if (mode == "default") defaultStatsLayout() else StatsData.entries.flatMap { data -> data.drawings.map { StatsModule("${data.name}:${it.name}", data, it) } }
                                items(rows, key = { it.id }) { module -> Column(Modifier.padding(vertical = 6.dp)) { StatsModuleBlock(module, tab, {}, {}, {}, {}, {}, {}) } }
                            }
                            }
                            "single" -> selectedModule?.let { module ->
                                Column(Modifier.padding(vertical = 6.dp)) { StatsModuleBlock(module, tab.copy(series = Load.Ready(SubjectSeries(emptyList(), listOf(SubjectRelationBo(110467, subject.type, "系列示例", "", relationId = 3, score = 8.7))))), {}, {}, {}, {}, {}, {}) }
                            }
                            "long" -> Column {
                                val cells = List(100) { EpisodeHeatCell(it + 1, (it + 1).toDouble(), it + 1, false) }
                                val heat = EpisodeHeatCard(cells)
                                StatsModuleBlock(StatsModule("long", StatsData.EPISODE_DISCUSSION, StatsDrawing.EPISODE_BARS),
                                    tab.copy(episodeHeat = Load.Ready(heat)), {}, {}, {}, {}, {}, {})
                            }
                            "arrange" -> Column {
                                ArrangeBar({}, {})
                                ArrangeList(defaultStatsLayout(), { it.id }, { statsModuleName(it, tab.subject.type) }, { listOf(it.drawing.label()) }, { StatsModuleLead(it.data) }, {}, {}, {}, "没有模块", "stats_capture")
                            }
                            "edit", "add" -> Unit
                            "tags" -> SubjectDetailsHeader(subject, 24.dp,
                                AppFeatures(true, true, true, true), {}, {}, {})
                        }
                    }
                }
            }
        }
        val width = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            for (screen in listOf("default", "all", "long", "arrange", "edit", "add", "tags")) {
                compose.runOnIdle {
                    actualArranger?.machine?.editorClosed()
                    actualArranger?.machine?.addingClosed()
                    mode = screen
                    if (screen == "edit") actualArranger?.edit(StatsModule("edit", StatsData.CONTROVERSY, StatsDrawing.CONTROVERSY_RIDGE))
                    if (screen == "add") actualArranger?.machine?.openAdding()
                }
                compose.waitForIdle()
                saveScreenshot("stats-modules", "$width-${if (isDark) "dark" else "light"}-$screen", compose.onRoot().captureToImage().asAndroidBitmap())
                if (screen == "default" || screen == "all") {
                    val count = if (screen == "default") defaultStatsLayout().size else StatsData.entries.sumOf { it.drawings.size }
                    for (index in 0 until count step 3) {
                        compose.runOnIdle { captureList?.requestScrollToItem(index) }
                        compose.waitForIdle()
                        saveScreenshot("stats-modules", "$width-${if (isDark) "dark" else "light"}-$screen-scroll-$index", compose.onRoot().captureToImage().asAndroidBitmap())
                    }
                }
                if (screen == "tags") {
                    compose.onNodeWithTag("details_tags_expand").performClick()
                    compose.waitForIdle()
                    saveScreenshot("stats-modules", "$width-${if (isDark) "dark" else "light"}-tags-expanded", compose.onRoot().captureToImage().asAndroidBitmap())
                    compose.onNodeWithTag("details_tags_collapse").performClick()
                }
            }
            compose.runOnIdle { actualArranger?.machine?.editorClosed(); actualArranger?.machine?.addingClosed() }
            for (data in StatsData.entries) for (drawing in data.drawings) {
                compose.runOnIdle { selectedModule = StatsModule("single", data, drawing); mode = "single" }
                compose.waitForIdle()
                saveScreenshot("stats-modules", "$width-${if (isDark) "dark" else "light"}-${drawing.name}", compose.onRoot().captureToImage().asAndroidBitmap())
            }
            for (work in StatsCaptureFixtures.types) {
                compose.runOnIdle { subject = work; mode = "default" }
                compose.waitForIdle()
                saveScreenshot("stats-modules", "$width-${if (isDark) "dark" else "light"}-${work.type.name}-default", compose.onRoot().captureToImage().asAndroidBitmap())
                compose.runOnIdle { mode = "tags" }
                compose.waitForIdle()
                saveScreenshot("stats-modules", "$width-${if (isDark) "dark" else "light"}-${work.type.name}-tags", compose.onRoot().captureToImage().asAndroidBitmap())
            }
            compose.runOnIdle { subject = StatsCaptureFixtures.anime }

        }
    }
}
