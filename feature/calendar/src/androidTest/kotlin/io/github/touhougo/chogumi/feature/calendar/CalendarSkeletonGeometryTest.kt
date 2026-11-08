package io.github.touhougo.chogumi.feature.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import io.github.touhougo.chogumi.core.ui.composables.SkeletonPosterTag
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 日历页的骨架与真实内容**几何同源**：内容出现时，骨架里已画出的东西不许挪位置。
 *
 * 做法同 core:ui 的 `SkeletonGeometryTest`：同一个窗口里先渲染骨架、读锚点，再翻转 state 换成真实内容、
 * 读同一批锚点，上、下、左三边逐一比较（容差 0.5dp，来自像素取整），不一致攒到最后一次性报告。
 * 每组都配了一条 fontScale = 2 的用例：行里文字的行高随字号缩放，写死 dp 的占位在 1 倍下可能碰巧对上。
 *
 * 时间线的行是分段列表项：标题通常一行，1 倍字号下行高由 48dp 宽的封面撑出，2 倍下由标题与在看人数两行文字撑出，
 * 所以真实标题取「一行写得下」的长度，骨架也是一行标题。骨架的两组（两行、三行）对应真实数据的两个时刻。
 */
@RunWith(AndroidJUnit4::class)
class CalendarSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    private fun setContent(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(width = 400.dp, height = 1400.dp)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun node(tag: String, index: Int) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)[index]

    /**
     * 骨架与真实的同一个锚点：[skeletonTag] / [realTag] 指向两边各自的节点（多数情况下是同一个 tag）；
     * 同一个 tag 出现多次时（封面框）按树中顺序取第 [skeletonIndex] / [realIndex] 个。
     */
    private fun anchor(name: String, skeletonTag: String, realTag: String = skeletonTag, skeletonIndex: Int = 0, realIndex: Int = 0) =
        SkeletonAnchor(name, { node(skeletonTag, skeletonIndex) }, { node(realTag, realIndex) })

    private fun fixtureItem(id: Int, title: String) = CalendarItemBo(
        subject = SubjectBo(id = id, type = SubjectType.ANIME, name = title, nameCn = title, eps = 13),
        watchers = 21000,
    )

    private fun realState(title: String): CalendarUiState {
        val followed = fixtureItem(1, title)
        fun entry(item: CalendarItemBo, minute: Int) = CalendarEntry(item, AiringMinute(minute))
        return CalendarUiState(
            now = FIXTURE_NOW,
            selectedDay = 3,
            calendar = Load.Ready(
                mapOf(
                    3 to CalendarDaySections(
                        following = listOf(entry(followed, SECOND_SLOT)),
                        // 在追那一行的行尾有进度小牌，标题列窄一截，标题会折成两行（骨架不知道哪一行在追）：放在比对的锚点之后，
                        // 前面各行都是「一行写得下」的形态
                        slots = listOf(
                            AiringSlot(AiringMinute(FIRST_SLOT), listOf(entry(fixtureItem(2, title), FIRST_SLOT), entry(fixtureItem(6, title), FIRST_SLOT))),
                            AiringSlot(
                                AiringMinute(SECOND_SLOT),
                                listOf(entry(fixtureItem(3, title), SECOND_SLOT), entry(fixtureItem(4, title), SECOND_SLOT), entry(followed, SECOND_SLOT)),
                            ),
                        ),
                        unknown = emptyList(),
                    ),
                ),
            ),
            progressCounts = mapOf(1 to ProgressCount.Known(null, current = 2, total = 13)),
        )
    }

    private fun skeletonState() = CalendarUiState(now = FIXTURE_NOW, selectedDay = 3, calendar = Load.Pending)

    @Composable
    private fun Content(title: String) {
        CalendarContent(
            uiState = if (showReal) realState(title) else skeletonState(),
            selectedDay = 3,
            onSelectDay = {},
            onBack = {},
            onSubjectClick = {},
            onRefresh = {},
        )
    }

    // ---- 整页骨架对比真实时间线 ----

    private val pageAnchors = listOf(
        anchor("追番分区", "calendar_following_section"),
        // 封面框在树中的顺序：追番横排在前（骨架三张、真实一张），时间线的行在后
        anchor("追番第一张封面", SkeletonPosterTag, REAL_COVER),
        anchor("追番第一张卡片", "calendar_skeleton_following_card_0", "calendar_following_card_1"),
        anchor("第一行封面", SkeletonPosterTag, REAL_COVER, skeletonIndex = 3, realIndex = 1),
        anchor("第一行（底边即行高）", CalendarSkeletonRowTagPrefix + 0, "calendar_row_2"),
        anchor("第二组第一行（顶边 = 第一组高度 + 组距）", CalendarSkeletonRowTagPrefix + 2, "calendar_row_3"),
    )

    private fun assertPageGeometry(fontScale: Float, title: String) {
        setContent(fontScale) { Content(title) }
        assertSkeletonMatchesReal(
            anchors = pageAnchors,
            flipToReal = {
                composeRule.runOnIdle { showReal = true }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 日历骨架与真实时间线同区块_同封面框_同行高_同组距() = assertPageGeometry(fontScale = 1f, title = TITLE_ONE_LINE_1X)

    @Test
    fun 字体放大两倍时日历骨架与真实时间线同区块_同封面框_同行高_同组距() =
        assertPageGeometry(fontScale = 2f, title = TITLE_ONE_LINE_2X)

    private companion object {
        /** 真实卡片与行的封面（测试数据没有图，画的是占位）。 */
        const val REAL_COVER = "subject_cover_placeholder"

        /** 20:00 与 21:00，放送日里的分钟数。 */
        const val FIRST_SLOT = 20 * 60
        const val SECOND_SLOT = 21 * 60

        /** 1 倍字号下行的标题列约 230dp 宽（bodyLarge 16sp，每行约 14 字）：9 个字一行写得下。 */
        const val TITLE_ONE_LINE_1X = "间谍过家家第三季"

        /** 2 倍字号下时刻列也放大一倍，标题列约 160dp、每行约 5 字：4 个字一行写得下。 */
        const val TITLE_ONE_LINE_2X = "魔女之旅"
    }
}
