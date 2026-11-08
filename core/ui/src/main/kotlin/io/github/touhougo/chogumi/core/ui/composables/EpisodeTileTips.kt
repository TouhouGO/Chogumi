package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpisodeCell
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTap
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 章节方格上的提示（[EpisodeTileGrid]）：大跳确认的富提示、未播出的普通提示，它们指着哪一格，
 * 以及「按在别处就放弃」的触摸记录。
 */

/**
 * 一块方格加上指着其中一格的提示（大跳确认或未播出）。提示由 [AnchoredTip] 开在自己的窗口里，浮在版面之上：
 * 方格只有一行时提示也不撑高方格、不挤动下面的内容。朝哪边开、怎么对齐见 [tipPosition]。
 * 消失的那一段仍按原来的格子摆放。
 * 每一格都拿到同一种修饰符记下「这一下按在了哪一格」（[reportTileTouch]），按在提示所指以外的地方就放弃提示；
 * 修饰符不随谁是提示所指而增减——按住途中改了格子的修饰符链，这一下手势会被取消（长按会丢）。
 */
@Composable
internal fun TileBoard(
    columns: Int,
    cells: List<EpisodeCell>,
    tips: TileTips,
    modifier: Modifier,
    tile: @Composable (index: Int, cell: EpisodeCell, touchModifier: Modifier) -> Unit,
) {
    val anchorId = tips.anchorId
    val anchorIndex = anchorId?.let { id -> cells.indexOfFirst { it.episode.id == id } } ?: -1
    val confirm = tips.confirm
    val unaired = tips.unaired
    val shown = when {
        anchorIndex < 0 -> null
        confirm != null -> BoardTooltip(anchorIndex, TipKind.Rich) { JumpConfirmTooltip(confirm.tap) }
        unaired != null -> BoardTooltip(anchorIndex, TipKind.Plain) { UnairedTooltip(unaired) }
        else -> null
    }
    // 提示退场时要接着画、接着按原来的格子摆放：记下最后一个（只在组合里读写，不是界面状态）
    val last = remember { LastTooltip() }
    if (shown != null) last.tooltip = shown
    val gap = with(LocalDensity.current) { GridCell.EpisodeTile.spacing.roundToPx() }
    Box(modifier) {
        EpisodeTileRows(columns, Modifier) {
            cells.forEachIndexed { index, cell ->
                key(cell.episode.id) {
                    tile(index, cell, Modifier.reportTileTouch(tips, cell.episode.id))
                }
            }
        }
        last.tooltip?.let { placing ->
            AnchoredTip(
                visible = shown != null,
                kind = placing.kind,
                anchor = { board -> tileBounds(placing.anchorIndex, columns, board.width, gap) },
                content = placing.content,
            )
        }
    }
}

/** 方格上的一个提示：指着第 [anchorIndex] 格，[kind] 是大跳确认（富提示）还是未播出（普通提示）。 */
private class BoardTooltip(val anchorIndex: Int, val kind: TipKind, val content: @Composable () -> Unit)

private class LastTooltip {
    var tooltip: BoardTooltip? = null
}

/** 等确认的一次大跳：点的是哪一格、点了要做什么（[EpisodeTracking.tapOf]），按哪一份进度算的。 */
internal class PendingJump(val episodeId: Int, val tap: EpisodeTap, val basis: Map<Int, EpisodeStatus>)

/**
 * 方格上的提示：等确认的大跳（常驻富提示）或未播出提示（普通提示），同一时刻最多一个（feedback.md：一次只显示一个提示）。
 * 正片与特别篇共用这一份。
 */
internal class TileTips(private val scope: CoroutineScope) {
    var confirm: PendingJump? by mutableStateOf(null)
        private set
    var unaired: EpisodeCell? by mutableStateOf(null)
        private set

    /** 这一下按在了哪一格（见 [dismissTipsOnOtherTouch]）。只在指针事件里读写，不是界面状态。 */
    var pressedId: Int? = null

    private var timer: Job? = null

    /** 提示指着哪一集。 */
    val anchorId: Int? get() = confirm?.episodeId ?: unaired?.episode?.id

    fun ask(jump: PendingJump) {
        dismiss()
        confirm = jump
    }

    /** 普通提示是短暂的（feedback.md：1.5 秒后消失）：计时随这一下点击开始，再点一次重新计时。 */
    fun showUnaired(cell: EpisodeCell) {
        // 没有播出日就没有话可说：不弹空气泡
        if (cell.airdate == null) return
        dismiss()
        unaired = cell
        timer = scope.launch {
            delay(PLAIN_TOOLTIP_MILLIS)
            unaired = null
        }
    }

    fun dismiss() {
        timer?.cancel()
        timer = null
        confirm = null
        unaired = null
    }

    fun forgetStale(current: Map<Int, EpisodeStatus>) {
        if (confirm?.basis != current) confirm = null
    }
}

/**
 * 按在提示所指的那一格以外的任何地方（别的格子、提示本身、导航条、卡片……）都放弃提示。
 * 一次按下的指针事件先由外向里走 Initial、再由里向外走 Main：外层在 Initial 清掉记号，按到的那一格在 Initial 记上，
 * 外层在 Main 看记号——这时格子还没收到抬起，点击还没发生。
 */
internal fun Modifier.dismissTipsOnOtherTouch(tips: TileTips): Modifier = pointerInput(tips) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        tips.pressedId = null
        awaitPointerEvent(PointerEventPass.Main)
        if (tips.pressedId == null || tips.pressedId != tips.anchorId) tips.dismiss()
    }
}

private fun Modifier.reportTileTouch(tips: TileTips, episodeId: Int): Modifier = pointerInput(tips, episodeId) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        tips.pressedId = episodeId
    }
}

/** 大跳确认：富提示卡（[RichTipCard]），小标题 + 说明，不放按钮——再点一次同一格才生效。 */
@Composable
private fun JumpConfirmTooltip(tap: EpisodeTap) {
    val targetSort = tap.targetSort
    val title = when {
        targetSort == null -> stringResource(R.string.episode_summary_none)
        tap.forward -> stringResource(R.string.episode_jump_forward_title, formatEpisodeSort(targetSort))
        else -> stringResource(R.string.episode_jump_back_title, formatEpisodeSort(targetSort))
    }
    val text = when {
        tap.cleared == 0 -> pluralStringResource(R.plurals.episode_jump_fill_text, tap.filled, tap.filled)
        tap.filled == 0 -> pluralStringResource(R.plurals.episode_jump_clear_text, tap.cleared, tap.cleared)
        else -> stringResource(R.string.episode_jump_mixed_text, tap.filled, tap.cleared)
    }
    RichTipCard(maxWidth = RichTooltipMaxWidth) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        // 改动与「怎么确认」各占一行：拼成一句会在「再点一」处折行
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.episode_jump_confirm_hint), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 未播出提示：普通提示（[PlainTip]），一行字说第几集哪天播出。 */
@Composable
private fun UnairedTooltip(cell: EpisodeCell) {
    val airdate = checkNotNull(cell.airdate) { "没有播出日的格子不会弹出未播出提示" }
    val locale = LocalConfiguration.current.locales[0]
    PlainTip(stringResource(R.string.episode_unaired_tip, formatEpisodeSort(cell.episode.sort), formatMonthDay(airdate, locale)))
}

// ---------------------------------------------------------------------------
// 提示的尺寸与停留时长
// ---------------------------------------------------------------------------

/** 富提示最宽多少（设计稿 236px；M3 只给内边距，not in M3）。 */
private val RichTooltipMaxWidth = 236.dp

/** 普通提示停留多久（feedback.md：1.5 秒后消失）。 */
private const val PLAIN_TOOLTIP_MILLIS = 1500L
