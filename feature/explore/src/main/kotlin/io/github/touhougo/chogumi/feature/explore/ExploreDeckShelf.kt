package io.github.touhougo.chogumi.feature.explore

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.subjectMetaParts
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.PosterMark
import io.github.touhougo.chogumi.core.ui.collection.posterCoverShape
import io.github.touhougo.chogumi.core.ui.collection.posterMarkGesture
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.common.navigation.CoverPressBlock
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectMetaText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * 卡堆（原型 `.deck`）：前 [DECK_SIZE] 部叠成一摞，露出最上面 [DECK_VISIBLE] 张，后面的一张比一张小、往右错开；
 * 右边是顶上那张的标题、元信息、分数与标签。最上面那张左右一甩（过了 [DeckThrowThreshold]）飞出去、插到最底下，下一张浮上来；
 * 没甩够弹回原位。手指一动就是甩卡，按住不动才是长按（收藏环，只认顶上那张）；点整张卡进顶上那部的详情，由它的封面长出。
 * 甩卡途中不响应点击。顶上是哪张跟着这一批作品存下（[rememberShelfIndex]），进详情再回来缩回的还是那一张。
 * 读屏另有「下一张」的动作。[subjects] 为 null 时画同样几何的骨架。
 */
@Composable
internal fun DeckShelf(subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    if (subjects.isNullOrEmpty()) {
        DeckFrame(
            pile = {
                for (depth in DECK_VISIBLE - 1 downTo 0) {
                    SubjectPosterSkeletonBox(Modifier.width(DeckCardWidth).deckPlacement({ depth.toFloat() }, throwX = null), MaterialTheme.shapes.small)
                }
            },
            info = {
                SkeletonText(MaterialTheme.typography.titleMedium, lines = 2, widthFraction = 0.9f)
                SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.6f)
            },
        )
        return
    }
    val cards = subjects.take(DECK_SIZE)
    val count = cards.size
    val key = worksKeyOf(cards)
    val top = rememberShelfIndex(key)
    val topIndex = top.intValue.mod(count)
    // 每张卡在摞里的位置（0 = 顶上，越大越靠后）：只在甩卡这个事件里动（docs/code-standards.md「一次性反馈属于事件，不属于状态」）
    val depths = remember(key) { List(count) { card -> Animatable(depthOf(card, topIndex, count).toFloat()) } }
    val throwX = remember(key) { Animatable(0f) }
    var throwing by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val density = LocalDensity.current
    val thresholdPx = with(density) { DeckThrowThreshold.toPx() }
    val flyPx = with(density) { DeckFlyDistance.toPx() }

    /** 松手（或读屏的「下一张」）：甩够了顶上那张飞出去、插到最底，其余各上一位；不够就弹回。 */
    suspend fun settle(direction: Float) {
        if (abs(throwX.value) < thresholdPx && direction == 0f) {
            throwX.animateTo(0f, spatial)
            return
        }
        throwing = true
        try {
            val side = if (direction != 0f) direction else sign(throwX.value)
            throwX.animateTo(side * flyPx, spatial)
            val thrown = top.intValue.mod(count)
            val next = (thrown + 1) % count
            // 飞出去的那张在看不见的位置落到最底：摞里不满 [DECK_VISIBLE] 张时从最后面淡入
            depths[thrown].snapTo(max(count - 1, DECK_VISIBLE).toFloat())
            top.intValue = next
            throwX.snapTo(0f)
            coroutineScope {
                for (card in 0 until count) launch { depths[card].animateTo(depthOf(card, next, count).toFloat(), spatial) }
            }
        } finally {
            throwing = false
        }
    }

    val subject = cards[topIndex]
    val mark = rememberPosterMark(subject.id, subject.type)
    val interaction = remember { MutableInteractionSource() }
    val nextLabel = stringResource(R.string.explore_deck_next)
    PressArea(
        onClick = { if (!throwing) onSubjectClick(subject.id) },
        shape = MaterialTheme.shapes.extraLarge,
        interactionSource = interaction,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .posterMarkGesture(mark)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) {
                        if (!throwing && count > 1) scope.launch { settle(direction = -1f) }
                        true
                    },
                )
            },
    ) {
        DeckFrame(
            pileModifier = Modifier.pointerInput(key) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (!throwing) scope.launch { settle(direction = 0f) } },
                    onDragCancel = { if (!throwing) scope.launch { throwX.animateTo(0f, spatial) } },
                ) { change, dragAmount ->
                    if (throwing || count < 2) return@detectHorizontalDragGestures
                    change.consume()
                    scope.launch { throwX.snapTo(throwX.value + dragAmount) }
                }
            },
            pile = {
                // 后面几张是装饰：圈进自己的一块，整张卡只代顶上那张封面长出详情页
                CoverPressBlock { _ ->
                    (0 until count).filter { it != topIndex }.sortedByDescending { depthOf(it, topIndex, count) }.forEach { card ->
                        key(cards[card].id) { DeckCard(cards[card], depth = { depths[card].value }, throwX = null, mark = null, coverShape = null) }
                    }
                }
                key(subject.id) {
                    DeckCard(
                        subject,
                        depth = { depths[topIndex].value },
                        throwX = { throwX.value / flyPx },
                        mark = mark,
                        coverShape = posterCoverShape(mark, interaction, DeckCorner),
                    )
                }
            },
            info = { DeckInfo(subject) },
        )
    }
}

/** 第 [card] 张在顶上是 [top] 时的位置（0 = 顶上）。 */
private fun depthOf(card: Int, top: Int, count: Int): Int = (card - top).mod(count)

/**
 * 卡堆的框（真卡与骨架共用）：surfaceContainer 的大圆角卡，四周 16；左边是一摞（固定 [DeckPileWidth] × [DeckPileHeight]，
 * 正好放下四张错开的封面），右边是说明。
 */
@Composable
private fun DeckFrame(
    pile: @Composable () -> Unit,
    info: @Composable ColumnScope.() -> Unit,
    pileModifier: Modifier = Modifier,
) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp).testTag("explore_deck")) {
            Box(pileModifier.size(width = DeckPileWidth, height = DeckPileHeight).testTag("explore_deck_pile")) { pile() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f), content = info)
        }
    }
}

/**
 * 摞里的一张：位置 [depth]（可以是小数，动画途中）与顶上那张的 [throwX]（甩出的比例：横移、转一点、淡出）都由
 * [deckPlacement] 在绘制阶段读，甩动不重组。
 */
@Composable
private fun DeckCard(
    subject: SubjectBo,
    depth: () -> Float,
    throwX: (() -> Float)?,
    mark: PosterMark?,
    coverShape: Shape?,
) {
    SubjectPoster(
        url = subject.coverUrl,
        description = displayNameOf(subject).takeIf { throwX != null },
        modifier = Modifier.width(DeckCardWidth).deckPlacement(depth, throwX),
        shape = coverShape ?: MaterialTheme.shapes.small,
        shadowElevation = DeckShadow,
        mark = mark,
    )
}

/** 摞里一张的位置（真卡与骨架共用）：[depth] 越大越小、越往右，过了第 [DECK_VISIBLE] 张淡没；[throwX] 是顶上那张甩出的比例。 */
private fun Modifier.deckPlacement(depth: () -> Float, throwX: (() -> Float)?): Modifier = graphicsLayer {
    val position = depth()
    val thrown = throwX?.invoke() ?: 0f
    transformOrigin = DeckOrigin
    translationX = position * DeckStep.toPx() + thrown * DeckFlyDistance.toPx()
    rotationZ = thrown * DECK_FLY_DEGREES
    scaleX = 1f - position * DECK_SHRINK_PER_CARD
    scaleY = scaleX
    alpha = (DECK_VISIBLE - position).coerceIn(0f, 1f) * (1f - abs(thrown)).coerceIn(0f, 1f)
}

/** 顶上那张的说明：标题、元信息、分数（照公共评分开关）、标签，与焦点卡同一套行。 */
@Composable
private fun ColumnScope.DeckInfo(subject: SubjectBo) {
    Text(
        text = displayNameOf(subject),
        style = MaterialTheme.typography.titleMedium,
        maxLines = DECK_TITLE_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
    )
    val meta = subjectMetaParts(subject)
    if (meta.isNotEmpty()) SubjectMetaText(meta, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant)
    FocusScore(subject)
    FocusTags(subject.metaTags)
}

/** 摞里放几部（原型 `vDeck` 的 8）。 */
private const val DECK_SIZE = 8

/** 露出几张（原型 `.dk.gone` 的 i > 3）。 */
private const val DECK_VISIBLE = 4

/** 一张卡的宽（原型 `.dk`）。 */
private val DeckCardWidth = 128.dp

/** 后一张比前一张往右错开多少、缩小多少（原型的 13px、.08）。我的设计决定（not in M3）。 */
private val DeckStep = 13.dp
private const val DECK_SHRINK_PER_CARD = 0.08f

/** 一摞的框：一张卡加三次错开，高是一张卡的 3:4（原型 `.pile` 的 166 × 171）。 */
private val DeckPileWidth = DeckCardWidth + DeckStep * (DECK_VISIBLE - 1)
private val DeckPileHeight = DeckCardWidth * 4 / 3

/** 缩小以右边中点为准：后面的卡右边缘依次露出来。 */
private val DeckOrigin = TransformOrigin(1f, 0.5f)

/** 甩出去要过多远（原型的 60px）、飞出去多远、飞到头时转几度（原型的 420px、24°）。我的设计决定（not in M3）。 */
private val DeckThrowThreshold = 60.dp
private val DeckFlyDistance = 420.dp
private const val DECK_FLY_DEGREES = 24f

/** 卡的投影（原型 `.dk .cv` 的阴影）。我的设计决定（not in M3）。 */
private val DeckShadow = 3.dp

/** 封面圆角：与海报同一档 8；长按标记的按住变形要 Dp 值。 */
private val DeckCorner = 8.dp

/** 标题最多几行（原型 `.dinfo .t-tm`）。 */
private const val DECK_TITLE_LINES = 3
