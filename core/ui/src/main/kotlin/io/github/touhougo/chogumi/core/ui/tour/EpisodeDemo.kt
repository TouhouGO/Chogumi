package io.github.touhougo.chogumi.core.ui.tour

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.businessDateOf
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeCellKind
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.ui.composables.EpisodeHeadFrame
import io.github.touhougo.chogumi.core.ui.composables.EpisodeTileRows
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.LocalNow
import io.github.touhougo.chogumi.core.ui.composables.NoJoin
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.TileJoin
import io.github.touhougo.chogumi.core.ui.composables.TileLabel
import io.github.touhougo.chogumi.core.ui.composables.TileNumber
import io.github.touhougo.chogumi.core.ui.composables.episodeTileShape
import io.github.touhougo.chogumi.core.ui.composables.gridColumnCount
import io.github.touhougo.chogumi.core.ui.composables.joinsOf
import io.github.touhougo.chogumi.core.ui.composables.numberEmphasized
import io.github.touhougo.chogumi.core.ui.composables.rememberMorphingShape
import io.github.touhougo.chogumi.core.ui.composables.tileBounds
import io.github.touhougo.chogumi.core.ui.composables.tileContent
import io.github.touhougo.chogumi.core.ui.composables.tileFace
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** 方格上面留的空：演示台顶上不贴着格子（设计决定，not in M3）。 */
private val GridTop = 24.dp

/** 方格画两行；一开始看到第 3 集，播到第一行再多两集。 */
private const val GridRows = 2
private const val StartWatched = 3
private const val AiredBeyondFirstRow = 2

/** 先点第 4 集标到那里，再按住第 6 集拉出它的面板（序号从 0 起）。 */
private const val TapIndex = 3
private const val HoldIndex = 5

private const val RestMillis = 700L
private const val TapMillis = 120L
private const val BetweenMillis = 900L
private const val SheetMillis = 2200L

/**
 * 第二页：点第 4 格，看过的一段长到那里、下一集挪到第 5 格；再按住第 6 格，压暗、单集面板从底下升起，
 * 上面是这一集的标题与评论。格子是 [tileFace] 的样子、[EpisodeTileRows] 的排法、[GridCell.EpisodeTile] 的列数，
 * 连段取 [EpisodeTracking.bandsOf]；面板是 [TourSheet]。
 */
@Composable
internal fun EpisodeDemo(playing: Boolean, modifier: Modifier = Modifier) {
    TourStage(modifier) { stage ->
        val area = stage.size
        val density = LocalDensity.current
        val motion = MaterialTheme.motionScheme
        val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
        val finger = remember { TourFinger() }
        var watched by remember { mutableIntStateOf(StartWatched) }
        var pressedIndex by remember { mutableStateOf<Int?>(null) }
        val sheet = remember { TourSheetState() }

        val side = with(density) { ChogumiDimens.ScreenPadding.roundToPx() }
        val top = with(density) { GridTop.roundToPx() }
        val gap = with(density) { GridCell.EpisodeTile.spacing.roundToPx() }
        val gridWidth = area.width.roundToInt() - side * 2
        val columns = gridColumnCount(gridWidth, with(density) { GridCell.EpisodeTile.minWidth.roundToPx() }, gap)
        val count = columns * GridRows
        val today = businessDateOf(LocalNow.current.value)
        val aired = columns + AiredBeyondFirstRow
        val episodes = remember(count, aired, today) {
            (1..count).map { number ->
                EpisodeBo(
                    id = number, subjectId = 0, type = EpisodeType.NORMAL, name = "", nameCn = "", sort = number.toDouble(),
                    airdate = (if (number <= aired) today else today.plusDays(1)).toString(),
                )
            }
        }
        val watches = (1..watched).associateWith { EpisodeStatus.DONE }
        val rows = EpisodeTracking.episodeGrid(episodes, watches, today, selectedRange = 0, columns = columns).rows
        val kinds = rows.flatMap { row -> row.cells.map { it.kind } }
        val joins = rows.flatMap { row -> joinsOf(row.cells.size, row.bands) }
        fun centerOf(index: Int): Offset = tileBounds(index, columns, gridWidth, gap).center.let { Offset(it.x + side.toFloat(), it.y + top.toFloat()) }

        LaunchedEffect(playing) {
            finger.reset()
            watched = StartWatched
            pressedIndex = null
            sheet.reset()
            if (!playing) return@LaunchedEffect
            while (true) {
                delay(RestMillis)
                finger.press(centerOf(TapIndex))
                pressedIndex = TapIndex
                delay(TapMillis)
                finger.lift()
                pressedIndex = null
                watched = TapIndex + 1
                delay(BetweenMillis)
                finger.press(centerOf(HoldIndex))
                pressedIndex = HoldIndex
                delay(longPressMillis)
                finger.lift()
                pressedIndex = null
                sheet.open(motion)
                delay(SheetMillis)
                sheet.close(motion)
                watched = StartWatched
            }
        }

        EpisodeTileRows(columns, Modifier.padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, top = GridTop)) {
            kinds.forEachIndexed { index, kind ->
                DemoTile(number = index + 1, kind = kind, join = joins.getOrElse(index) { NoJoin }, pressed = pressedIndex == index)
            }
        }
        TourSheet(sheet) { DemoEpisodeSheet() }
        FingerDot(finger)
    }
}

/** 一格：静止时的面来自 [tileFace]，种类变了淡过去；按住时收成 small 圆角，与方格里的格子同一段变形。 */
@Composable
private fun DemoTile(number: Int, kind: EpisodeCellKind, join: TileJoin, pressed: Boolean) {
    val shapes = MaterialTheme.shapes
    val motion = MaterialTheme.motionScheme
    val target = shapes.episodeTileShape(kind, join, pressed)
    val shape = rememberMorphingShape(target, motion.fastSpatialSpec())
    Crossfade(kind, animationSpec = motion.defaultEffectsSpec(), label = "tour_tile") { shown ->
        Box(Modifier.fillMaxSize().tileFace(shown, shape), contentAlignment = Alignment.Center) {
            TileLabel(number = {
                TileNumber(
                    text = number.toString(),
                    emphasized = shown.numberEmphasized,
                    color = MaterialTheme.colorScheme.tileContent(shown),
                )
            })
        }
    }
}

/**
 * 单集面板里的内容：头部是单集面板头部的框架 [EpisodeHeadFrame]（字画成骨架、右边同一个回复按钮），下面是评论楼层（[DemoFloor]）。
 * 面板本身是 [TourSheet]。
 */
@Composable
private fun DemoEpisodeSheet() {
    EpisodeHeadFrame(
        number = { SkeletonText(it, widthFraction = 0.25f) },
        title = { SkeletonText(it, widthFraction = 0.7f) },
        airdate = { SkeletonText(it, widthFraction = 0.45f) },
        onReply = {},
    )
    repeat(DemoFloorCount) { DemoFloor() }
}

/** 面板里的楼层数：演示台里露出的那几层。 */
private const val DemoFloorCount = 3
