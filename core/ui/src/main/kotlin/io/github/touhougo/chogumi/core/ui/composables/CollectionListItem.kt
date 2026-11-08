package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.CoverPressBlock
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 列表行海报的宽度；高度由 [SubjectPoster] 按 [ChogumiDimens.CoverRatio] 推出。
 * 骨架（[CollectionListItemSkeleton]）经 [CollectionListItemFrame] 取同一个值。
 */
private val CollectionRowPosterWidth = 80.dp

/**
 * 进度条所在槽位的高度：官方两种进度条里**较高的一种**（波浪，`component-tokens.md`
 * LinearProgressIndicator.WaveHeight 10dp）。直线进度条（TrackThickness 4dp）在槽里垂直居中。
 *
 * 槽位高度恒定，是因为连载中的条目章节一到，进度条会从直线翻成波浪，同时进度计数从加载变已知：
 * 若槽位随进度条变高，文字列高度一变，行内垂直居中的标题就会跟着挪。
 */
private val ProgressBarSlotHeight = WavyProgressIndicatorDefaults.LinearContainerHeight

/** 骨架里进度条占位的粗细：直线进度条的轨道粗细（`component-tokens.md` LinearProgressIndicator.TrackThickness）。 */
private val ProgressTrackThickness = 4.dp

/**
 * 列表行的**框架**——真实行（[CollectionListItem]）与骨架（[CollectionListItemSkeleton]）共用的唯一出处：
 * 外层内边距、海报宽与比例、列间距、标题字阶、EP 槽位的结构都只写在这里，
 * 两边只往槽里填内容，所以几何不会各抄一份而跑偏。
 *
 * - [poster]：收到框架定好的宽度 modifier，高度由海报自己按比例推；
 * - [title]：收到框架定的标题字阶，真实行写 `Text`，骨架写 [SkeletonText]；
 * - [details]：标题下方的内容（见 [CollectionRowDetails]）；
 * - [epSlot]：非 null 才留 EP 槽位（是否留由 [EpisodeTracking.reservesEpSlot] 唯一决定，调用方算好），
 *   槽位尺寸取 [epButtonFootprint]（紧凑形态：所有状态同宽），与按钮、占位三者一致。
 */
@Composable
internal fun CollectionListItemFrame(
    poster: @Composable (Modifier) -> Unit,
    title: @Composable (TextStyle) -> Unit,
    details: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    epSlot: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        poster(Modifier.width(CollectionRowPosterWidth))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            title(MaterialTheme.typography.bodyLarge)
            details()
        }
        if (epSlot != null) {
            Box(Modifier.epButtonFootprint(showTarget = false), contentAlignment = Alignment.Center) { epSlot() }
        }
    }
}

/**
 * 标题下方的内容——真实行与骨架共用：带进度的类型画进度文字加进度条，其余类型画类型名。
 * 骨架传 [ProgressCount.Loading]，就是真实行等待逐集状态时画的那一份，换成 [ProgressCount.Known] 时
 * 文字列高度不变（进度文字行同为 bodySmall 上下各 8dp，进度条槽位恒高 [ProgressBarSlotHeight]）。
 */
@Composable
internal fun ColumnScope.CollectionRowDetails(
    subjectType: SubjectType,
    progressCount: ProgressCount,
    ongoing: Boolean,
) {
    if (!EpisodeTracking.hasProgress(subjectType)) {
        Text(
            stringResource(subjectType.labelRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    when (progressCount) {
        ProgressCount.Loading -> {
            SkeletonText(
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
                widthFraction = 0.4f,
            )
            ProgressBarSlot {
                SkeletonBox(Modifier.fillMaxWidth().height(ProgressTrackThickness), CircleShape)
            }
        }
        is ProgressCount.Known -> {
            collectionProgressLabel(progressCount)?.let { label ->
                CollectionProgressText(progressCount, label)
            }
            ProgressBarSlot {
                if (progressCount.total > 0) {
                    val target = (progressCount.current.toFloat() / progressCount.total).coerceIn(0f, 1f)
                    // 进度条跟着弹过去，不是一帧跳到新位置
                    val fraction by animateFloatAsState(target, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "CollectionProgress")
                    if (ongoing) {
                        LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        ProgressCount.None -> Unit
    }
}

/**
 * 进度行只排一行：完整句按实际字体与可用宽度放不下时，视觉改画纯数字；读屏始终只念带单位的完整句。
 */
@Composable
private fun CollectionProgressText(count: ProgressCount.Known, fullLabel: String) {
    val style = MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    val shortLabel = collectionProgressNumbers(count)
    BoxWithConstraints(
        Modifier
            .padding(vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = fullLabel },
    ) {
        val availableWidth = constraints.maxWidth
        val fullFits = availableWidth == Constraints.Infinity ||
            measurer.measure(fullLabel, style = style, maxLines = 1, softWrap = false).size.width <= availableWidth
        // 进度变了只有看过的数字在原位翻一格（多了往上翻、少了往下翻）：点「+」的反馈落在眼睛本来在看的数字上。
        RollingText(
            text = if (fullFits) fullLabel else shortLabel,
            order = count.current,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressBarSlot(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(ProgressBarSlotHeight),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * 首页与进度共用，进度写入由 EpButton 承载。
 *
 * 进度条按原型（`phase1.html` 的 `.prog`）区分：**连载中**（[ongoing]）画 M3 官方
 * [LinearWavyProgressIndicator]（波浪沿轨道流动），其余画直线 [LinearProgressIndicator]。
 * 两者的粗细、间隙、终点圆点都取官方默认（= `component-tokens.md` 的 LinearProgressIndicator 表）。
 *
 * 行的几何（内边距、海报、列间距、EP 槽位）来自 [CollectionListItemFrame]，与骨架同源。
 */
@Composable
fun CollectionListItem(
    item: UserCollectionBo,
    onClick: () -> Unit,
    /** 长按：打开这部的收藏面板（读屏念「编辑收藏」）。 */
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 条目进度计数，唯一判定点是 [EpisodeTracking.progressCount]，一律由调用方算好传入。
     * 故意不给默认值：缺省值只会在这里再长出第二处判定，且动画/三次元缺了逐集表会默默停在骨架上。
     */
    progressCount: ProgressCount,
    epButtonState: EpButtonState = EpButtonState.Empty,
    onNextEpisode: () -> Unit = {},
    onMarkFinished: () -> Unit = {},
    pendingVisual: EpPendingVisual? = null,
    /** 连载中（判定唯一经 `EpisodeTracking.isOngoing`，由调用方算好传入）：进度条画波浪。 */
    ongoing: Boolean = false,
) {
    val subject = item.subject
    // 点行里任何地方都由封面长出详情页
    CoverPressBlock { presses ->
        CollectionListItemFrame(
            modifier = modifier.then(presses).combinedClickable(onClick = onClick, onLongClick = onEdit, onLongClickLabel = stringResource(R.string.edit_collection)),
            poster = { posterModifier -> SubjectPoster(subject?.coverUrl, posterModifier) },
            title = { style ->
                Text(
                    subject?.let { displayNameOf(it) } ?: "#${item.subjectId}",
                    style = style,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            details = { CollectionRowDetails(item.subjectType, progressCount, ongoing) },
            // 槽位只由类型与收藏状态决定（[EpisodeTracking.reservesEpSlot]，第一帧就知道）：章节加载前后行内布局不变。
            // 槽位里的内容换态（占位 → 按钮 / 留空）只淡入淡出，不改宽度；首帧不播。
            // 同属「占位」「非占位」的状态之间（如下一集换了一集）不换内容，按钮自己用 animateContentSize 过渡。
            epSlot = if (EpisodeTracking.reservesEpSlot(item.subjectType, item.type)) {
                {
                    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    AnimatedContent(
                        targetState = epButtonState,
                        contentKey = { it is EpButtonState.Loading },
                        transitionSpec = {
                            fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec)
                        },
                        label = "EpButtonSlot",
                    ) { state ->
                        EpButton(
                            state = state,
                            onNext = onNextEpisode,
                            onMarkFinished = onMarkFinished,
                            // 行里已写「看过 N / M 集」，按钮只画「+」
                            showTarget = false,
                            subjectType = item.subjectType,
                            pending = pendingVisual,
                        )
                    }
                }
            } else {
                null
            },
        )
    }
}
