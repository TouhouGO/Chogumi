package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EP_COMMIT_DELAY_NANOS
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeBand
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeCell
import io.github.touhougo.chogumi.core.model.EpisodeCellKind
import io.github.touhougo.chogumi.core.model.EpisodeGrid
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTap
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import java.time.LocalDate

/** 正片方格每一格的 testTag（几何测试据此与骨架的第一格对位）。 */
const val EpisodeTileTag = "episode_tile"

/** 骨架里每一格的 testTag。 */
const val SkeletonEpisodeTileTag = "skeleton_episode_tile"

/**
 * 章节 tab 的全部内容：顶上一行「看到第几集」，
 * 超过一百集时一条分段导航，然后是正片方格、图例、看完时的「标为看过」建议，最后是特别篇方格。
 *
 * 进度用格子表达：同一行里相邻的看过格连成一段，段尾是「看到这里」，整板唯一的圆形格是下一集。格子的种类、连段、
 * 点一格的意思全由 [EpisodeTracking] 的纯函数给出，这里只画；列数由 [GridCell.EpisodeTile] 按屏宽给出。
 *
 * ## 交互
 *
 * - 点一格：意思只由 [EpisodeTracking.tapOf] 判定（看到哪一集、标上几集、取消几集），写入只有 [onWatchUpTo] 一个出口。
 *   一次改动超过 [EpisodeTracking.WATCH_JUMP_CONFIRM_OVER] 集先不生效：会变的格子预览出来，旁边出一个常驻富提示，
 *   再点同一格才生效；碰了别处（别的格子、提示本身、导航条……）就放弃（feedback.md：常驻富提示点了才出现，碰别的就消失）。
 * - 点未播出的格子：只提示哪天播（[EpisodeCell.markable]），1.5 秒后消失。
 * - 点特别篇：只切换这一集看过与否（[onToggleSpecial]），不算进度。
 * - 长按：单集详情（[onEpisodeLongPress]）。
 * - [offerMarkDone]（调用方判：正片看完了、收藏还不是「看过」）时，方格下方出一张卡片建议标为看过（[onMarkDone]）。
 *
 * 两种提示同一时刻最多一个（feedback.md），都画在方格自己的版面里，跟着内容滚，不另开窗口。
 *
 * ## 动效（几何用 spatial、颜色与透明度用 effects，都取 `MaterialTheme.motionScheme`；错开的毫秒数是设计稿的取值）
 *
 * - 点一格改了几集：变了的格子按顺序一格一格换上新样子（[TileCascade]），标上的从前往后、取消的从后往前；
 *   标上的格子弹一下（expressive，会过冲），取消的收一下（standard，不回弹）。
 * - 停手到提交前（[pendingCommit]）：「看到这里」那一格绕一圈倒计时，与 EP 按钮读同一个提交时刻（[rememberCommitRemaining]）。
 * - 标完最后一集：所有格子从最后一格往回荡一遍（[TileWave]），只在这一下真的把正片看完时。
 * - 点未播出的格子：轻轻一抖，同时提示哪天播。
 * - 切到另一段：新一段的格子依次淡入。
 * - 提示淡入淡出、轻微缩放。
 * - 页面刚打开、别处来的进度变化（提交回读、写入失败回滚）：直接过渡颜色与形状，不排队、不弹。
 */
@Composable
fun EpisodeTileGrid(
    /** 条目类型：看完卡片上写「标为看过」的说法（[markDoneLabel]）。 */
    subjectType: SubjectType,
    episodes: List<EpisodeBo>,
    watchStates: Map<Int, EpisodeStatus>,
    today: LocalDate,
    pendingCommit: EpPendingVisual?,
    offerMarkDone: Boolean,
    onWatchUpTo: Gated<(EpisodeProgressTarget) -> Unit>,
    onToggleSpecial: Gated<(EpisodeBo) -> Unit>,
    onMarkDone: Gated<() -> Unit>,
    onEpisodeLongPress: (EpisodeBo) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 排序、解析日期的全量计算只在输入变了才重做：上千集的作品里一次要几毫秒，不能每次重组都算
    val mains = remember(episodes) { EpisodeTracking.mainEpisodes(episodes) }
    val scope = rememberCoroutineScope()
    val tips = remember(scope) { TileTips(scope) }
    // 等确认的大跳是按点下那一刻的进度算的：进度被别处改了（停手提交回读、写入失败回滚），这次确认作废
    LaunchedEffect(watchStates) { tips.forgetStale(watchStates) }
    val specialGroups = remember(episodes) { EpisodeTracking.specialGroups(episodes) }
    if (mains.isEmpty() && specialGroups.isEmpty()) return

    Column(modifier.fillMaxWidth().dismissTipsOnOtherTouch(tips)) {
        // 只有特别篇的条目（剧场版特典、OVA 合集……）：没有正片方格，只画特别篇
        if (mains.isNotEmpty()) {
            MainEpisodes(
                subjectType = subjectType,
                mains = mains,
                episodes = episodes,
                watchStates = watchStates,
                today = today,
                pendingCommit = pendingCommit,
                offerMarkDone = offerMarkDone,
                tips = tips,
                onWatchUpTo = onWatchUpTo,
                onMarkDone = onMarkDone,
                onEpisodeLongPress = onEpisodeLongPress,
            )
        }
        if (specialGroups.isNotEmpty()) {
            SectionHeader(title = stringResource(R.string.episode_specials_title))
            SpecialEpisodeTiles(
                groups = specialGroups,
                watchStates = watchStates,
                today = today,
                tips = tips,
                onToggle = onToggleSpecial,
                onEpisodeLongPress = onEpisodeLongPress,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/** 正片部分：摘要行、分段导航、正片方格、图例与看完时的「标为看过」建议。[mains] 不为空。 */
@Composable
private fun MainEpisodes(
    subjectType: SubjectType,
    mains: List<EpisodeBo>,
    episodes: List<EpisodeBo>,
    watchStates: Map<Int, EpisodeStatus>,
    today: LocalDate,
    pendingCommit: EpPendingVisual?,
    offerMarkDone: Boolean,
    tips: TileTips,
    onWatchUpTo: Gated<(EpisodeProgressTarget) -> Unit>,
    onMarkDone: Gated<() -> Unit>,
    onEpisodeLongPress: (EpisodeBo) -> Unit,
) {
    val defaultIndex = remember(mains, watchStates) { EpisodeTracking.defaultRangeIndex(EpisodeTracking.episodeRanges(mains), watchStates) }
    // 选中的段跟着这一份正片列表走：列表换了（离屏期间章节到齐、换了一季），回来时落在下一集所在的段，不恢复旧段
    val rangeKey = "${mains.first().id}:${mains.size}"
    var selectedRange by rememberSaveable(rangeKey, saver = rangeSaver(rangeKey)) { mutableIntStateOf(defaultIndex) }
    val scope = rememberCoroutineScope()
    val cascade = remember(scope) { TileCascade(scope) }
    val wave = remember(scope) { TileWave(scope) }
    // 切段后新一段的格子依次淡入：记下是切到了哪一段（页面刚打开时为空，不播）
    var enteringRange by remember { mutableStateOf<Int?>(null) }
    val summary = remember(episodes, watchStates, today) { EpisodeTracking.episodeSummary(episodes, watchStates, today) }

    EpisodeSummaryRow(summary)
    WithGridColumns(GridCell.EpisodeTile, Modifier.padding(horizontal = ChogumiDimens.ScreenPadding)) { columns ->
        val grid = remember(episodes, watchStates, today, selectedRange, columns) {
            EpisodeTracking.episodeGrid(episodes, watchStates, today, selectedRange, columns)
        }
        Column {
            if (grid.ranges.size > 1) {
                EpisodeRangeNavigator(
                    grid = grid,
                    onSelect = { range ->
                        tips.dismiss()
                        enteringRange = range
                        selectedRange = range
                    },
                )
            }
            MainEpisodeTiles(
                grid = grid,
                columns = columns,
                episodes = episodes,
                watchStates = watchStates,
                pendingCommit = pendingCommit,
                entering = enteringRange == grid.selectedRange,
                allWatched = summary.allWatched,
                tips = tips,
                cascade = cascade,
                wave = wave,
                onWatchUpTo = onWatchUpTo,
                onEpisodeLongPress = onEpisodeLongPress,
            )
            EpisodeTileLegend(grid)
        }
    }
    ExpandReveal(visible = offerMarkDone) {
        EpisodesFinishedCard(
            subjectType = subjectType,
            onMarkDone = onMarkDone,
            modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding).padding(top = 12.dp),
        )
    }
}

/**
 * 章节 tab 的骨架：与 [EpisodeTileGrid] 同一套框架（摘要行、导航条、方格的排法与留白都经同一组函数），
 * 占一季十二集的格子。[withNavigator]：条目的话数多到要分段（[EpisodeTracking.splitsIntoRanges]）时预留导航条，
 * 真实内容出现时方格不被顶下去。
 */
@Composable
fun EpisodeTileGridSkeleton(withNavigator: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        EpisodeSummaryFrame(
            title = { titleModifier -> SkeletonText(SummaryTitleStyle, titleModifier, widthFraction = 0.45f) },
            meta = { metaModifier -> SkeletonText(MaterialTheme.typography.bodySmall, metaModifier.width(SummaryMetaSkeletonWidth)) },
        )
        WithGridColumns(GridCell.EpisodeTile, Modifier.padding(horizontal = ChogumiDimens.ScreenPadding)) { columns ->
            Column {
                if (withNavigator) {
                    NavigatorFrame {
                        SkeletonBox(Modifier.fillMaxWidth().height(NavigatorSegmentHeight), shape = CircleShape)
                    }
                }
                EpisodeTileRows(columns, Modifier.padding(TilesPadding)) {
                    repeat(SKELETON_TILES) {
                        SkeletonBox(Modifier.testTag(SkeletonEpisodeTileTag), shape = MaterialTheme.shapes.medium)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 摘要行
// ---------------------------------------------------------------------------

/**
 * 方格的排法（真实与骨架共用）：每行 [columns] 格正方形，格距取 [GridCell.EpisodeTile]。
 * 格子的大小与位置只由 [tileBounds] 算，提示的定位也用它。
 */
@Composable
internal fun EpisodeTileRows(columns: Int, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = GridCell.EpisodeTile.spacing.roundToPx()
        val size = tileSize(constraints.maxWidth, columns, gap)
        val placeables = measurables.map { it.measure(Constraints.fixed(size, size)) }
        val rows = (placeables.size + columns - 1) / columns
        // 高度守约束（同 Column）：放在限高的容器里时从顶上排、往下超出，不被居中挪上去
        layout(constraints.maxWidth, constraints.constrainHeight(if (rows == 0) 0 else rows * size + (rows - 1) * gap)) {
            placeables.forEachIndexed { index, placeable ->
                val bounds = tileBounds(index, columns, constraints.maxWidth, gap)
                placeable.place(bounds.left, bounds.top)
            }
        }
    }
}

private fun tileSize(width: Int, columns: Int, gap: Int): Int = (width - gap * (columns - 1)) / columns

internal fun tileBounds(index: Int, columns: Int, width: Int, gap: Int): IntRect {
    val size = tileSize(width, columns, gap)
    val left = (index % columns) * (size + gap)
    val top = (index / columns) * (size + gap)
    return IntRect(left, top, left + size, top + size)
}

/** 选中的段连同正片列表的 key 一起存（[keyedListSaver]）。 */
private fun rangeSaver(key: String): Saver<MutableIntState, Any> = keyedListSaver(
    key = key,
    save = { listOf(it.intValue) },
    restore = { saved -> mutableIntStateOf(saved[0] as Int) },
)

/** 这一行每格在连段里的位置（[TileJoin]）：前面、后面是否还连着看过的格子。 */
private fun joinsOf(cells: Int, bands: List<EpisodeBand>): List<TileJoin> = (0 until cells).map { column ->
    val band = bands.firstOrNull { column in it.startColumn..it.endColumn }
    TileJoin(start = band != null && column > band.startColumn, end = band != null && column < band.endColumn)
}

/**
 * 正片方格：一页（分段时是当前这一段）。点一格的意思只问 [EpisodeTracking.tapOf]。
 *
 * 逐格过场中还没轮到的格子保持原来的种类（[TileCascade.kindOf]），连段也按此刻显示的种类连
 * （判定只在 [EpisodeTracking.bandsOf]），所以过场里连段是一格一格长出来的。
 */
@Composable
private fun MainEpisodeTiles(
    grid: EpisodeGrid,
    columns: Int,
    episodes: List<EpisodeBo>,
    watchStates: Map<Int, EpisodeStatus>,
    pendingCommit: EpPendingVisual?,
    /** 刚切到这一段：格子依次淡入。 */
    entering: Boolean,
    allWatched: Boolean,
    tips: TileTips,
    cascade: TileCascade,
    wave: TileWave,
    onWatchUpTo: Gated<(EpisodeProgressTarget) -> Unit>,
    onEpisodeLongPress: (EpisodeBo) -> Unit,
) {
    val appMotion = LocalAppMotion.current
    val cells = grid.rows.flatMap { it.cells }
    val shownRows = grid.rows.map { row -> row.cells.map(cascade::kindOf) }
    val shownKinds = shownRows.flatten()
    val joins = shownRows.flatMap { kinds -> joinsOf(kinds.size, EpisodeTracking.bandsOf(kinds)) }
    val pending = tips.confirm?.takeIf { it.basis == watchStates }
    val remaining = rememberCommitRemaining(pendingCommit?.commitAtNanos, EP_COMMIT_DELAY_NANOS)
    val currentAllWatched by rememberUpdatedState(allWatched)
    // 写入只有这一个出口：先排好逐格过场（点击事件里排，别处来的进度变化不排），再写
    val commit: (EpisodeTap) -> Unit = { tap ->
        tips.dismiss()
        val settleMillis = cascade.start(cells, tap, stepMillis = appMotion.stagger(CASCADE_STAGGER_MILLIS))
        if (tap.completes) wave.schedule(afterMillis = settleMillis + WAVE_PAUSE_MILLIS) { currentAllWatched }
        onWatchUpTo.action(tap.target)
    }
    TileBoard(columns, cells, tips, Modifier.padding(TilesPadding)) { index, cell, touchModifier ->
        val episode = cell.episode
        val shown = cell.copy(kind = shownKinds[index])
        EpisodeTile(
            cell = shown,
            join = joins[index],
            preview = pending?.tap?.changes?.get(episode.id),
            target = pending?.episodeId == episode.id,
            name = episodeProgressLabel(episode.sort),
            countdown = remaining.takeIf { pendingCommit != null && shown.kind == EpisodeCellKind.CURRENT },
            entranceDelayMillis = if (entering) appMotion.stagger(minOf(index, ENTRANCE_STAGGER_MAX) * ENTRANCE_STAGGER_MILLIS) else null,
            waveGeneration = wave.generation,
            waveDelayMillis = appMotion.stagger((cells.lastIndex - index) * WAVE_STAGGER_MILLIS),
            onClick = {
                // 读此刻的确认（不是组合时的那一份）：按在别处时它已经被放弃
                val waiting = tips.confirm?.takeIf { it.basis == watchStates }
                when {
                    waiting?.episodeId == episode.id -> commit(waiting.tap)
                    !cell.markable -> tips.showUnaired(cell)
                    else -> {
                        val tap = EpisodeTracking.tapOf(episodes, watchStates, episode)
                        if (tap.needsConfirm) tips.ask(PendingJump(episode.id, tap, watchStates)) else commit(tap)
                    }
                }
            },
            onLongClick = {
                tips.dismiss()
                onEpisodeLongPress(episode)
            },
            modifier = touchModifier.testTag(EpisodeTileTag),
        )
    }
}

/**
 * 特别篇方格（SP / OP / ED……按类型分组，分组与每格的样子由 [EpisodeTracking.specialGroups] /
 * [EpisodeTracking.specialCell] 给出）：与正片同一种格子与列宽，但不连段——特别篇互不连续，没有「看到第几集」。
 * 点一下只切换这一集看过与否（[onToggle]），读屏念完整文案（[specialEpisodeLabel]）。
 */
@Composable
private fun SpecialEpisodeTiles(
    groups: List<Pair<Int, List<EpisodeBo>>>,
    watchStates: Map<Int, EpisodeStatus>,
    today: LocalDate,
    tips: TileTips,
    onToggle: Gated<(EpisodeBo) -> Unit>,
    onEpisodeLongPress: (EpisodeBo) -> Unit,
    modifier: Modifier,
) {
    WithGridColumns(GridCell.EpisodeTile, modifier.padding(horizontal = ChogumiDimens.ScreenPadding)) { columns ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { (type, list) ->
                episodeTypeLabelRes(type)?.let { res ->
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val cells = list.map { EpisodeTracking.specialCell(it, watchStates, today) }
                TileBoard(columns, cells, tips, Modifier) { _, cell, touchModifier ->
                    EpisodeTile(
                        cell = cell,
                        join = NoJoin,
                        preview = null,
                        target = false,
                        name = specialEpisodeLabel(cell.episode),
                        countdown = null,
                        entranceDelayMillis = null,
                        waveGeneration = 0,
                        waveDelayMillis = 0,
                        onClick = {
                            if (cell.markable) {
                                tips.dismiss()
                                onToggle.action(cell.episode)
                            } else {
                                tips.showUnaired(cell)
                            }
                        },
                        onLongClick = {
                            tips.dismiss()
                            onEpisodeLongPress(cell.episode)
                        },
                        modifier = touchModifier,
                    )
                }
            }
        }
    }
}

/** 正片全部看完、收藏还不是「看过」：建议一键标为看过。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EpisodesFinishedCard(subjectType: SubjectType, onMarkDone: Gated<() -> Unit>, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        // 只有标题和按钮：大字号或英文下一行放不下时，按钮换到标题下面，不挤压文字
        FlowRow(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.episodes_finished_title), style = MaterialTheme.typography.titleSmallEmphasized)
            Button(onClick = onMarkDone.action) { Text(markDoneLabel(subjectType)) }
        }
    }
}

/** 骨架画几格：最常见的一季十二集。 */
private const val SKELETON_TILES = 12

/** 方格上下的留白（设计稿 `.grid` 的 padding）。 */
private val TilesPadding = PaddingValues(top = 12.dp, bottom = 8.dp)
