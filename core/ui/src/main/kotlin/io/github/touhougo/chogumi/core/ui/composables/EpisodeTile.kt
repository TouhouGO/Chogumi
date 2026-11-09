package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpisodeCell
import io.github.touhougo.chogumi.core.model.EpisodeCellKind
import io.github.touhougo.chogumi.core.model.EpisodeTap
import io.github.touhougo.chogumi.core.model.WatchChange
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* 章节方格里的一格（[EpisodeTileGrid]）：样子、按下与点击，以及逐格过场、看完回荡、轻抖与倒计时环这些动效。 */

/**
 * 点一格改了几集时的逐格过场：变了的格子先保持原来的种类，再按顺序每 [CASCADE_STAGGER_MILLIS] 放出一格、换上新样子——
 * 标上的从前往后、取消的从后往前，两队同时走，最多错开 [CASCADE_STAGGER_MAX] 格，之后的一起放出（设计稿的取值，not in M3）。
 * 只由点击发起（[start]）：别处来的进度变化（提交回读、写入失败回滚）直接过渡、不排队。
 */
internal class TileCascade(private val scope: CoroutineScope) {
    private val held = mutableStateMapOf<Int, EpisodeCellKind>()
    private var job: Job? = null

    /** 这一格此刻显示的种类：还没轮到就是原来的种类。 */
    fun kindOf(cell: EpisodeCell): EpisodeCellKind = held[cell.episode.id] ?: cell.kind

    /**
     * 写入 [tap] 之前排好过场：[cells] 是这一页的格子，每格怎么变只读 [EpisodeTap.changes]（按全部正片算好，
     * 不在这一页的变化不排）。返回过场走完要多久（毫秒）。
     */
    fun start(cells: List<EpisodeCell>, tap: EpisodeTap, stepMillis: Long): Long {
        job?.cancel()
        held.clear()
        val changes = cells.mapNotNull { cell -> tap.changes[cell.episode.id]?.let { cell to it } }
        val fills = changes.filter { it.second == WatchChange.FILL }.map { it.first }
        val clears = changes.filter { it.second == WatchChange.CLEAR }.map { it.first }.asReversed()
        (fills + clears).forEach { held[it.episode.id] = it.kind }
        val steps = maxOf(fills.size, clears.size)
        job = scope.launch {
            for (step in 0 until steps) {
                if (step in 1..CASCADE_STAGGER_MAX) delay(stepMillis)
                fills.getOrNull(step)?.let { held.remove(it.episode.id) }
                clears.getOrNull(step)?.let { held.remove(it.episode.id) }
            }
        }
        return minOf(maxOf(steps - 1, 0), CASCADE_STAGGER_MAX) * stepMillis
    }
}

/**
 * 看完时刻：所有格子从最后一格往回荡一遍（设计稿的取值，not in M3）。由看完的那一下点击预约（[schedule]），
 * 到时候正片真的全部看过了才荡——点击可能被登录门拦下，写入也可能被撤回。
 */
internal class TileWave(private val scope: CoroutineScope) {
    /** 每荡一遍加一。格子记下自己出现时的值，之后进入组合的格子不重放已经荡过的那一遍。 */
    var generation by mutableIntStateOf(0)
        private set
    private var job: Job? = null

    fun schedule(afterMillis: Long, completed: () -> Boolean) {
        job?.cancel()
        job = scope.launch {
            delay(afterMillis)
            if (completed()) generation++
        }
    }
}

/**
 * 一格。样子只随种类、连段与确认预览变：
 * - 形状：连段内相邻的角取 extraSmall、两端 medium；下一集是圆；按下变 small（buttons.md：按下变方）——
 *   四个角各自以 spatial 弹簧变形（[rememberMorphingShape]）。
 * - 进度（看过、看到这里）用 primary 系；未播出是「还没有」的灰；网页上单独标的想看用 tertiaryFixedDim 底、
 *   onTertiaryFixed 字——一眼看得出是另一种颜色，深浅色主题下色调不变（color.md：Fixed 系是贴在 surface 上、
 *   深浅色同一色调的填色；tertiaryContainer 在本主题的深色下是全板最亮的一格，会压过「看到这里」，淡色底又发灰、
 *   与未播出混）；抛弃是描边空位加一道斜线。
 * - 颜色以 effects 弹簧过渡；「不填色」是看过色的透明版，从空位填成看过时不经过深灰。
 * - 等确认时：会标上的格子先填半透明的看过色，会取消的格子变淡，点的那一格加粗描边。
 * - 看过与否变了：标上弹一下（从 [POP_GAIN_FROM] 以 expressive fast spatial 回到原大，会过冲）、取消收一下（从 [POP_LOSE_FROM]
 *   以 standard fast spatial，不回弹）。比的是这一格自己上一刻显示的样子，刚进入组合时不弹。
 * - [countdown]：待提交时绕格子一圈的倒计时环，只在画的时候读，每帧只重画。
 * - [entranceDelayMillis]：刚切到这一段，等这么久后淡入。
 * - [waveGeneration] 变大（看完时刻）：等 [waveDelayMillis] 后荡一下。
 * - 点了不能标的格子（未播出）：轻轻一抖。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EpisodeTile(
    cell: EpisodeCell,
    join: TileJoin,
    preview: WatchChange?,
    target: Boolean,
    /** 读屏念的名字（「第 12 集」「SP 1 …」）。 */
    name: String,
    countdown: FloatState?,
    entranceDelayMillis: Long?,
    waveGeneration: Int,
    waveDelayMillis: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val motion = MaterialTheme.motionScheme
    val appMotion = LocalAppMotion.current
    val kind = cell.kind
    val tileScope = rememberCoroutineScope()

    // 标上弹一下、取消收一下：只在这一格显示的「看过与否」真的变了时
    val watched = kind == EpisodeCellKind.WATCHED || kind == EpisodeCellKind.CURRENT
    val pop = remember { Animatable(1f) }
    var shownWatched by remember { mutableStateOf(watched) }
    // 标上弹一下（expressive，会过冲）；取消看过时收一下，几何变化不回弹（standard）
    val gainSpec = appMotion.expressive.fastSpatialSpec<Float>()
    val loseSpec = motion.fastSpatialSpec<Float>()
    LaunchedEffect(watched) {
        if (watched == shownWatched) return@LaunchedEffect
        shownWatched = watched
        pop.snapTo(if (watched) POP_GAIN_FROM else POP_LOSE_FROM)
        pop.animateTo(1f, if (watched) gainSpec else loseSpec)
    }
    // 切段进场
    val appear = remember { Animatable(if (entranceDelayMillis != null) 0f else 1f) }
    val appearScale = remember { Animatable(if (entranceDelayMillis != null) ENTRANCE_FROM_SCALE else 1f) }
    val appearEffects = motion.defaultEffectsSpec<Float>()
    LaunchedEffect(Unit) {
        val wait = entranceDelayMillis ?: return@LaunchedEffect
        delay(wait)
        launch { appear.animateTo(1f, appearEffects) }
        appearScale.animateTo(1f, loseSpec)
    }
    // 看完时刻：比这一格出现时更新的那一遍才荡
    val bump = remember { Animatable(1f) }
    val seenWave = remember { waveGeneration }
    val waveSpec = appMotion.expressive.defaultSpatialSpec<Float>()
    LaunchedEffect(waveGeneration) {
        if (waveGeneration <= seenWave) return@LaunchedEffect
        delay(waveDelayMillis)
        bump.animateTo(1f, waveSpec, initialVelocity = WAVE_VELOCITY)
    }
    // 点了不能标的格子轻轻一抖：随这一下点击开始，再点一次从此刻接着抖
    val shake = remember { Animatable(0f) }
    val shakeVelocity = with(LocalDensity.current) { -SHAKE_VELOCITY.toPx() }
    val shakeSpec = appMotion.shake
    val tileClick = {
        if (!cell.markable) tileScope.launch { shake.animateTo(0f, shakeSpec, initialVelocity = shakeVelocity) }
        onClick()
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val targetShape: CornerBasedShape = when {
        pressed -> shapes.small
        kind == EpisodeCellKind.NEXT -> CircleShape
        else -> shapes.tileShape(join)
    }
    val shape = rememberMorphingShape(targetShape, MaterialTheme.motionScheme.fastSpatialSpec())

    val container = if (preview == WatchChange.FILL) colors.primaryContainer.copy(alpha = PREVIEW_FILL_ALPHA) else colors.tileContainer(kind)
    val content = colors.tileContent(kind)
    val (strokeWidth, strokeColor) = when {
        // 点的那一格是「看到这里」（实心 primary）时描边换成 onPrimary，否则看不出来
        target -> TargetStroke to if (kind == EpisodeCellKind.CURRENT) colors.onPrimary else colors.primary
        kind == EpisodeCellKind.NEXT -> NextStroke to colors.primary
        preview == WatchChange.FILL -> TileStroke to colors.primary.copy(alpha = PREVIEW_FILL_STROKE_ALPHA)
        kind.outlined -> TileStroke to colors.outlineVariant
        else -> TileStroke to colors.outlineVariant.copy(alpha = 0f)
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val animatedContainer by animateColorAsState(container, effects, label = "episodeTileContainer")
    val animatedContent by animateColorAsState(content, effects, label = "episodeTileContent")
    val animatedStroke by animateColorAsState(strokeColor, effects, label = "episodeTileStroke")
    val stroke by animateDpAsState(strokeWidth, MaterialTheme.motionScheme.fastSpatialSpec(), label = "episodeTileStrokeWidth")
    val slash = colors.outlineVariant
    val todayDot = colors.tertiary

    val state = stringResource(
        when (kind) {
            EpisodeCellKind.WATCHED -> R.string.episode_state_watched
            EpisodeCellKind.CURRENT -> R.string.episode_state_current
            EpisodeCellKind.NEXT -> R.string.episode_state_next
            EpisodeCellKind.AIRED -> R.string.episode_state_aired
            EpisodeCellKind.WISH -> R.string.episode_state_wish
            EpisodeCellKind.DROPPED -> R.string.episode_state_dropped
            EpisodeCellKind.UNAIRED -> R.string.episode_state_unaired
        },
    )
    val locale = LocalConfiguration.current.locales[0]
    // 小点只是颜色，格子里的播出日又常被省掉：读屏要念到「今天播出」或哪天播
    val airdate = cell.airdate
    val airing = when {
        cell.airsToday -> stringResource(R.string.episode_tile_airs_today)
        kind == EpisodeCellKind.UNAIRED && airdate != null ->
            stringResource(R.string.episode_tile_airs_on, formatMonthDay(airdate, locale))
        else -> null
    }
    val description = if (airing == null) {
        stringResource(R.string.episode_tile_desc, name, state)
    } else {
        stringResource(R.string.episode_tile_desc_airing, name, state, airing)
    }

    val ringColor = animatedContent.copy(alpha = RING_ALPHA)

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = appear.value * if (preview == WatchChange.CLEAR) PREVIEW_CLEAR_ALPHA else 1f
                val scale = pop.value * bump.value * appearScale.value
                scaleX = scale
                scaleY = scale
                translationX = shake.value
            }
            .clip(shape)
            .background(animatedContainer)
            .border(stroke, animatedStroke, shape)
            .drawBehind { if (kind == EpisodeCellKind.DROPPED) drawDroppedSlash(slash) }
            // 倒计时环：修饰符一直在（只是没有倒计时时不画），格子成为「看到这里」时不改修饰符链
            .drawWithCache {
                val ring = countdown?.let { countdownRing(shape.createOutline(size, layoutDirection, this), RING_INSET.toPx() + RING_STROKE.toPx() / 2) }
                val measure = PathMeasure()
                ring?.let { measure.setPath(it, false) }
                val segment = Path()
                val ringStroke = Stroke(width = RING_STROKE.toPx(), cap = StrokeCap.Round)
                onDrawWithContent {
                    drawContent()
                    val left = countdown?.floatValue ?: 0f
                    if (ring != null && left > 0f) {
                        // 剩多少画多少：从顶边正中起顺时针，随时间从尾巴一侧收回
                        segment.reset()
                        measure.getSegment((1f - left) * measure.length, measure.length, segment, true)
                        drawPath(segment, ringColor, style = ringStroke)
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onLongClickLabel = stringResource(R.string.episode_tile_long_press),
                onClick = tileClick,
                onLongClick = onLongClick,
            )
            // 读屏只念「第 12 集，看过」：格子里的集号与日期不单独念（清掉的是这之后的语义，点击与长按留着）
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        TileLabel(
            number = {
                TileNumber(
                    text = formatEpisodeSort(cell.episode.sort),
                    emphasized = kind == EpisodeCellKind.CURRENT || kind == EpisodeCellKind.NEXT,
                    color = animatedContent,
                )
            },
            // 一个月内要播的写播出日；两行放不下时只留集号，点一下会提示播出日
            below = cell.upcomingAirdate?.let { date ->
                { Text(text = formatShortDate(date), style = MaterialTheme.typography.labelSmall, color = animatedContent, maxLines = 1) }
            },
        )
        if (cell.airsToday) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(TodayDotInset)
                    .size(TodayDotSize)
                    .background(todayDot, CircleShape),
            )
        }
    }
}

/**
 * 倒计时环的路径：[outline]（格子此刻的形状）向里缩 [inset] 后的一圈，从顶边正中起顺时针——
 * 剩多少画多少时，环从尾巴一侧收回，像钟表走完一圈。圆角半径跟着缩，与格子同心。
 */
private fun countdownRing(outline: Outline, inset: Float): Path {
    val bounds = outline.bounds.deflate(inset)
    val rounded = (outline as? Outline.Rounded)?.roundRect
    fun radius(corner: CornerRadius?): Float = ((corner?.x ?: 0f) - inset).coerceIn(0f, minOf(bounds.width, bounds.height) / 2)
    val topLeft = radius(rounded?.topLeftCornerRadius)
    val topRight = radius(rounded?.topRightCornerRadius)
    val bottomRight = radius(rounded?.bottomRightCornerRadius)
    val bottomLeft = radius(rounded?.bottomLeftCornerRadius)
    return Path().apply {
        moveTo(bounds.center.x, bounds.top)
        lineTo(bounds.right - topRight, bounds.top)
        arcTo(Rect(bounds.right - topRight * 2, bounds.top, bounds.right, bounds.top + topRight * 2), -90f, 90f, false)
        lineTo(bounds.right, bounds.bottom - bottomRight)
        arcTo(Rect(bounds.right - bottomRight * 2, bounds.bottom - bottomRight * 2, bounds.right, bounds.bottom), 0f, 90f, false)
        lineTo(bounds.left + bottomLeft, bounds.bottom)
        arcTo(Rect(bounds.left, bounds.bottom - bottomLeft * 2, bounds.left + bottomLeft * 2, bounds.bottom), 90f, 90f, false)
        lineTo(bounds.left, bounds.top + topLeft)
        arcTo(Rect(bounds.left, bounds.top, bounds.left + topLeft * 2, bounds.top + topLeft * 2), 180f, 90f, false)
        close()
    }
}

/** 标上时从多大弹回原大、取消时从多大收回（设计稿，not in M3）。 */
private const val POP_GAIN_FROM = 0.84f
private const val POP_LOSE_FROM = 0.92f

/** 逐格过场：每格错开多久、最多错开几格（设计稿，not in M3；M3 没有错开的取值，motion.md）。 */
internal const val CASCADE_STAGGER_MILLIS = 20L
private const val CASCADE_STAGGER_MAX = 12

/** 切段进场：每格错开多久、最多错开几格、从多大淡入（设计稿，not in M3）。 */
internal const val ENTRANCE_STAGGER_MILLIS = 6L
internal const val ENTRANCE_STAGGER_MAX = 40
private const val ENTRANCE_FROM_SCALE = 0.9f

/**
 * 看完时刻：过场走完再停多久开始荡、每格错开多久（设计稿，not in M3），以及荡一下的初速度——
 * 原大的格子带着这个速度交给 expressive default spatial 弹簧，先冲到约 1.1 倍再回到原大（设计稿「放大到 1.1」）。
 */
internal const val WAVE_PAUSE_MILLIS = 300L
internal const val WAVE_STAGGER_MILLIS = 15L
private const val WAVE_VELOCITY = 4.6f

/** 点了不能标的格子轻轻一抖的初速度：先往左约 4dp，来回两三下停住（设计稿 ±4dp；弹簧取 [LocalAppMotion] 的 shake）。 */
private val SHAKE_VELOCITY = 220.dp

/** 倒计时环：线宽、离格子边缘多远、不透明度（设计稿，not in M3）。 */
private val RING_STROKE = 2.5.dp
private val RING_INSET = 3.dp
private const val RING_ALPHA = 0.75f

/** 格子描边：下一集 2dp，等确认时点的那一格 3dp（设计稿，not in M3；已播未看、抛弃的 1dp 见 [TileStroke]）。 */
internal val NextStroke = 2.dp
private val TargetStroke = 3.dp

/** 等确认时的预览：会标上的格子填 55% 的看过色、描 35% 的 primary 边，会取消的格子整体 40%（设计稿，not in M3）。 */
private const val PREVIEW_FILL_ALPHA = 0.55f
private const val PREVIEW_FILL_STROKE_ALPHA = 0.35f
private const val PREVIEW_CLEAR_ALPHA = 0.4f

/** 今天播出的小点：直径 6dp、离右上角 8dp（设计稿，not in M3）。 */
private val TodayDotSize = 6.dp
private val TodayDotInset = 8.dp
