package io.github.touhougo.chogumi.feature.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Failure
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
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 每日放送视觉与交互契约测试：
 * 1. 顶栏：M3 顶栏，48dp 返回触控区 + 标题「每日放送」（无副标题），滚动后仍是页面底色 surface（只靠柔边分层）；
 * 2. 七天选择器：连通按钮组，40dp 高；
 * 3. 今天小圆点：无论是否选中今天，今天那格底部正中均展示 4×4dp primary 圆点；
 * 4. 「我在追」独立分区：有在追条目时展示灰底容器卡，无在追条目时隐藏；
 * 5. 时间线：有时刻的按时刻分组、组前写时刻，「现在」线只在今天那页、画在第一个还没到的时刻之前，没有时刻的在「时刻未知」标题之下；
 * 6. 翻页隔离：每一天拥有独立的滚动状态，翻页互不串干扰。
 */
@RunWith(AndroidJUnit4::class)
class CalendarVisualTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun fixtureItem(
        id: Int,
        title: String,
        watchers: Int = 100,
        eps: Int? = null,
    ) = CalendarItemBo(
        subject = SubjectBo(
            id = id,
            type = SubjectType.ANIME,
            name = title,
            nameCn = title,
            eps = eps,
            imageLarge = null,
        ),
        watchers = watchers,
    )

    private fun entry(item: CalendarItemBo, minute: Int? = null) = CalendarEntry(item, minute?.let(::AiringMinute))

    private fun slot(minute: Int, vararg items: CalendarItemBo) = AiringSlot(AiringMinute(minute), items.map { entry(it, minute) })

    private fun sections(
        following: List<CalendarEntry> = emptyList(),
        slots: List<AiringSlot> = emptyList(),
        unknown: List<CalendarItemBo> = emptyList(),
    ) = CalendarDaySections(following = following, slots = slots, unknown = unknown.map { entry(it) })

    private fun state(
        selectedDay: Int,
        vararg days: Pair<Int, CalendarDaySections>,
        progressCounts: Map<Int, ProgressCount> = emptyMap(),
    ) = CalendarUiState(now = FIXTURE_NOW, selectedDay = selectedDay, calendar = Load.Ready(days.toMap()), progressCounts = progressCounts)

    private val followed = fixtureItem(1, "跃动青春 第二季", 21000, 13)

    /** 周三：20:00 两部（其中一部在追）、一部没有时刻。 */
    private fun defaultState() = state(
        3,
        3 to sections(
            following = listOf(entry(followed, TWENTY)),
            slots = listOf(slot(TWENTY, fixtureItem(2, "间谍过家家 第三季", 24000), followed)),
            unknown = listOf(fixtureItem(3, "魔女之旅 第二季", 20000)),
        ),
        progressCounts = mapOf(1 to ProgressCount.Known(null, current = 2, total = 13)),
    )

    private fun setContent(
        state: CalendarUiState = defaultState(),
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                CalendarContent(
                    uiState = state,
                    selectedDay = state.selectedDay,
                    onSelectDay = {},
                    onBack = onBack,
                    onSubjectClick = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `返回按钮触控区不小于48dp且标题正常显示`() {
        var backs = 0
        setContent(onBack = { backs++ })

        // M3 顶栏的返回钮画 40dp，触控区靠 minimumInteractiveComponentSize 撑到 48dp：量语义触控区，不量画出来的大小。
        val back = composeRule.onNodeWithTag("calendar_back")
        val minTouchPx = with(composeRule.density) { ChogumiDimens.MinTouchTarget.toPx() }
        val touchBounds = back.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(
            "返回钮触控区 ${touchBounds.width}x${touchBounds.height}px 应至少 ${minTouchPx}px",
            touchBounds.width >= minTouchPx && touchBounds.height >= minTouchPx,
        )
        back.performClick()
        assertEquals(1, backs)

        // 验证标题展示（不设副标题）
        composeRule.onNodeWithTag("calendar_top_bar")
            .assertIsDisplayed()
        composeRule.onNode(hasText("每日放送")).assertIsDisplayed()
    }

    @Test
    fun `七天选择器七格高40dp且默认选中周三`() {
        setContent()
        repeat(7) { index ->
            composeRule.onNodeWithTag("calendar_day_$index")
                .assertExists()
                .assertHeightIsEqualTo(40.dp)
        }
        // 默认选中周三（index 2）
        composeRule.onNodeWithTag("calendar_day_2").assertIsSelected()
    }

    @Test
    fun `今天小圆点在非选中状态下依然显示`() {
        val todayIndex = FIXTURE_TODAY - 1
        // 选择一个非今天的天（若今天是 1 则选 2，否则选 1）
        val otherDay = if (FIXTURE_TODAY == 1) 2 else 1
        setContent(state = state(otherDay, otherDay to CalendarDaySections.EMPTY))

        // 验证当前选中的不是今天
        composeRule.onNodeWithTag("calendar_day_$todayIndex")
            .assertExists()
        // 今天的小圆点必须依然存在且展示
        composeRule.onNodeWithTag("calendar_today_dot", useUnmergedTree = true)
            .assertExists()
            .assertIsDisplayed()
    }

    @Test
    fun `我在追分区在有关注条目时出现且不在追时不出现`() {
        val stateWithFollowing = state(
            3,
            3 to sections(following = listOf(entry(followed, TWENTY)), slots = listOf(slot(TWENTY, followed))),
            progressCounts = mapOf(1 to ProgressCount.Known(null, current = 2, total = 13)),
        )
        val stateWithoutFollowing = state(3, 3 to sections(slots = listOf(slot(TWENTY, fixtureItem(2, "间谍过家家 第三季", 24000)))))

        var currentState by mutableStateOf(stateWithFollowing)
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                CalendarContent(
                    uiState = currentState,
                    selectedDay = currentState.selectedDay,
                    onSelectDay = {},
                    onBack = {},
                    onSubjectClick = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 1. 有在追条目
        composeRule.onNodeWithTag("calendar_following_section").assertExists().assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_following_card_1").assertExists().assertIsDisplayed()

        // 2. 无在追条目
        currentState = stateWithoutFollowing
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar_following_section").assertDoesNotExist()
    }

    @Test
    fun `在追卡片标题下是正片进度`() {
        setContent()
        // 时间线里同一部作品的行尾也是这句进度：只认在追卡片里的那一句，并且在标题之下
        val card = "calendar_following_card_1"
        val title = composeRule.onNode(hasText("跃动青春 第二季") and hasAnyAncestor(hasTestTag(card)), useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val progress = composeRule.onNode(hasText("看过 2 / 13 集") and hasAnyAncestor(hasTestTag(card)), useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("进度在标题之下：$title / $progress", progress.top >= title.bottom)
    }

    @Test
    fun `放送表等待时画骨架_失败时画重试_那天没有放送时画暂无内容`() {
        var calendar by mutableStateOf<Load<Map<Int, CalendarDaySections>>>(Load.Pending)
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                CalendarContent(
                    uiState = CalendarUiState(now = FIXTURE_NOW, selectedDay = 3, calendar = calendar),
                    selectedDay = 3,
                    onSelectDay = {},
                    onBack = {},
                    onSubjectClick = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(CalendarSkeletonRowTagPrefix + 0)[0].assertExists()
        composeRule.onNode(hasText("暂无内容")).assertDoesNotExist()

        calendar = Load.Failed(Failure.Offline)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(CalendarSkeletonRowTagPrefix + 0).assertCountEquals(0)
        composeRule.onNode(hasText("重试")).assertExists()

        calendar = Load.Ready(mapOf(3 to CalendarDaySections.EMPTY))
        composeRule.waitForIdle()
        composeRule.onNode(hasText("暂无内容")).assertIsDisplayed()
    }

    @Test
    fun `顶栏与页面同色_当前那一天滚离顶部后吸顶的七天选择器仍是页面底色`() {
        var surface = Color.Unspecified
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                surface = MaterialTheme.colorScheme.surface
                CalendarContent(
                    uiState = state(3, 3 to sections(unknown = (1..25).map { fixtureItem(300 + it, "周三作品 $it") })),
                    selectedDay = 3,
                    onSelectDay = {},
                    onBack = {},
                    onSubjectClick = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals("没滚动时顶栏是 surface", surface.toArgb(), topBarCornerPixel())

        // 往上读时顶栏让位（高度收成 0），最上面吸住的是七天选择器：它也只能是页面底色
        composeRule.onNodeWithTag("calendar_list").performScrollToNode(hasTestTag("calendar_row_320"))
        composeRule.waitForIdle()
        assertEquals("滚动后吸顶的七天选择器仍是 surface（不换色、不加阴影）", surface.toArgb(), daySelectorCornerPixel())
    }

    /** 顶栏右上角的一个像素：那里没有按钮也没有文字，只有容器色。 */
    private fun topBarCornerPixel(): Int {
        val image = composeRule.onNodeWithTag("calendar_top_bar").captureToImage().toPixelMap()
        return image[image.width - 4, 4].toArgb()
    }

    /** 七天选择器左上角的一个像素：在左侧留白里，只有容器色。 */
    private fun daySelectorCornerPixel(): Int =
        composeRule.onNodeWithTag("calendar_day_selector").captureToImage().toPixelMap()[2, 2].toArgb()

    @Test
    fun `时间线包含全量条目_在追的也在里面`() {
        setContent()
        composeRule.onNodeWithTag("calendar_row_1").assertExists().assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_row_2").assertExists().assertIsDisplayed()
    }

    @Test
    fun `有时刻的一组前写时刻_没有时刻的在时刻未知标题之下`() {
        setContent()
        // 在追卡片封面上也标着 20:00：只认时间线里那一组前面的时刻
        composeRule.onNode(hasText("20:00") and !hasAnyAncestor(hasTestTag("calendar_following_section")), useUnmergedTree = true)
            .assertIsDisplayed()
        val lastTimed = composeRule.onNodeWithTag("calendar_row_1").getUnclippedBoundsInRoot()
        val header = composeRule.onNodeWithTag("calendar_unknown_header").assertIsDisplayed().getUnclippedBoundsInRoot()
        val unknown = composeRule.onNodeWithTag("calendar_row_3").getUnclippedBoundsInRoot()
        assertTrue("「时刻未知」标题在有时刻的最后一行之下", header.top >= lastTimed.bottom)
        assertTrue("没有时刻的行在标题之下", unknown.top >= header.bottom)
    }

    @Test
    fun `今天那页的现在线画在第一个还没到的时刻之前`() {
        // 现在 20:30：19:00 已开播，21:00 还没到
        setContent(
            state = state(
                FIXTURE_TODAY,
                FIXTURE_TODAY to sections(slots = listOf(slot(19 * 60, fixtureItem(1, "已开播")), slot(21 * 60, fixtureItem(2, "还没到")))),
            ),
        )
        val line = composeRule.onNodeWithTag("calendar_now_line").assertIsDisplayed().getUnclippedBoundsInRoot()
        val started = composeRule.onNodeWithTag("calendar_row_1").getUnclippedBoundsInRoot()
        val upcoming = composeRule.onNodeWithTag("calendar_row_2").getUnclippedBoundsInRoot()
        assertTrue("「现在」线在已开播的那组之下", line.top >= started.bottom)
        assertTrue("「现在」线在还没到的那组之上", upcoming.top >= line.bottom)
    }

    @Test
    fun `不是今天的页不画现在线`() {
        setContent(state = state(3, 3 to sections(slots = listOf(slot(19 * 60, fixtureItem(1, "已开播")), slot(21 * 60, fixtureItem(2, "还没到"))))))
        composeRule.onNode(hasText("19:00")).assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_now_line").assertDoesNotExist()
    }

    @Test
    fun `翻页不串滚动位置且每一天各自保留滚动位置`() {
        val wednesdayItems = (1..25).map { fixtureItem(300 + it, "周三作品 $it", 2000 - it * 50) }
        val thursdayItems = (1..25).map { fixtureItem(400 + it, "周四作品 $it", 2000 - it * 50) }
        var currentDay by mutableIntStateOf(3)
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                CalendarContent(
                    uiState = state(currentDay, 3 to sections(unknown = wednesdayItems), 4 to sections(unknown = thursdayItems)),
                    selectedDay = currentDay,
                    onSelectDay = { currentDay = it },
                    onBack = {},
                    onSubjectClick = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 1. 周三向下滚动到第 20 行
        composeRule.onNodeWithTag("calendar_list")
            .performScrollToNode(hasTestTag("calendar_row_320"))
        composeRule.waitForIdle()
        // 验证周三的第 1 行已经滚出屏幕
        composeRule.onNodeWithTag("calendar_row_301").assertIsNotDisplayed()

        // 2. 切换到周四 (index 3 为周四)
        composeRule.onNodeWithTag("calendar_day_3").performClick()
        composeRule.waitForIdle()

        // 3. 验证周四第一行展示在顶部（不继承周三的滚动位置）
        composeRule.onNodeWithTag("calendar_row_401").assertIsDisplayed()

        // 4. 再切回周三 (index 2 为周三)，验证周三原有的滚动位置完好保留
        composeRule.onNodeWithTag("calendar_day_2").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar_row_320").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_row_301").assertIsNotDisplayed()
    }

    @Test
    @VisualCapture
    fun `生成截图_01_今天全景`() {
        val itemFollowed1 = fixtureItem(1, "跃动青春 第二季", 21000, 13)
        val itemFollowed2 = fixtureItem(2, "孤独摇滚！第二季", 19500, 12)
        val itemOther1 = fixtureItem(3, "间谍过家家 第三季", 24000)
        val itemOther2 = fixtureItem(4, "魔女之旅 第二季", 21000)
        val itemOther3 = fixtureItem(5, "月刊少女野崎君 续", 20000)

        // 现在 20:30：19:00 那组已开播，「现在」线在 21:00 那组之前
        setContent(
            state = state(
                FIXTURE_TODAY,
                FIXTURE_TODAY to sections(
                    following = listOf(entry(itemFollowed1, 19 * 60), entry(itemFollowed2, 21 * 60)),
                    slots = listOf(slot(19 * 60, itemOther1, itemFollowed1), slot(21 * 60, itemOther2, itemFollowed2)),
                    unknown = listOf(itemOther3),
                ),
                progressCounts = mapOf(
                    1 to ProgressCount.Known(unit = null, current = 2, total = 13),
                    2 to ProgressCount.Known(unit = null, current = 1, total = 12),
                ),
            ),
        )
        writeScreenshot("01-today-overview")
    }

    @Test
    @VisualCapture
    fun `生成截图_02_无在追分区`() {
        val itemOther1 = fixtureItem(3, "间谍过家家 第三季", 24000)
        val itemOther2 = fixtureItem(4, "魔女之旅 第二季", 21000)
        val itemOther3 = fixtureItem(5, "月刊少女野崎君 续", 20000)
        val itemOther4 = fixtureItem(6, "迷宫饭 第二季", 18000)
        val itemOther5 = fixtureItem(7, "葬送的芙莉莲 第二季", 17500)
        val itemOther6 = fixtureItem(8, "电锯人 第二季", 15000)

        setContent(
            state = state(
                FIXTURE_TODAY,
                FIXTURE_TODAY to sections(
                    slots = listOf(slot(22 * 60, itemOther1, itemOther2), slot(24 * 60 + 30, itemOther3, itemOther4)),
                    unknown = listOf(itemOther5, itemOther6),
                ),
            ),
        )
        writeScreenshot("02-no-following")
    }

    @Test
    @VisualCapture
    fun `生成截图_03_七天选择器特写`() {
        val otherDay = if (FIXTURE_TODAY == 1) 2 else 1
        val itemOther1 = fixtureItem(3, "间谍过家家 第三季", 24000)
        val itemOther2 = fixtureItem(4, "魔女之旅 第二季", 21000)
        val itemOther3 = fixtureItem(5, "月刊少女野崎君 续", 20000)

        setContent(state = state(otherDay, otherDay to sections(slots = listOf(slot(TWENTY, itemOther1, itemOther2, itemOther3)))))
        writeScreenshot("03-day-selector-today-dot")
    }

    @Test
    @VisualCapture
    fun `生成截图_04_滚动位置隔离`() {
        val wednesdayItems = (1..20).map { fixtureItem(300 + it, "周三作品 $it", 2000 - it * 50) }
        val thursdayItems = (1..20).map { fixtureItem(400 + it, "周四作品 $it", 2000 - it * 50) }
        var currentDay by mutableIntStateOf(3)
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                CalendarContent(
                    uiState = state(currentDay, 3 to sections(unknown = wednesdayItems), 4 to sections(unknown = thursdayItems)),
                    selectedDay = currentDay,
                    onSelectDay = { currentDay = it },
                    onBack = {},
                    onSubjectClick = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 周三向下滚动到中段
        composeRule.onNodeWithTag("calendar_list")
            .performScrollToNode(hasTestTag("calendar_row_310"))
        composeRule.waitForIdle()

        // 翻到周四
        composeRule.onNodeWithTag("calendar_day_3").performClick()
        composeRule.waitForIdle()

        // 截图：显示周四从顶部第 1 行（周四作品 1）开始，未被周三滚动位置污染
        writeScreenshot("04-scroll-state-isolated")
    }

    private fun writeScreenshot(name: String) {
        saveScreenshot("calendar", name, composeRule.onRoot().captureToImage().asAndroidBitmap())
    }

    private companion object {
        /** 20:00，放送日里的分钟数。 */
        const val TWENTY = 20 * 60
    }
}
