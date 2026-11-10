package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.FixedWorkShelf
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.model.fixedWorkShelfEntry
import io.github.touhougo.chogumi.core.ui.composables.ArrangeBar
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/** 探索是共享整理组件的另一类真实调用方，各设备窗口浅深色都留图。 */
@VisualCapture
class ExploreArrangeCapture {
    @get:Rule val compose = createComposeRule()
    private var dark by mutableStateOf(false)

    @Test
    fun 探索整理列表两色() {
        val type = SubjectType.ANIME
        val entries = listOf(fixedWorkShelfEntry(FixedWorkShelf.RANKING), fixedWorkShelfEntry(FixedWorkShelf.CATCH_UP_SEASON))
        val shelves = exploreShelvesFor(type, LocalDate.of(2026, 11, 10), entries)
        val layout = ExploreLayout(emptyList(), shelves, entries)
        val content = ExploreTypeContent(shelves = shelves.associate { it.id to Load.Ready(listOf(SubjectBo(1, type, "作品", ""))) })
        compose.setContent {
            ChogumiTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        ArrangeBar({}, {})
                        ArrangeList(type, layout, content, NoArrangeActions)
                    }
                }
            }
        }
        val width = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
        listOf(false, true).forEach {
            compose.runOnIdle { dark = it }
            compose.waitForIdle()
            saveScreenshot("explore-arrange", "$width-${if (it) "dark" else "light"}", compose.onRoot().captureToImage().asAndroidBitmap())
        }
    }
}
