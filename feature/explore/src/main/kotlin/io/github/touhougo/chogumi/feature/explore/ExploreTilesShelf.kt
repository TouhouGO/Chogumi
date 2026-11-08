package io.github.touhougo.chogumi.feature.explore

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTile
import io.github.touhougo.chogumi.core.model.GamePlatform
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.TileDrawing
import io.github.touhougo.chogumi.core.model.TileLogo
import io.github.touhougo.chogumi.core.ui.composables.LogoImage
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.pressedCorner
import io.github.touhougo.chogumi.core.res.R

/**
 * 一条方块货架：标题 + 一行横滑的方块，一个词一块，点一块带那个词的条件进搜索页（原型 `.tiles`）；
 * 刻度尺画法是一把横拖的尺子，下面一行是尺子停在的那个词的作品（[DecadeRuler]）。
 * 方块一露面就请 [onShown] 去取它们要的数据（合集、代表作要那个词的作品，标志要公司的标志图，刻度尺只要停在的那一块的作品）；
 * 色块与平台的标志（随包）什么都不取。
 */
@Composable
internal fun TilesShelf(
    shelf: ExploreShelf.Tiles,
    typeContent: ExploreTypeContent,
    onShown: (List<ExploreTile>) -> Unit,
    onSubjectClick: (Int) -> Unit,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
    onArrange: () -> Unit,
) {
    val title = exploreTitleText(shelf.title)
    Column(Modifier.testTag("explore_shelf_${shelf.id}")) {
        // 画法的唯一分派（红线一：别处不再判断画法；整理里画法的缩略图也是它）
        when (shelf.drawing) {
            TileDrawing.RULER -> DecadeRuler(shelf, title, onArrange, typeContent, onShown, onSubjectClick, onOpenSearch)
            TileDrawing.COLOR -> TilesRow(shelf, title, onArrange, onShown, onOpenSearch) { index, tile, label, onClick ->
                ExploreTileCard(big = tile.big, label = label, style = exploreTileStyle(shelf.title), colorIndex = index, onClick = onClick)
            }
            TileDrawing.STACK -> {
                val works = rowWorks(shelf.tiles.map { typeContent.tileWorks(shelf.id, it) }, take = STACK_COVERS)
                TilesRow(shelf, title, onArrange, onShown, onOpenSearch) { index, _, label, onClick ->
                    StackTile(label, index, works[index], onClick)
                }
            }
            TileDrawing.COVER -> {
                val works = rowWorks(shelf.tiles.map { typeContent.tileWorks(shelf.id, it) }, take = 1)
                TilesRow(shelf, title, onArrange, onShown, onOpenSearch) { index, tile, label, onClick ->
                    CoverTile(label, tile.subtitle, index, works[index], onClick)
                }
            }
            TileDrawing.LOGO -> TilesRow(shelf, title, onArrange, onShown, onOpenSearch) { _, tile, label, onClick ->
                LogoTile(label, onClick) { shape, frame ->
                    when (val logo = tile.logo) {
                        is TileLogo.Platform -> LogoImage(logo.platform.logoRes(), description = null, shape = shape, modifier = frame)
                        is TileLogo.Company -> CompanyLogo(typeContent.logo(logo.personId), shape, frame)
                        null -> LogoImage(null, description = null, shape = shape, modifier = frame)
                    }
                }
            }
        }
    }
}

/**
 * 标题 + 一行横滑的方块（[tile] 画一块：第几块、哪一块、它的词、点了做什么）。货架组合出来时整行的方块都算露面：
 * 没滚到的方块货架不发请求；同一批重复报出由 ViewModel 去重。
 */
@Composable
private fun TilesRow(
    shelf: ExploreShelf.Tiles,
    title: String,
    /** 长按标题进整理。 */
    onArrange: () -> Unit,
    onShown: (List<ExploreTile>) -> Unit,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
    tile: @Composable (index: Int, tile: ExploreTile, label: String, onClick: () -> Unit) -> Unit,
) {
    LaunchedEffect(shelf.id, shelf.tiles) { onShown(shelf.tiles) }
    ShelfHeader(title = title, onArrange = onArrange)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("explore_tiles_row_${shelf.id}"),
    ) {
        itemsIndexed(shelf.tiles, key = { _, item -> item.label.key }) { index, item ->
            val label = exploreTileLabelText(item.label)
            tile(index, item, label) { onOpenSearch(item.criteria, label, false) }
        }
    }
}

/**
 * 合集：色块上叠这个词排在前面的三张小封面（同一行不重复，[rowWorks]），错开、各自转一点，整叠在色块下部左右居中（原型 `.tst`）。配色与色块同一套四色轮转（[exploreTileColors]）。
 * 作品还没到时封面是占位块，取不到、没有作品时只剩色块与词。整块点了进这个词，封面不单独可点。
 */
@Composable
private fun StackTile(label: String, colorIndex: Int, works: Load<List<SubjectBo>>, onClick: () -> Unit) {
    val colors = exploreTileColors(colorIndex)
    val interaction = remember { MutableInteractionSource() }
    val corner by pressedCorner(interaction, resting = STACK_CORNER_RESTING, pressed = STACK_CORNER_PRESSED)
    PressArea(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = colors.container,
        contentColor = colors.content,
        interactionSource = interaction,
        modifier = Modifier.size(width = StackTileWidth, height = StackTileHeight),
    ) {
        Box {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
            )
            val covers: List<SubjectBo?> = when (works) {
                Load.Pending -> List(STACK_COVERS) { null }
                is Load.Ready -> works.value.take(STACK_COVERS)
                is Load.Failed -> emptyList()
            }
            // 第一部压在最上面、最靠左：从最后一张画起；整叠在方块里左右居中
            covers.withIndex().reversed().forEach { (place, subject) ->
                val coverModifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .offset(x = stackCoverOffset(place, covers.size))
                    .graphicsLayer { rotationZ = STACK_ROTATIONS[place] }
                    .width(StackCoverWidth)
                    .testTag(STACK_COVER_TAG)
                if (subject == null) {
                    SubjectPosterSkeletonBox(coverModifier)
                } else {
                    SubjectPoster(url = subject.coverUrl, modifier = coverModifier, shadowElevation = 2.dp)
                }
            }
        }
    }
}

/**
 * 代表作：一张横卡，底是这块代表作（这个词按热度排在最前、这一行别的块没用过的那部，[rowWorks]）的封面卡底
 * （[CoverBackdrop] 的深色罩：模糊的封面铺满、从左往右渐暗），左下角是白字的词，下面一行是它的中文名（[ExploreTile.subtitle]），
 * 右上角立着同一部作品清晰的封面。作品还没到、取不到时底是这块轮转配色（[exploreTileColors]），还在等时右上是封面占位。
 * 整块点了进这个词。尺寸出自原型（设计决定，not in M3）。
 */
@Composable
private fun CoverTile(label: String, subtitle: String?, colorIndex: Int, works: Load<List<SubjectBo>>, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    val tonal = exploreTileColors(colorIndex)
    val cover = works.valueOrNull()?.firstOrNull()?.coverUrl
    val (container, content) = if (cover != null) DarkCoverCardColors else CoverCardColors(tonal.container, tonal.content)
    PressArea(
        onClick = onClick,
        shape = shape,
        color = container,
        contentColor = content,
        modifier = Modifier.size(width = CoverTileWidth, height = CoverTileHeight),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (cover != null) CoverBackdrop(cover, CoverVeil.DARK, container, shape, Modifier.matchParentSize())
            when {
                cover != null -> SubjectPoster(
                    url = cover,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).width(CoverTilePosterWidth),
                    shadowElevation = 4.dp,
                )
                works == Load.Pending -> SubjectPosterSkeletonBox(
                    Modifier.align(Alignment.TopEnd).padding(12.dp).width(CoverTilePosterWidth),
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 14.dp, end = 12.dp + CoverTilePosterWidth + 12.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = CoverTileSubtitleAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 一行合集或代表作要画的作品（唯一判定）：整行一起定。每块按顺序取自己排在前面的作品里、前面的块没用过的 [take] 部，
 * 同一部作品不在一行里出现两次；自己的作品全被前面的块用掉时退回自己的前几部，方块不空。
 * 有一块还在等时整行都按等待画：后到的块不会让先画出来的封面换掉。取不到的块照旧是取不到。
 */
internal fun rowWorks(loads: List<Load<List<SubjectBo>>>, take: Int): List<Load<List<SubjectBo>>> {
    if (loads.any { it == Load.Pending }) return loads.map { Load.Pending }
    val used = mutableSetOf<Int>()
    return loads.map { load ->
        if (load !is Load.Ready) return@map load
        val fresh = load.value.filter { it.id !in used }.take(take)
        val picked = fresh.ifEmpty { load.value.take(take) }
        used += picked.map { it.id }
        load.copy(value = picked)
    }
}

/**
 * 标志：标志放在框里，名字在下面一行（原型 `.tlg`）。整块（框加名字）点了进这个词。
 * 框里画什么由 [frame] 给（公司的标志图、平台的标志），它拿到框的形状与尺寸。
 */
@Composable
private fun LogoTile(label: String, onClick: () -> Unit, frame: @Composable (shape: Shape, modifier: Modifier) -> Unit) {
    val shape = MaterialTheme.shapes.medium
    PressArea(onClick = onClick, shape = shape, modifier = Modifier.width(LogoWidth)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            frame(shape, Modifier.size(width = LogoWidth, height = LogoHeight))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 平台的官方标志（随包，来源见 docs/third-party-notices.md）。 */
@DrawableRes
private fun GamePlatform.logoRes(): Int = when (this) {
    GamePlatform.SWITCH -> R.drawable.platform_nintendo_switch
    GamePlatform.PS5 -> R.drawable.platform_ps5
    GamePlatform.PC -> R.drawable.platform_windows
    GamePlatform.XBOX_SERIES -> R.drawable.platform_xbox
    GamePlatform.ANDROID -> R.drawable.platform_android
}

/** 公司的标志图：还没到时框是占位块；没有图、取不到时框里是图片图标（[LogoImage]）。 */
@Composable
private fun CompanyLogo(logo: Load<String?>, shape: Shape, modifier: Modifier) {
    when (logo) {
        Load.Pending -> SkeletonBox(modifier, shape)
        is Load.Ready -> LogoImage(logo.value, description = null, shape = shape, modifier = modifier)
        is Load.Failed -> LogoImage(null, description = null, shape = shape, modifier = modifier)
    }
}

/** 合集方块的尺寸（原型 `.tst`）。 */
private val StackTileWidth = 156.dp
private val StackTileHeight = 118.dp

/** 合集方块的圆角：静息 16（原型 `.tst`），按下圆一档，与色块按下 12 → 20 同一个幅度。我的设计决定（not in M3）。 */
private val STACK_CORNER_RESTING = 16.dp
private val STACK_CORNER_PRESSED = 24.dp

/** 合集方块叠几张封面。 */
private const val STACK_COVERS = 3

/** 叠着的封面宽、相邻两张错开多少（原型 `.tst .cv` 与 `j*26`）。 */
private val StackCoverWidth = 50.dp
private val StackCoverStep = 26.dp

/**
 * 第 [place] 张封面（第一部是 0，在最左）离方块正中的横向偏移：[count] 张整叠左右居中，左右留白相等，
 * 只有一两张时也一样居中。我的设计决定（not in M3）。
 */
private fun stackCoverOffset(place: Int, count: Int): Dp = StackCoverStep * (place - (count - 1) / 2f)

/** 合集方块里每张小封面的测试标记。 */
internal const val STACK_COVER_TAG = "explore_stack_cover"

/** 三张封面各转几度，按第一部到第三部（原型 `[7,-1,-9]` 倒过来）。我的设计决定（not in M3）。 */
private val STACK_ROTATIONS = floatArrayOf(-9f, -1f, 7f)

/** 代表作横卡的尺寸、右上角清晰封面的宽（原型 B 方案）。我的设计决定（not in M3）。 */
private val CoverTileWidth = 236.dp
private val CoverTileHeight = 124.dp
private val CoverTilePosterWidth = 75.dp

/** 代表作横卡的托底：从左往右由深到浅，托住左下角的白字；中文名一行再淡一档。我的设计决定（not in M3）。 */
private const val CoverTileSubtitleAlpha = 0.85f

/** 标志框的尺寸（原型 `.tlg .lg`）。 */
private val LogoWidth = 132.dp
private val LogoHeight = 80.dp
