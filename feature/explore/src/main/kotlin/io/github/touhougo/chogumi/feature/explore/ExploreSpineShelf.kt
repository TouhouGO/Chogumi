package io.github.touhougo.chogumi.feature.explore

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.parseSubjectYearMonth
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkPrelude
import io.github.touhougo.chogumi.core.ui.collection.posterCoverShape
import io.github.touhougo.chogumi.core.ui.collection.posterMarkGesture
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.image.CoverBands
import io.github.touhougo.chogumi.core.ui.image.rememberCoverBands
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.isDarkThemeActive
import kotlinx.coroutines.flow.first

/**
 * 书脊（原型 `.spines`）：一排一样高的书脊，颜色取自各自封面的上中下三段（[rememberCoverBands]），竖写书名、底下年份；
 * 一次抽出一本：抽出的那本展开成整张 3:4 封面（书脊横向长宽，封面从左边露出来，不裁不压），一开始抽出第一本。
 * 点立着的书脊只是抽出它（先前抽出的那本收回去）；点抽出的那本进详情，由它的封面长出。长按立着的书脊先抽出再开环
 * （[PosterMarkPrelude]）。抽出的是哪本跟着这一批作品存下（[rememberShelfIndex]），进详情再回来还是那本抽着。
 * [subjects] 为 null 时画同样几何的骨架。
 */
@Composable
internal fun SpineRow(subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    if (subjects == null) {
        CarouselRow(userScrollEnabled = false, itemSpacing = SpineGap) {
            items(SPINE_SKELETON_COUNT) { index ->
                SkeletonBox(Modifier.size(width = if (index == 0) SpineOpenWidth else SpineWidth, height = SpineHeight), SpineShape)
            }
        }
        return
    }
    val opened = rememberShelfIndex(worksKeyOf(subjects))
    CarouselRow(itemSpacing = SpineGap, modifier = Modifier.testTag("explore_spines")) {
        itemsIndexed(subjects, key = { _, subject -> subject.id }) { index, subject ->
            Spine(subject, open = opened.intValue == index, onOpen = { opened.intValue = index }, onSubjectClick = onSubjectClick)
        }
    }
}

/** 一本书：立着是书脊，抽出来是整张封面。宽度在两者之间走 spatial 动画，书脊上的字与封面交叉淡入淡出。 */
@Composable
private fun Spine(subject: SubjectBo, open: Boolean, onOpen: () -> Unit, onSubjectClick: (Int) -> Unit) {
    val openness = animateFloatAsState(if (open) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "spine_open")
    val isOpen by rememberUpdatedState(open)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val mark = rememberPosterMark(subject.id, subject.type)
    val interaction = remember { MutableInteractionSource() }
    val coverShape = posterCoverShape(mark, interaction, SpineCoverCorner)
    val prelude = remember(openness) {
        PosterMarkPrelude(
            pending = { !isOpen },
            complete = {
                currentOnOpen()
                snapshotFlow { openness.value }.first { it >= 1f }
            },
        )
    }
    SpineFrame(openness, subject, Modifier.posterMarkGesture(mark, prelude)) {
        // 封面按抽出时的整张大小排，书脊多宽就露出多少（书脊的框裁掉其余）；立着时看不见，但整条书脊都是它的点击区
        SubjectPoster(
            url = subject.coverUrl,
            description = displayNameOf(subject),
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints.fixed(SpineOpenWidth.roundToPx(), SpineHeight.roundToPx()))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .graphicsLayer { alpha = openness.value },
            shape = coverShape,
            mark = mark,
            onClick = { if (isOpen) onSubjectClick(subject.id) else currentOnOpen() },
        )
    }
}

/**
 * 书脊的框：宽在立着的 [SpineWidth] 与抽出的 [SpineOpenWidth] 之间（[openness]，排版阶段读，抽出途中不重组），高 [SpineHeight]，
 * 圆角 [SpineShape] 裁着里面的封面（书脊就是这本书的外形，封面在里面露出多少由它定）。底是封面三段颜色连成的竖向渐变，
 * 压一层圆脊的明暗；书名竖写在上、年份在下，随抽出淡掉。[content] 是书里的封面。
 */
@Composable
private fun SpineFrame(openness: State<Float>, subject: SubjectBo, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val bands = rememberCoverBands(subject.coverUrl)
    val fallback = MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = bands?.let(::spineInk) ?: MaterialTheme.colorScheme.onSurface
    val dark = isDarkThemeActive()
    Box(
        modifier
            .layout { measurable, constraints ->
                val width = lerp(SpineWidth, SpineOpenWidth, openness.value).roundToPx()
                val placeable = measurable.measure(Constraints.fixed(width, SpineHeight.roundToPx()))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .clip(SpineShape)
            .background(spineBrush(bands, fallback))
            .background(SpineShading)
            .then(if (dark) Modifier.background(Color.Black.copy(alpha = SPINE_DARK_DIM)) else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - openness.value },
        ) {
            SpineFace(subject, ink, bands != null)
        }
        content()
    }
}

/** 立着时看得见的那一面：顶上一道细线、竖写的书名、底下的年份（原型 `.sp::before`、`.spt`、`.spy`）。 */
@Composable
private fun BoxScope.SpineFace(subject: SubjectBo, ink: Color, onCoverColor: Boolean) {
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .padding(start = 6.dp, end = 6.dp, top = 10.dp)
            .fillMaxWidth()
            .height(2.dp)
            .background(ink.copy(alpha = SPINE_RULE_ALPHA), RoundedCornerShape(1.dp)),
    )
    // 字压在封面颜色上时加一层与字色相反的晕，任何颜色上都读得出（原型 `--sps`）
    val shadow = if (!onCoverColor) null else if (ink.luminance() < 0.5f) LightGlow else DarkGlow
    val title = MaterialTheme.typography.titleSmall.copy(shadow = shadow)
    val lineHeight = with(LocalDensity.current) { title.lineHeight.toDp() }
    val lines = ((SpineHeight - SpineTitleTop - SpineTitleBottom) / lineHeight).toInt().coerceAtLeast(1)
    Text(
        text = stackedTitle(displayNameOf(subject), lines),
        style = title,
        color = ink,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = SpineTitleTop),
    )
    subject.date?.let(::parseSubjectYearMonth)?.let { date ->
        Text(
            text = date.year.toString(),
            style = MaterialTheme.typography.labelMedium.copy(shadow = shadow),
            color = ink.copy(alpha = SPINE_YEAR_ALPHA),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

/**
 * 竖写：一字一行，至多 [lines] 行；放不下时末行换成竖排的省略号「︙」。书名以中文名为先，多是汉字，一字一行就是书脊上的竖排；
 * 夹着的拉丁字母也一个一个立着排（设计决定，not in M3）。
 */
internal fun stackedTitle(title: String, lines: Int): String {
    val chars = title.filterNot { it.isWhitespace() }.codePoints().toArray().map { String(Character.toChars(it)) }
    val shown = if (chars.size <= lines) chars else chars.take(lines - 1) + VERTICAL_ELLIPSIS
    return shown.joinToString("\n")
}

/** 书脊的底：封面上、中（52% 处）、下三段颜色连成的竖向渐变；还没取到颜色时是容器色。 */
private fun spineBrush(bands: CoverBands?, fallback: Color): Brush =
    if (bands == null) {
        Brush.verticalGradient(listOf(fallback, fallback))
    } else {
        Brush.verticalGradient(0f to bands.top, SPINE_MIDDLE_STOP to bands.middle, 1f to bands.bottom)
    }

/** 书脊上的字色：上半截（上段与中段）够亮用深色字，否则白字（原型按亮度 .32 分）。我的设计决定（not in M3）。 */
private fun spineInk(bands: CoverBands): Color =
    if (lerp(bands.top, bands.middle, 0.5f).luminance() > SPINE_LIGHT_THRESHOLD) SpineDarkInk else Color.White

/** 圆脊的明暗：左边一道高光、右边一道暗面（原型 `shade`）。我的设计决定（not in M3）。 */
private val SpineShading = Brush.horizontalGradient(
    0f to Color.White.copy(alpha = 0.22f),
    0.26f to Color.White.copy(alpha = 0f),
    0.64f to Color.Black.copy(alpha = 0f),
    1f to Color.Black.copy(alpha = 0.26f),
)

/** 深色主题下书脊整体压暗一点，不比页面亮得刺眼（原型的 .14）。我的设计决定（not in M3）。 */
private const val SPINE_DARK_DIM = 0.14f

/** 浅底上的深色字（原型 `#17181c`）与两种字晕（原型 `--sps`）。我的设计决定（not in M3）。 */
private val SpineDarkInk = Color(0xFF17181C)
private val LightGlow = Shadow(Color.White.copy(alpha = 0.55f), Offset.Zero, blurRadius = 6f)
private val DarkGlow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), blurRadius = 3f)

private const val SPINE_LIGHT_THRESHOLD = 0.32f
private const val SPINE_MIDDLE_STOP = 0.52f
private const val SPINE_RULE_ALPHA = 0.35f
private const val SPINE_YEAR_ALPHA = 0.75f
private const val VERTICAL_ELLIPSIS = "︙"

/** 骨架画几本：一本抽出加几本立着，铺满一屏。 */
private const val SPINE_SKELETON_COUNT = 8

/** 书脊之间的间隔（原型 `.spines` 的 gap 6）。 */
private val SpineGap = 6.dp

/** 立着的书脊宽、所有书一样的高（原型 `.sp` 的 42 与 `--h` 204，书脊等高）。我的设计决定（not in M3）。 */
private val SpineWidth = 42.dp
private val SpineHeight = 204.dp

/** 抽出来的宽：整张 3:4 封面。 */
private val SpineOpenWidth = SpineHeight * ChogumiDimens.CoverRatio

/** 书脊的圆角（原型 `.sp` 的 6）。我的设计决定（not in M3）。 */
private val SpineShape = RoundedCornerShape(6.dp)

/** 抽出的封面的圆角：与海报同一档 8；长按标记的按住变形要 Dp 值。 */
private val SpineCoverCorner = 8.dp

/** 书名离顶、离底留多少（原型 `.spt` 的 top 20 与 `--h − 52` 的最大高度）。 */
private val SpineTitleTop = 20.dp
private val SpineTitleBottom = 32.dp
