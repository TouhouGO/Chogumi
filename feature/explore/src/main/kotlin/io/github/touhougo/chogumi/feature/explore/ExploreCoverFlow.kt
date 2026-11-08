package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkPrelude
import io.github.touhougo.chogumi.core.ui.collection.posterCoverShape
import io.github.touhougo.chogumi.core.ui.collection.posterMarkGesture
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * 封面流（原型 `.flow`）：封面横排、互相叠一点，正中那张正对着人，两边的按离中心的距离侧转、缩小、压暗（只转、缩，不裁封面）；
 * 横滑停在某一张的正中，一开始停在第 [FLOW_START] + 1 张（两边都有封面）。下面一行是正中那张的标题与「年份、分数」。
 *
 * 侧转、缩小、压暗与前后叠放每帧在绘制与摆放阶段按位置算（[flowDistance]），滑动时不重组、前后顺序不跳。
 * 点正中那张进详情（由它长出详情页）；点侧边那张只把它转到正中——侧转是旋转，转着的封面不能当长出详情页的起点。
 * 长按正中那张是收藏环；长按侧边那张先把它转正再开环（[PosterMarkPrelude]）。停在哪张跟着这一批作品存下（[rememberShelfListState]），
 * 进详情再回来缩回的还是那一张。[subjects] 为 null 时画同样几何的骨架。
 */
@Composable
internal fun CoverFlow(subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    if (subjects == null) {
        CoverFlowFrame(count = FLOW_SKELETON_COUNT, state = rememberLazyListState(FLOW_START), scrollable = false) {
            SubjectPosterSkeletonBox(Modifier.width(FlowItemWidth))
        }
        FlowCaptionFrame {
            SkeletonText(MaterialTheme.typography.titleMedium, widthFraction = 0.4f)
            SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.25f)
        }
        return
    }
    val state = rememberShelfListState(worksKeyOf(subjects), initialIndex = min(FLOW_START, subjects.lastIndex))
    val centered by remember(state) { derivedStateOf { state.centeredItem() } }
    CoverFlowFrame(count = subjects.size, state = state, scrollable = true) { index ->
        FlowCover(subjects[index], index, state, isCentered = { centered == index }, onSubjectClick)
    }
    val subject = subjects[centered.coerceIn(0, subjects.lastIndex)]
    FlowCaptionFrame {
        Text(
            text = displayNameOf(subject),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 没有年份与分数也留着这一行：换到正中的那张时说明不跳高
        val meta = yearAndScoreParts(subject)
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        if (meta.isEmpty()) {
            Text(text = "", style = MaterialTheme.typography.bodyMedium)
        } else {
            MetaText(meta, style = MaterialTheme.typography.bodyMedium, color = secondary)
        }
    }
}

/**
 * 封面流的横排（真卡与骨架共用）：两边各留到第一张、最后一张也能停在正中，项叠 [FlowOverlap]，吸附在正中。
 * 每一项在摆放阶段按离中心的距离定前后（离中心越近越靠上），绘制阶段侧转、缩小。
 */
@Composable
private fun CoverFlowFrame(
    count: Int,
    state: LazyListState,
    scrollable: Boolean,
    item: @Composable (index: Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("explore_cover_flow")) {
        LazyRow(
            state = state,
            userScrollEnabled = scrollable,
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
            contentPadding = PaddingValues(start = (maxWidth - FlowItemWidth) / 2, end = (maxWidth - FlowItemWidth) / 2, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(-FlowOverlap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(count) { index ->
                Box(
                    Modifier
                        // 前后：离中心越近越靠上（摆放阶段读位置，滑动不重组）
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                placeable.place(0, 0, zIndex = -abs(state.flowDistance(index)))
                            }
                        }
                        .graphicsLayer {
                            val distance = state.flowDistance(index)
                            val far = min(abs(distance), FLOW_FAR)
                            rotationY = (-distance * FLOW_DEGREES_PER_STEP).coerceIn(-FLOW_MAX_DEGREES, FLOW_MAX_DEGREES)
                            scaleX = 1f - far * FLOW_SHRINK_PER_STEP
                            scaleY = scaleX
                            cameraDistance = FLOW_CAMERA_DISTANCE * density
                        },
                ) { item(index) }
            }
        }
    }
}

/** 封面流里的一张：整张可点、可长按标记；侧边的点了只转到正中，长按先转正再开环。 */
@Composable
private fun FlowCover(
    subject: SubjectBo,
    index: Int,
    state: LazyListState,
    isCentered: () -> Boolean,
    onSubjectClick: (Int) -> Unit,
) {
    val motion = LocalAppMotion.current
    val scope = rememberCoroutineScope()
    val mark = rememberPosterMark(subject.id, subject.type)
    val interaction = remember { MutableInteractionSource() }
    val shape = posterCoverShape(mark, interaction, FlowCorner)
    val prelude = remember(state, index, motion) {
        PosterMarkPrelude(pending = { !isCentered() }, complete = { motion.scrollToItem(state, index) })
    }
    PressArea(
        onClick = {
            if (isCentered()) onSubjectClick(subject.id) else scope.launch { motion.scrollToItem(state, index) }
        },
        shape = shape,
        interactionSource = interaction,
        modifier = Modifier.width(FlowItemWidth).posterMarkGesture(mark, prelude),
    ) {
        SubjectPoster(
            url = subject.coverUrl,
            description = displayNameOf(subject),
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            mark = mark,
        ) {
            // 侧边的压暗（原型 `brightness`）：在封面里画一层 scrim，透明度按离中心的距离，绘制阶段读
            val scrim = MaterialTheme.colorScheme.scrim
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = min(abs(state.flowDistance(index)), FLOW_FAR) * FLOW_DIM_PER_STEP }
                    .background(scrim),
            )
        }
    }
}

/** 封面流下面那一行说明的框（真说明与骨架共用）：居中，左右各让 32（原型 `.flowcap`）。 */
@Composable
private fun FlowCaptionFrame(content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 12.dp),
    ) { content() }
}

/**
 * 第 [index] 项离正中有几步（一步 = 一项的宽减去叠着的那一截）：正中是 0，右边为正。不在屏上的项按很远算。
 * 只在绘制与摆放阶段读（读的是滚动位置，滑动途中每帧都变）。
 */
private fun LazyListState.flowDistance(index: Int): Float {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return FLOW_FAR
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val step = item.size + info.mainAxisItemSpacing
    return if (step <= 0) 0f else (item.offset + item.size / 2f - center) / step
}

/** 此刻离正中最近的那一项（说明、点击、长按按它）。 */
private fun LazyListState.centeredItem(): Int =
    layoutInfo.visibleItemsInfo.minByOrNull { abs(flowDistance(it.index)) }?.index ?: firstVisibleItemIndex

/** 一开始停在第几张（从 0 数）：两边都有封面（原型 `FLOW_START`）。 */
private const val FLOW_START = 2

/** 骨架画几张：正中一张、两边各两张。 */
private const val FLOW_SKELETON_COUNT = 5

/** 封面宽（原型 `.fi`）。 */
private val FlowItemWidth = 140.dp

/** 相邻两张叠着的宽度（原型 `.fi` 的左右各 −16）。 */
private val FlowOverlap = 32.dp

/** 封面圆角：与海报同一档 8；长按标记的按住变形要 Dp 值。 */
private val FlowCorner = 8.dp

/** 每离中心一步侧转几度、最多转几度（原型的 42、55）。我的设计决定（not in M3）。 */
private const val FLOW_DEGREES_PER_STEP = 42f
private const val FLOW_MAX_DEGREES = 55f

/** 每离中心一步缩小多少、压暗多少，两步以外不再变（原型的 .13、.16、2）。我的设计决定（not in M3）。 */
private const val FLOW_SHRINK_PER_STEP = 0.13f
private const val FLOW_DIM_PER_STEP = 0.16f
private const val FLOW_FAR = 2f

/** 透视的远近（每 dp 的倍数，与原型 900px 的透视大致相当）。我的设计决定（not in M3）。 */
private const val FLOW_CAMERA_DISTANCE = 12f
