package io.github.touhougo.chogumi.feature.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.AiringMinute
import io.github.touhougo.chogumi.core.model.AiringSlot
import io.github.touhougo.chogumi.core.model.CalendarDaySections
import io.github.touhougo.chogumi.core.model.CalendarEntry
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 追番分区与时间线的骨架与真实内容同画面截图（[saveScreenshot] 的 `calendar-skeleton/`），字号比例 1 与 2 各一对。
 * 固定容器是截图的窗口选择（非 M3 规范），两态在同一组合里切换。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class CalendarSkeletonScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private var showReal by mutableStateOf(false)

    @Test
    fun 骨架与内容各存一张截图() = capturePair(fontScale = 1f, prefix = "calendar")

    @Test
    fun 字体放大两倍时骨架与内容各存一张截图() = capturePair(fontScale = 2f, prefix = "calendar-font2x")

    private fun fixtureItem(id: Int) = CalendarItemBo(
        subject = SubjectBo(id = id, type = SubjectType.ANIME, name = "作品$id", nameCn = "作品$id", eps = 13),
        watchers = 21000,
    )

    private fun slot(minute: Int, ids: IntRange) = AiringSlot(AiringMinute(minute), ids.map { CalendarEntry(fixtureItem(it), AiringMinute(minute)) })

    private fun state(real: Boolean): CalendarUiState {
        if (!real) return CalendarUiState(now = FIXTURE_NOW, selectedDay = 3, calendar = Load.Pending)
        return CalendarUiState(
            now = FIXTURE_NOW,
            selectedDay = 3,
            calendar = Load.Ready(mapOf(
                3 to CalendarDaySections(
                    following = listOf(CalendarEntry(fixtureItem(1), AiringMinute(20 * 60))),
                    slots = listOf(slot(20 * 60, 1..2), slot(21 * 60, 3..5)),
                    unknown = (6..8).map { CalendarEntry(fixtureItem(it), airing = null) },
                ),
            )),
            progressCounts = mapOf(1 to ProgressCount.Known(null, current = 2, total = 13)),
        )
    }

    private fun capturePair(fontScale: Float, prefix: String) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme(darkTheme = false) {
                    Surface {
                        Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                            CalendarContent(
                                uiState = state(showReal),
                                selectedDay = 3,
                                onSelectDay = {},
                                onBack = {},
                                onSubjectClick = {},
                                onRefresh = {},
                            )
                        }
                    }
                }
            }
        }
        listOf("skeleton" to false, "content" to true).forEach { (name, real) ->
            composeRule.runOnIdle { showReal = real }
            composeRule.waitForIdle()
            // 捕获真正的主题与页面根节点，避免仅保存空容器。
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "$prefix-$name", bitmap)
        }
    }

    private companion object {
        const val FOLDER = "calendar-skeleton"
    }
}
