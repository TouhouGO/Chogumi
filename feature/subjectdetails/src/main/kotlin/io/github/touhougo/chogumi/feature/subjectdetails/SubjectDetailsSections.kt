package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.subjectVolumes
import io.github.touhougo.chogumi.core.model.trackDiscs
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileGrid
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileGridSkeleton
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SectionHeaderSkeleton
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SkeletonTextDigits
import io.github.touhougo.chogumi.core.ui.composables.numberColumnWidth

/** 区块标题行的 testTag（几何测试据此对位骨架阶段与内容阶段的标题行）。 */
internal const val DetailsSectionHeaderTag = "details_section_header"

/**
 * 带标题的异步列表区块：三态由 [state] 决定（[sectionStateOf] 唯一判定），
 * [SectionState.EMPTY] 时连标题一起不渲染。给了 [onSeeAll] 且有内容时，标题行尾显示「全部」（原型 `.sech .more`）；
 * [count] 跟在标题后面（「关联作品 19」）。
 */
@Composable
internal fun ListSection(
    /** 区块标题；null 表示不画标题（如章节 tab：tab 名已经说明了内容）。 */
    title: String?,
    state: SectionState,
    error: Failure?,
    onRetry: () -> Unit,
    skeleton: @Composable () -> Unit,
    count: Int? = null,
    onSeeAll: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (state == SectionState.EMPTY) return
    if (title != null) {
        // 有「全部」入口的区块，内容一到标题行尾就多出一枚文字按钮（布局高度含 48dp 触控区，
        // 比标题文字高）。骨架阶段的标题行用 SectionHeaderSkeleton 把这块空间留出来，否则内容一出现标题行被撑高。
        if (state == SectionState.LOADING) {
            SectionHeaderSkeleton(
                modifier = Modifier.testTag(DetailsSectionHeaderTag),
                hasTrailingAction = onSeeAll != null,
            )
        } else {
            SectionHeader(
                title = title,
                modifier = Modifier.testTag(DetailsSectionHeaderTag),
                count = count?.takeIf { state == SectionState.CONTENT },
                trailingText = stringResource(R.string.detail_all),
                onTrailingClick = onSeeAll?.takeIf { state == SectionState.CONTENT },
            )
        }
    }
    when (state) {
        SectionState.LOADING -> skeleton()
        SectionState.ERROR -> error?.let { RetryError(it, onRetry = onRetry, Modifier.fillMaxWidth(), compact = true) }
        SectionState.CONTENT -> content()
        SectionState.EMPTY -> Unit
    }
}

// ---------------------------------------------------------------------------
// 2. EPISODES 区块：章节方格
// ---------------------------------------------------------------------------

/**
 * 章节 tab 的完整内容：章节方格（点一集 = 看到这一集，乐观更新、停手合并提交；特别篇点一下只切换这一集）。
 * 不画区块标题、也不放 EP 按钮：进到这个 tab 就知道是章节，方格本身就是进度的输入与展示（产品决定）。
 * 进度写入由 `SubjectDetailsViewModel` 交给 `EpTapCoordinator`。
 *
 * 传 [episodes]（**完整**列表，含 SP/OP/ED）而非只有正片：方格下方要列出特别篇，
 * 正片/非正片的切分由 [EpisodeTracking] 负责（界面不写 `type == 0`）。
 * [expectedEpisodes]：条目资料里的话数，章节还没到时骨架据此决定留不留分段导航条。
 */
@Composable
internal fun EpisodesSection(
    subjectType: SubjectType,
    episodes: AsyncList<EpisodeBo>,
    watchStates: Map<Int, EpisodeStatus>,
    today: java.time.LocalDate,
    pendingCommit: EpPendingVisual?,
    expectedEpisodes: Int?,
    offerMarkDone: Boolean,
    onWatchUpTo: Gated<(EpisodeProgressTarget) -> Unit>,
    onToggleSpecial: Gated<(EpisodeBo) -> Unit>,
    onMarkDone: Gated<() -> Unit>,
    onEpisodeLongPress: (EpisodeBo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(episodes.load) { sectionStateOf(episodes.load) { !EpisodeTracking.hasTiles(it) } }
    Column(modifier = modifier.fillMaxWidth()) {
        // 零集条目：tab 照常在（可见性只看条目类型），里面说一句没有章节
        if (state == SectionState.EMPTY) {
            EmptyLine(stringResource(R.string.detail_episodes_empty))
            return@Column
        }
        ListSection(
            title = null,
            state = state,
            error = episodes.error,
            onRetry = episodes.onRetry,
            skeleton = { EpisodeTileGridSkeleton(withNavigator = EpisodeTracking.splitsIntoRanges(expectedEpisodes ?: 0)) },
        ) {
            EpisodeTileGrid(
                subjectType = subjectType,
                episodes = episodes.items,
                watchStates = watchStates,
                today = today,
                pendingCommit = pendingCommit,
                offerMarkDone = offerMarkDone,
                onWatchUpTo = onWatchUpTo,
                onToggleSpecial = onToggleSpecial,
                onMarkDone = onMarkDone,
                onEpisodeLongPress = onEpisodeLongPress,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 第 2 个 tab：单行本（书籍）与曲目（音乐）
// ---------------------------------------------------------------------------

/**
 * 单行本 tab：单行本关联（[subjectVolumes]，按卷号）排成封面网格，点了进那一卷的条目页。
 * 数据与关联作品同一路（关联接口），三态跟着它走；确认为空时画一行说明（这个 tab 对书籍恒可见）。
 * 阅读进度在底部工具栏里改。
 */
@Composable
internal fun VolumesSection(
    relations: AsyncList<SubjectRelationBo>,
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val volumes = remember(relations.items) { subjectVolumes(relations.items).map { RelationCardItem(it, label = null) } }
    val state = sectionStateOf(relations.load) { volumes.isEmpty() }
    Column(modifier = modifier.fillMaxWidth()) {
        if (state == SectionState.EMPTY) {
            EmptyLine(stringResource(R.string.detail_volumes_empty))
            return@Column
        }
        ListSection(
            title = null,
            state = state,
            error = relations.error,
            onRetry = relations.onRetry,
            skeleton = { RelationCardGridSkeleton() },
        ) {
            RelationCardGrid(items = volumes, onSubjectClick = onSubjectClick)
        }
    }
}

/**
 * 曲目 tab：曲目就是音乐条目的章节，按碟分组（[trackDiscs]），不显示播出日期。
 * 只有一张碟（或接口没给碟号）时不画碟标题。每首是分段列表的一项：曲序 + 曲名。
 */
internal fun LazyListScope.tracksSection(episodes: AsyncList<EpisodeBo>) {
    val discs = trackDiscs(episodes.items)
    when (sectionStateOf(episodes.load) { discs.isEmpty() }) {
        SectionState.LOADING -> item(key = "tracks-skeleton", contentType = "tracks-skeleton") { TracksSkeleton() }
        SectionState.ERROR -> item(key = "tracks-error", contentType = "tracks-feedback") {
            episodes.error?.let { RetryError(it, onRetry = episodes.onRetry, Modifier.fillMaxWidth(), compact = true) }
        }
        SectionState.EMPTY -> item(key = "tracks-empty", contentType = "tracks-feedback") {
            EmptyLine(stringResource(R.string.detail_tracks_empty))
        }
        SectionState.CONTENT -> discs.forEach { disc ->
            if (discs.size > 1) {
                item(key = "track-disc-${disc.disc}", contentType = "track-disc") {
                    SectionHeader(title = stringResource(R.string.detail_track_disc, disc.disc))
                }
            }
            val maxLabelLength = disc.tracks.maxOfOrNull { formatEpisodeSort(it.sort).length } ?: 1
            itemsIndexed(disc.tracks, key = { _, track -> "track-${track.id}" }, contentType = { _, _ -> "track" }) { index, track ->
                TrackRow(
                    index = index,
                    count = disc.tracks.size,
                    numberWidth = numberColumnWidth(maxLabelLength, MaterialTheme.typography.labelLarge, TRACK_NUMBER_WIDTH),
                    modifier = trackRowPadding(index),
                    number = { numberModifier ->
                        Text(
                            text = formatEpisodeSort(track.sort),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = numberModifier,
                        )
                    },
                ) {
                    Text(
                        text = displayNameOf(track),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 曲目 tab 的骨架：与真实曲目行同一个框架（[TrackRow]、[trackRowPadding]），占 [SKELETON_TRACKS] 行。 */
@Composable
private fun TracksSkeleton() {
    Column {
        repeat(SKELETON_TRACKS) { index ->
            TrackRow(
                index = index,
                count = SKELETON_TRACKS,
                numberWidth = TRACK_NUMBER_WIDTH,
                modifier = trackRowPadding(index),
                number = { numberModifier ->
                    SkeletonText(MaterialTheme.typography.labelLarge, numberModifier, sample = SkeletonTextDigits)
                },
            ) {
                SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.6f)
            }
        }
    }
}

/** 一首曲目在分段列表里的位置（真实与骨架共用）：左右 16dp，除第一首外上方留分段间距。 */
private fun trackRowPadding(index: Int): Modifier =
    Modifier.padding(start = 16.dp, end = 16.dp, top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap)

/** 一首曲目（真实与骨架共用）：分段列表的一项，曲序列宽 [numberWidth]（同一张碟各行一样宽，按最长的曲序实测），后面是曲名。 */
@Composable
internal fun TrackRow(
    index: Int,
    count: Int,
    numberWidth: Dp,
    modifier: Modifier = Modifier,
    number: @Composable (Modifier) -> Unit,
    title: @Composable () -> Unit,
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = modifier.fillMaxWidth(),
        leadingContent = { number(Modifier.width(numberWidth)) },
        content = title,
    )
}

/** 曲目骨架占几行。我的设计决定（not in M3）。 */
private const val SKELETON_TRACKS = 6

/** 曲序列宽 28（原型曲目项 `width:28px`；设计决定，not in M3）。 */
private val TRACK_NUMBER_WIDTH = 28.dp

