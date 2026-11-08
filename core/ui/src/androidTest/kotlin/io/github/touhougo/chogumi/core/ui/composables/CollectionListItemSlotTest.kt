package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.res.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 列表行的 EP 槽位：**章节加载前后行内布局宽度完全不变**（这条测试的核心）。
 *
 * 标题取足够长的文字，让它撑满进度文字以外的整列宽度：标题节点的宽度 = 左列宽度 = 行宽 − 海报 − 槽位，
 * 槽位一旦随状态出现/消失/变宽，这个数就会变。直接断言布局宽度，不靠截图。
 */
@RunWith(AndroidJUnit4::class)
class CollectionListItemSlotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val title = "这是一个很长很长的作品名称".repeat(8)

    private var state by mutableStateOf<EpButtonState>(EpButtonState.Loading)
    private var type by mutableStateOf(SubjectType.ANIME)
    private var status by mutableStateOf(CollectionType.DOING)
    private var progressCount by mutableStateOf<ProgressCount>(ProgressCount.Known(null, 1, 12))
    private var pending by mutableStateOf<EpPendingVisual?>(null)

    private fun setRow(fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(400.dp)) {
                    CollectionListItem(
                        item = UserCollectionBo(
                            subjectId = 1,
                            subjectType = type,
                            type = status,
                            epStatus = 1,
                            subject = SubjectBo(id = 1, type = type, name = title, nameCn = title, eps = 12),
                        ),
                        onClick = {},
                        onEdit = {},
                        progressCount = progressCount,
                        epButtonState = state,
                        pendingVisual = pending,
                    )
                }
            }
        }
    }

    private fun titleWidth(): Dp {
        composeRule.waitForIdle()
        return composeRule.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot().width
    }

    private fun nextAt(position: Int) = EpButtonState.Next(
        episode = EpisodeBo(id = position, subjectId = 1, type = 0, name = "", nameCn = "", sort = position.toDouble()),
        position = position,
    )

    private val nextState = nextAt(2)

    private fun waitingIn(days: Long) = EpButtonState.Waiting(nextState.episode, daysUntil = days, position = 2, intervalDays = 7L)

    @Test
    fun `连点徽标出现和消失不能挤压进度条所在列`() {
        state = nextState
        setRow()
        val before = titleWidth()
        composeRule.runOnIdle { pending = EpPendingVisual(count = 1, commitAtNanos = 0L) }
        val first = titleWidth()
        composeRule.runOnIdle { pending = EpPendingVisual(count = 12, commitAtNanos = 0L) }
        val many = titleWidth()
        composeRule.runOnIdle { pending = null }
        val after = titleWidth()
        assertEquals(
            "进度数值始终是1/12，布局却发生变化：before=$before pending1=$first pending12=$many after=$after",
            before.value, first.value, 0.5f,
        )
        assertEquals(before.value, many.value, 0.5f)
        assertEquals(before.value, after.value, 0.5f)
    }

    @Test
    fun `连点中徽标位数与集号位数变化_标题列宽度不变`() = assertTapsKeepColumnStable(fontScale = 1f)

    @Test
    fun `字体放大两倍时连点中徽标位数与集号位数变化_标题列宽度不变`() = assertTapsKeepColumnStable(fontScale = 2f)

    /** 逐段走过：无徽标 → +1 → +12 → 集号进位(9→10、99→100)，进度恒为 1/12，列宽必须全程相同。 */
    private fun assertTapsKeepColumnStable(fontScale: Float) {
        state = nextAt(1)
        setRow(fontScale)
        val base = titleWidth()
        val steps = listOf(
            "+1 集号 9" to (9 to 1),
            "+12 集号 10" to (10 to 12),
            "+3 集号 99" to (99 to 3),
            "+12 集号 100" to (100 to 12),
        )
        for ((name, step) in steps) {
            val (position, count) = step
            composeRule.runOnIdle {
                state = nextAt(position - 1)
                pending = EpPendingVisual(count = count, commitAtNanos = 0L)
            }
            assertEquals("字体=$fontScale 待提交 $name", base.value, titleWidth().value, 0.5f)
            composeRule.runOnIdle { pending = null; state = nextAt(position) }
            assertEquals("字体=$fontScale 提交后 $name", base.value, titleWidth().value, 0.5f)
        }
    }

    @Test
    fun `加载前后标题列宽度不变`() {
        setRow()
        val loading = titleWidth()

        composeRule.runOnIdle { state = nextState }
        assertEquals("占位换成按钮，列宽不能变", loading.value, titleWidth().value, 0.5f)

        composeRule.runOnIdle { state = EpButtonState.Loading }
        composeRule.runOnIdle { state = EpButtonState.Empty }
        assertEquals("占位换成留空，槽位不能收缩", loading.value, titleWidth().value, 0.5f)
    }

    @Test
    fun `动画行槽位恒为紧凑按钮的宽_游戏行一个像素都不占`() {
        setRow()
        val anime = titleWidth()

        composeRule.runOnIdle { type = SubjectType.GAME; state = EpButtonState.Empty }
        val game = titleWidth()
        assertEquals("anime=$anime game=$game", epCompactWidth.value, game.value - anime.value, 0.5f)
    }

    @Test
    fun `可点_等待_完结与待提交之间切换_标题列宽度不变`() = assertStatesKeepColumnStable(fontScale = 1f)

    @Test
    fun `字体放大两倍时可点_等待_完结与待提交之间切换_标题列宽度不变`() = assertStatesKeepColumnStable(fontScale = 2f)

    /** 列表右侧一列所有状态同宽：标完已播的最后一集（可点 → 等待）、看完最后一集（→ 完结）时标题不会被挤得重新换行。 */
    private fun assertStatesKeepColumnStable(fontScale: Float) {
        state = nextState
        setRow(fontScale)
        val base = titleWidth()
        val steps = listOf<Pair<String, () -> Unit>>(
            "等待（倒计时环）" to { state = waitingIn(3L) },
            "等待且待提交" to { pending = EpPendingVisual(count = 1, commitAtNanos = 0L) },
            "完结且待提交" to { state = EpButtonState.Finished },
            "完结（对勾）" to { pending = null },
            "回到可点" to { state = nextState },
        )
        for ((name, change) in steps) {
            composeRule.runOnIdle(change)
            assertEquals("字体=$fontScale $name", base.value, titleWidth().value, 0.5f)
        }
    }

    @Test
    fun `等待态的环里写还有几天_放不下只写数字`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        state = waitingIn(3L)
        setRow()
        composeRule.onNodeWithText(context.getString(R.string.ep_button_waiting_days_compact, 3L), useUnmergedTree = true).assertExists()

        composeRule.runOnIdle { state = waitingIn(120L) }
        composeRule.onNodeWithText("120", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `非在看的动画行不留槽位_进度文字照常显示`() {
        setRow()
        val doing = titleWidth()

        composeRule.runOnIdle { status = CollectionType.WISH; state = EpButtonState.Empty }
        val wish = titleWidth()
        assertEquals(epCompactWidth.value, wish.value - doing.value, 0.5f)
        composeRule.onNodeWithText("看过 1 / 12 集", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `进度文字只说条目内条数_不出现系列绝对集号`() {
        setRow()
        // 分季条目：绝对集号 220，条目内序号 19；进度文字与按钮（读屏）都必须用领域层提供的序号。
        composeRule.runOnIdle {
            state = nextState.copy(episode = nextState.episode.copy(sort = 220.0), position = 19)
            progressCount = ProgressCount.Known(null, 18, 51)
        }
        composeRule.onNodeWithText("看过 18 / 51 集", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("第 19 集", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("第 220 集", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `逐集表未到位走加载态不显示任何NM文字`() {
        setRow()
        composeRule.runOnIdle { progressCount = ProgressCount.Loading }
        composeRule.onNodeWithText("看过 1 / 12 集", useUnmergedTree = true).assertDoesNotExist()
    }
}
