package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionInput
import io.github.touhougo.chogumi.core.model.CollectionProgress
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagBo
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 收藏面板的交互契约：没改不能存、改了只交出改过的项；「想看」收起进度区，「看过」多问一句要不要一起标完；
 * 进度到头建议标为看过；标签按服务端规矩收，同排时输入框与芯片居中、窄屏时换行；吐槽到 380 字打不进去；关掉时交出修改；上次没保存的修改能放弃；
 * 点大数字输入集号；保存中不再提交；保存成功后退场。
 */
class CollectionSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026, 10, 12)
    private val subject = SubjectBo(
        id = 8, type = SubjectType.ANIME, name = "测试动画", nameCn = "测试动画", eps = 12,
        tags = listOf(TagBo("奇幻", 100), TagBo("战斗", 80)),
    )
    private val episodes = (1..12).map { sort ->
        EpisodeBo(id = 1000 + sort, subjectId = 8, type = EpisodeType.NORMAL, name = "ep$sort", nameCn = "", sort = sort.toDouble(), airdate = "2026-09-01")
    }
    private val doing = UserCollectionBo(
        subjectId = 8, subjectType = SubjectType.ANIME, type = CollectionType.DOING,
        rate = 7, tags = listOf("原标签"), comment = "原吐槽", isPrivate = true,
    )

    private var saving by mutableStateOf(false)
    private var saved by mutableStateOf(false)
    private var shown by mutableStateOf(true)
    private var removals = 0
    private val saves = mutableListOf<CollectionEdits>()
    private val closes = mutableListOf<CollectionEdits>()

    private fun setSheet(
        watchedUpTo: Int = 5,
        restored: CollectionEdits? = null,
        fontScale: Float = 1f,
        viewportWidth: Dp? = null,
    ) {
        val work = CollectionWork(
            subject = subject,
            collection = doing,
            episodes = Load.Ready(episodes),
            watchStates = (1..watchedUpTo).associate { (1000 + it) to EpisodeStatus.DONE },
        )
        compose.setContent {
            val density = LocalDensity.current
            // 受控宽度按局部密度测量，宽视口也能完整放进窄屏设备的物理窗口。
            CompositionLocalProvider(LocalDensity provides Density(if (viewportWidth != null) 2f else density.density, fontScale)) {
                ChogumiTheme {
                    val viewport = if (viewportWidth != null) Modifier.width(viewportWidth) else Modifier.fillMaxWidth()
                    Box(viewport.fillMaxHeight().testTag(CollectionViewportTag)) {
                        PageSheetTestHost {
                            if (shown) {
                                CollectionSheet(
                                    work = work,
                                    today = today,
                                    restored = restored,
                                    saving = saving,
                                    saved = saved,
                                    snackbar = remember { SnackbarHostState() },
                                    onRetryEpisodes = {},
                                    onSave = { saves += it },
                                    onRemove = { removals++ },
                                    onClose = {
                                        closes += it
                                        shown = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun 移除收藏先确认_取消不写_确认只提交一次() {
        setSheet()
        compose.onNodeWithText(text(R.string.collection_remove)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText(text(R.string.collection_remove)).performScrollTo().performClick()
        compose.onNodeWithTag("collection_remove_confirm").performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun save() = compose.onNodeWithText(text(R.string.save)).performClick()

    /** 状态组里的一格（按钮上挂着完整的说法）。 */
    private fun status(label: Int) = compose.onNode(hasContentDescription(text(label)))

    private val rating get() = compose.onNodeWithContentDescription(text(R.string.collection_rating))

    @Test
    fun 没改时不能存_改了评分才能存_只交出改过的项() {
        setSheet()
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        rating.performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled()
        save()
        compose.runOnIdle { assertEquals(listOf(CollectionEdits(rate = 9)), saves) }
    }

    @Test
    fun 选想看收起进度区_选看过问要不要一起标完() {
        setSheet()
        val ruler = text(R.string.progress_ruler_episodes)
        compose.onNodeWithContentDescription(ruler).assertExists()
        status(R.string.collection_wish_anime).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ruler).assertDoesNotExist()

        status(R.string.collection_done_anime).performClick()
        compose.onNodeWithText(text(R.string.collection_finish_all_episodes)).assertExists()
        save()
        compose.runOnIdle { assertEquals(listOf(CollectionEdits(type = CollectionType.DONE)), saves) }
    }

    @Test
    fun 进度到头建议标为看过_点了切到看过() {
        setSheet(watchedUpTo = 12)
        val suggestion = text(R.string.collection_progress_at_end)
        compose.onNodeWithText(suggestion).assertExists()
        compose.onNodeWithText(text(R.string.collection_mark_as, text(R.string.collection_done_anime))).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(suggestion).assertDoesNotExist()
        status(R.string.collection_done_anime).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun 标签打空格成一个_一个字的不收并说明_点标签删掉() {
        setSheet()
        val field = compose.onNodeWithTag(CollectionTagFieldTag)
        field.performScrollTo().performTextInput("热血 ")
        compose.onNodeWithContentDescription("热血").assertExists()
        field.assert(hasText(""))

        field.performTextInput("神 ")
        compose.onNodeWithText(text(R.string.collection_tag_too_short, CollectionInput.MIN_TAG_LENGTH)).assertExists()
        field.assert(hasText("神"))

        compose.onNodeWithContentDescription("原标签").performSemanticsAction(SemanticsActions.OnClick)
        save()
        compose.runOnIdle { assertEquals(listOf("热血"), saves.single().tags) }
    }

    @Test
    fun 宽视口下加标签框与标签芯片在一行里上下居中() = assertTagRowCentered(fontScale = 1f)

    @Test
    fun 宽视口大字号下加标签框与标签芯片仍上下居中() = assertTagRowCentered(fontScale = 2f)

    /** 足够宽的受控视口里，两者同排时竖直中心一致；换行由窄屏契约检查。 */
    private fun assertTagRowCentered(fontScale: Float) {
        setSheet(fontScale = fontScale, viewportWidth = 600.dp)
        assertEquals("同排契约的视口宽度", 600f, compose.onNodeWithTag(CollectionViewportTag).getUnclippedBoundsInRoot().width.value, 0.5f)
        val field = compose.onNodeWithTag(CollectionTagFieldTag).performScrollTo().getUnclippedBoundsInRoot()
        val chip = compose.onNodeWithContentDescription("原标签").getUnclippedBoundsInRoot()
        fun center(b: DpRect) = ((b.top + b.bottom) / 2).value
        assertEquals("加标签框与标签芯片的竖直中心", center(chip), center(field), 0.5f)
    }

    @Test
    fun 窄视口大字号标签与输入框换行不重叠且可添加标签() {
        setSheet(fontScale = 2f, viewportWidth = 380.dp)
        val field = compose.onNodeWithTag(CollectionTagFieldTag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        val chip = compose.onNodeWithContentDescription("原标签").assertIsDisplayed()
        val viewport = compose.onNodeWithTag(CollectionViewportTag).getUnclippedBoundsInRoot()
        val fieldBounds = field.getUnclippedBoundsInRoot()
        val chipBounds = chip.getUnclippedBoundsInRoot()
        assertEquals("换行契约的视口宽度", 380f, viewport.width.value, 0.5f)
        assertTrue("输入框应换到标签下方且不重叠", fieldBounds.top >= chipBounds.bottom)
        assertTrue("输入框换行后回到行首区域", fieldBounds.left < chipBounds.right)
        assertInsideViewport("原标签", chipBounds, viewport)
        assertInsideViewport("加标签框", fieldBounds, viewport)
        field.performTextInput("热血 ")
        compose.onNodeWithContentDescription("热血").assertExists()
        field.assert(hasText(""))
    }

    private fun assertInsideViewport(label: String, bounds: DpRect, viewport: DpRect) {
        assertTrue("$label 左边在视口内", bounds.left >= viewport.left)
        assertTrue("$label 右边在视口内", bounds.right <= viewport.right)
        assertTrue("$label 顶边在视口内", bounds.top >= viewport.top)
        assertTrue("$label 底边在视口内", bounds.bottom <= viewport.bottom)
    }

    @Test
    fun 吐槽到380字打不进去() {
        setSheet()
        val comment = compose.onNodeWithTag(CollectionCommentTag)
        comment.performScrollTo().performTextReplacement("字".repeat(CollectionInput.MAX_COMMENT))
        comment.performTextInput("多")
        compose.onNodeWithText(text(R.string.text_length_count, CollectionInput.MAX_COMMENT, CollectionInput.MAX_COMMENT)).assertExists()
        save()
        compose.runOnIdle { assertEquals("字".repeat(CollectionInput.MAX_COMMENT), saves.single().comment) }
    }

    @Test
    fun 点空白处关掉_交出此刻的修改() {
        setSheet()
        compose.onNodeWithText(text(R.string.private_collection)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.close)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(CollectionEdits(isPrivate = false)), closes) }
    }

    @Test
    fun 上次没保存的修改还在_放弃回到已保存的样子() {
        setSheet(restored = CollectionEdits(rate = 3))
        val banner = text(R.string.collection_restored)
        compose.onNodeWithText(banner).assertExists()
        rating.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.collection_rating_state, 3, levelName(3))))

        compose.onNodeWithText(text(R.string.collection_discard)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(banner).assertDoesNotExist()
        rating.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.collection_rating_state, 7, levelName(7))))
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
    }

    private fun levelName(score: Int): String = context.resources.getStringArray(R.array.rating_level_names)[score - 1]

    @Test
    fun 大字号下大数字与单位_分数与评语都在同一条基线上() {
        setSheet(fontScale = 2f)
        assertSameBaseline(RulerReadoutTag, "5", text(R.string.progress_ruler_total, "12", text(R.string.unit_episode)))
        assertSameBaseline(RatingReadoutTag, "7", levelName(7))
    }

    /** [row] 那一行里的两段字（各自一个 Text）的第一条基线在屏幕上的高度一样。 */
    private fun assertSameBaseline(row: String, a: String, b: String) {
        fun baseline(t: String): Float {
            val node = compose.onNode(hasText(t) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true)
            return (node.getUnclippedBoundsInRoot().top + node.getAlignmentLinePosition(FirstBaseline)).value
        }
        assertEquals("「$a」与「$b」的基线", baseline(a), baseline(b), 0.5f)
    }

    @Test
    fun 点大数字输入集号_跳到那一集() {
        setSheet()
        compose.onNodeWithText("5").performClick()
        compose.onNode(hasSetTextAction() and hasText("5")).performTextReplacement("9")
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitForIdle()
        save()
        compose.runOnIdle {
            assertEquals(
                listOf(CollectionEdits(progress = CollectionProgress.Episodes(io.github.touhougo.chogumi.core.model.EpisodeProgressTarget.Episode(1009, 9.0)))),
                saves,
            )
        }
    }

    @Test
    fun 保存中按钮转圈_再点不提交() {
        setSheet()
        rating.performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        compose.runOnIdle { saving = true }
        compose.onNode(hasText(text(R.string.save)) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.collection_saving)))
            .performClick()
        compose.runOnIdle { assertEquals(emptyList<CollectionEdits>(), saves) }
    }

    @Test
    fun 保存成功后退场_退场完交出修改() {
        setSheet()
        compose.runOnIdle { saved = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, closes.size)
            assertEquals("面板撤掉了", false, shown)
        }
    }
}

private const val CollectionViewportTag = "collection-test-viewport"
