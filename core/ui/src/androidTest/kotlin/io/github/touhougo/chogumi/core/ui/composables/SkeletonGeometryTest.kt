package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 骨架与真实内容的**几何同源**：内容出现时，骨架里已画出的东西不许挪位置。
 *
 * 做法：同一个窗口里先渲染骨架、读锚点，再翻转 state 换成真实内容、读同一批锚点，
 * 上、下、左三边逐一比较（容差 0.5dp）。比对本身走全仓共用的
 * `io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal`。
 *
 * 每组都配了一条 fontScale = 2 的用例：文字行高随系统字号缩放，写死 dp 的占位在 1 倍字号下可能碰巧对上，
 * 放大后就会露馅。
 */
@RunWith(AndroidJUnit4::class)
class SkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)
    private var progressCount by mutableStateOf<ProgressCount>(ProgressCount.Known(null, 1, 12))
    private var ongoing by mutableStateOf(false)

    // 取三个字：字体放大两倍后标题列只剩约 176dp，更长的标题会折成两行，而骨架只画一行（见 CollectionListItemSkeleton）
    private val title = "芙莉莲"

    private fun setContent(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(400.dp)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun node(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun textNode(text: String) = composeRule.onNodeWithText(text, useUnmergedTree = true)

    private fun flipToReal() {
        composeRule.runOnIdle { showReal = true }
        composeRule.waitForIdle()
    }

    // ---- 进度行：CollectionListItemSkeleton 对比 CollectionListItem ----

    private fun row() = UserCollectionBo(
        subjectId = 1,
        subjectType = SubjectType.ANIME,
        type = CollectionType.DOING,
        epStatus = 1,
        subject = SubjectBo(id = 1, type = SubjectType.ANIME, name = title, nameCn = title, eps = 12),
    )

    private val nextState = EpButtonState.Next(
        episode = EpisodeBo(id = 2, subjectId = 1, type = 0, name = "", nameCn = "", sort = 2.0),
        position = 2,
    )

    @Composable
    private fun RealRow(modifier: Modifier = Modifier) {
        CollectionListItem(
            item = row(),
            onClick = {},
            onEdit = {},
            modifier = modifier,
            progressCount = progressCount,
            epButtonState = nextState,
            ongoing = ongoing,
        )
    }

    private fun assertRowGeometry(fontScale: Float) {
        setContent(fontScale) {
            if (showReal) RealRow(Modifier.testTag(ROW)) else CollectionListItemSkeleton(SubjectType.ANIME, CollectionType.DOING, Modifier.testTag(ROW))
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor("整行", { node(ROW) }, { node(ROW) }),
                SkeletonAnchor("海报框", { node(SkeletonPosterTag) }, { node("subject_cover_placeholder") }),
                SkeletonAnchor("标题", { node(SkeletonTitleTag) }, { textNode(title) }),
            ),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 进度行骨架与真实行同高_同海报框_同标题起点() = assertRowGeometry(fontScale = 1f)

    @Test
    fun 字体放大两倍时进度行骨架与真实行同高_同海报框_同标题起点() = assertRowGeometry(fontScale = 2f)

    private fun assertTitleStableAcrossProgressLoad(fontScale: Float, ongoingAfter: Boolean) {
        progressCount = ProgressCount.Loading
        ongoing = false
        setContent(fontScale) { RealRow(Modifier.testTag(ROW)) }
        assertSkeletonMatchesReal(
            anchors = listOf(
                // 字体放大后文字列比海报高，标题总是贴着行顶，只剩整行高能暴露文字列高度的变化，故一并比较。
                SkeletonAnchor("ProgressCount.Loading→Known 整行（连载=$ongoingAfter）", { node(ROW) }, { node(ROW) }),
                SkeletonAnchor("ProgressCount.Loading→Known 标题（连载=$ongoingAfter）", { textNode(title) }, { textNode(title) }),
            ),
            flipToReal = {
                composeRule.runOnIdle {
                    progressCount = ProgressCount.Known(null, 1, 12)
                    ongoing = ongoingAfter
                }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 进度计数从加载换成已知_标题顶不变() = assertTitleStableAcrossProgressLoad(fontScale = 1f, ongoingAfter = false)

    @Test
    fun 字体放大两倍时进度计数从加载换成已知_标题顶不变() = assertTitleStableAcrossProgressLoad(fontScale = 2f, ongoingAfter = false)

    /** 章节一到，进度条从直线翻成波浪（连载中）与计数变已知同时发生：标题不能因为两种进度条一高一矮而挪位。 */
    @Test
    fun 进度计数变已知同时进度条翻成波浪_标题顶不变() = assertTitleStableAcrossProgressLoad(fontScale = 1f, ongoingAfter = true)

    // ---- 海报卡：SubjectCardVerticalSkeleton 对比 SubjectCardVertical ----

    private fun assertPosterCardGeometry(fontScale: Float) {
        val subject = SubjectBo(id = 1, type = SubjectType.ANIME, name = title, nameCn = title)
        setContent(fontScale) {
            if (showReal) {
                SubjectCardVertical(subject, onClick = {}, modifier = Modifier.width(ChogumiDimens.CoverWidth).testTag(CARD))
            } else {
                SubjectCardVerticalSkeleton(Modifier.width(ChogumiDimens.CoverWidth).testTag(CARD))
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor("整卡", { node(CARD) }, { node(CARD) }),
                SkeletonAnchor("海报框", { node(SkeletonPosterTag) }, { node("subject_cover_placeholder") }),
            ),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 海报卡骨架与真实卡同高_同海报框() = assertPosterCardGeometry(fontScale = 1f)

    @Test
    fun 字体放大两倍时海报卡骨架与真实卡同高_同海报框() = assertPosterCardGeometry(fontScale = 2f)

    // ---- EP 按钮：占位与真按钮的布局尺寸 ----

    private fun assertEpPlaceholderGeometry(fontScale: Float) {
        setContent(fontScale) {
            if (showReal) {
                EpButton(state = nextState, onNext = {}, onMarkFinished = {}, showTarget = false, subjectType = SubjectType.ANIME, modifier = Modifier.testTag(EP))
            } else {
                EpButtonPlaceholder(showTarget = false, Modifier.testTag(EP))
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(SkeletonAnchor("EP 按钮", { node(EP) }, { node(EP) })),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun EP占位与真按钮布局高一致() = assertEpPlaceholderGeometry(fontScale = 1f)

    @Test
    fun 字体放大两倍时EP占位与真按钮布局高一致() = assertEpPlaceholderGeometry(fontScale = 2f)

    private companion object {
        const val ROW = "geometry_row"
        const val CARD = "geometry_card"
        const val EP = "geometry_ep"
    }
}
