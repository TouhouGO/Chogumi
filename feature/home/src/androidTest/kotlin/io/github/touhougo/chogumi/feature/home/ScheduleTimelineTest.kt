package io.github.touhougo.chogumi.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.AiringMinute
import io.github.touhougo.chogumi.core.model.ScheduleDayGroup
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleTimelineTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val DpRect.width get() = right - left
    private val DpRect.height get() = bottom - top

    private fun createItem(id: Int, title: String) = HomeScheduleItem(
        subject = SubjectBo(
            id = id,
            type = SubjectType.ANIME,
            name = title,
            nameCn = title,
            imageLarge = null,
            ratingScore = 8.0,
            ratingRank = 1,
        ),
        nextUpcomingSort = 1.0,
        airedUnwatchedCount = 0,
        airing = null,
    )

    /**
     * 封面顶边的两枚小牌是预算：1 倍字号下时刻与未看集数并排、都在封面里；2 倍字号下两枚放不下时只留未看集数，时刻不画。
     */
    @Test
    fun 字体放大两倍时封面顶边放不下两枚小牌就只留未看集数() {
        val group = ScheduleDayGroup(
            daysUntil = 0L,
            date = LocalDate.of(2026, 10, 11),
            items = listOf(createItem(1, "动画1").copy(airing = AiringMinute(20 * 60), airedUnwatchedCount = 3)),
        )
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ScheduleTimeline(groups = listOf(group), onSubjectClick = {})
            }
        }
        composeRule.waitForIdle()

        val poster = composeRule.onNodeWithTag(HomeScheduleCardTagPrefix + "0_0").getUnclippedBoundsInRoot()
        val time = composeRule.onNodeWithTag("schedule_time_badge_0_0", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("1 倍字号下时刻小牌在封面里", time.left >= poster.left && time.right <= poster.right)

        fontScale = 2f
        composeRule.waitForIdle()
        val placed = composeRule.onAllNodesWithTag("schedule_time_badge_0_0", useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.layoutInfo.isPlaced }
        assertTrue("2 倍字号下两枚并排放不下，时刻小牌不画", placed.isEmpty())
    }

    /**
     * 字体放大两倍：头部「今天」不被头部行的高度切掉；封面上的集数小牌只占一行、不出封面
     * （放不下「第 125 集」时只写集号），所以两位数与三位数的小牌一样高。
     */
    @Test
    fun 字体放大两倍时头部的字不被切_集数小牌只占一行() {
        val group = ScheduleDayGroup(
            daysUntil = 0L,
            date = LocalDate.of(2026, 10, 11),
            items = listOf(createItem(1, "动画1"), createItem(2, "动画2").copy(nextUpcomingSort = 125.0)),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ScheduleTimeline(groups = listOf(group), onSubjectClick = {})
            }
        }
        composeRule.waitForIdle()

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("schedule_day_word_0").fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse("头部「今天」在高度上被切", layouts.single().didOverflowHeight)

        val short = composeRule.onNodeWithTag("schedule_episode_badge_0_0", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val long = composeRule.onNodeWithTag("schedule_episode_badge_0_1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val poster = composeRule.onNodeWithTag(HomeScheduleCardTagPrefix + "0_1").getUnclippedBoundsInRoot()
        assertEquals("三位数集号的小牌也只占一行", short.height.value, long.height.value, 0.5f)
        assertTrue("小牌不出封面", long.right <= poster.right)
    }

    /** 日期叠在天数下面：只有一部作品的一天，列宽就是一张海报宽，轴线上不留大片空白；日期仍是一行。 */
    @Test
    fun 单部海报长日期时日期在天数下方_列宽等于海报宽() {
        val longDateGroup = ScheduleDayGroup(
            daysUntil = 19L,
            date = LocalDate.of(2026, 10, 19), // 10 月 19 日 周一
            items = listOf(createItem(1, "死神")),
        )
        composeRule.setContent {
            ScheduleTimeline(
                groups = listOf(longDateGroup),
                onSubjectClick = {},
            )
        }
        composeRule.waitForIdle()

        val columnBounds = composeRule.onNodeWithTag("schedule_day_column_0").getUnclippedBoundsInRoot()
        val headerBounds = composeRule.onNodeWithTag("schedule_header_0").getUnclippedBoundsInRoot()
        val postersBounds = composeRule.onNodeWithTag("schedule_posters_row_0").getUnclippedBoundsInRoot()
        val dayWordBounds = composeRule.onNodeWithTag("schedule_day_word_0").getUnclippedBoundsInRoot()
        val dateBounds = composeRule.onNodeWithTag("schedule_date_text_0").getUnclippedBoundsInRoot()

        assertEquals("单部海报宽为 100dp", 100f, postersBounds.width.value, 0.5f)
        assertTrue("日期在天数下方", dateBounds.top >= dayWordBounds.bottom - 0.5.dp)
        assertTrue("头部不超出列宽", headerBounds.width <= columnBounds.width)
        assertEquals("列宽等于海报宽，头部不把这一天撑长", postersBounds.width.value, columnBounds.width.value, 0.5f)
        assertTrue("日期文本只占一行（不高于 24dp）", dateBounds.height <= 24.dp)
    }

    @Test
    fun 两天以上时第一天轴线铺到下一天的起点_末日止于列尾() {
        val group1 = ScheduleDayGroup(
            daysUntil = 0L,
            date = LocalDate.of(2026, 9, 30),
            items = listOf(createItem(1, "动画1")),
        )
        val group2 = ScheduleDayGroup(
            daysUntil = 1L,
            date = LocalDate.of(2026, 10, 1),
            items = listOf(createItem(2, "动画2")),
        )

        composeRule.setContent {
            ScheduleTimeline(
                groups = listOf(group1, group2),
                onSubjectClick = {},
            )
        }
        composeRule.waitForIdle()

        val col0Bounds = composeRule.onNodeWithTag("schedule_day_column_0").getUnclippedBoundsInRoot()
        val col1Bounds = composeRule.onNodeWithTag("schedule_day_column_1").getUnclippedBoundsInRoot()
        val line0Bounds = composeRule.onNodeWithTag("schedule_axis_line_0").getUnclippedBoundsInRoot()
        val posters0Bounds = composeRule.onNodeWithTag("schedule_posters_row_0").getUnclippedBoundsInRoot()

        assertEquals("第一天轴线右端等于下一天列的左缘", col1Bounds.left.value, line0Bounds.right.value, 0.5f)
        assertEquals(
            "天与天的间距算在第一天自己的列宽里",
            ScheduleTimelineDaySpacing.value,
            (col0Bounds.right - posters0Bounds.right).value,
            0.5f,
        )

        val line1Bounds = composeRule.onNodeWithTag("schedule_axis_line_1").getUnclippedBoundsInRoot()
        assertEquals("最后一天轴线止于列尾", col1Bounds.right.value, line1Bounds.right.value, 0.5f)
    }

    @Test
    fun 单天时轴线右端不超出该天列宽右缘() {
        val group1 = ScheduleDayGroup(
            daysUntil = 0L,
            date = LocalDate.of(2026, 9, 30),
            items = listOf(createItem(1, "动画1")),
        )

        composeRule.setContent {
            ScheduleTimeline(
                groups = listOf(group1),
                onSubjectClick = {},
            )
        }
        composeRule.waitForIdle()

        val col0Bounds = composeRule.onNodeWithTag("schedule_day_column_0").getUnclippedBoundsInRoot()
        val line0Bounds = composeRule.onNodeWithTag("schedule_axis_line_0").getUnclippedBoundsInRoot()

        assertEquals(
            "单天时轴线右端与该天列宽右缘对齐，不向外延伸",
            col0Bounds.right.value,
            line0Bounds.right.value,
            0.5f,
        )
    }
}
