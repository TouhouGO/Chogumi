package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import io.github.touhougo.chogumi.core.ui.collection.PosterMark
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkBadges
import io.github.touhougo.chogumi.core.ui.collection.posterMarkCover
import io.github.touhougo.chogumi.core.ui.common.navigation.expandOrigin
import io.github.touhougo.chogumi.core.ui.image.rememberCoverRequest
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/** 封面圆角 8dp（`CornerSmall`）：[SubjectPoster]、它的骨架 [SubjectPosterSkeletonBox] 与海报卡的按下水波纹共用。 */
internal val PosterShape = ShapeDefaults.Small

/** [PosterShape] 的圆角值：可长按标记的封面按住时从它变形，落回时回到它。 */
internal val PosterCorner = 8.dp

/**
 * 海报的骨架块：形状与宽高比取自 [SubjectPoster]（同一份 [PosterShape] 与 [ChogumiDimens.CoverRatio]），
 * 由调用方的 [modifier] 给宽度，高度随比例推出——与真实海报同宽就同高。
 */
@Composable
fun SubjectPosterSkeletonBox(modifier: Modifier = Modifier, shape: Shape = PosterShape) {
    SkeletonBox(
        modifier = modifier.aspectRatio(ChogumiDimens.CoverRatio).testTag(SkeletonPosterTag),
        shape = shape,
    )
}

/**
 * 作品封面（全 App 唯一画法，也是封面唯一的裁切处）：默认 3:4、8dp 圆角。封面图和压在上面的 [overlay]（名次、角标、
 * 渐变托底……）一起裁进 [shape]，四角永远是同一个圆角；页面自己不裁封面，也不在封面旁边另叠一层（门禁 check_cover_clip.py）。
 *
 * - 尺寸由 [modifier] 给：只给宽时按 3:4 推出高；给定宽高（放送卡、热门大图）就按给的，图裁切铺满。
 * - [onClick] 给只有一张封面的点击目标：水波纹裁在同一个形状里。封面加标题的整块可点用 [PressArea] 包在外面。
 * - [mark] 是这部作品的长按标记：封面这一块录给浮层（浮起时画同一份内容），右上角压「我」的收藏状态（[PosterMarkBadges]）。
 *   可长按的封面，[shape] 取 [io.github.touhougo.chogumi.core.ui.collection.posterCoverShape]，按住时的变形与浮起的那一份同一个圆角。
 * - [shadowElevation] 只给需要浮起的封面（详情页头部）。
 * - 点中的封面会长成作品详情页（容器转换，`expandOrigin`，见 `PageExpand.kt`）；起点标记挂在封面最外层，不影响布局与点击。
 */
@Composable
fun SubjectPoster(
    url: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    shape: Shape = PosterShape,
    shadowElevation: Dp = 0.dp,
    mark: PosterMark? = null,
    onClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .coverRatio()
            .expandOrigin(shape)
            .then(if (shadowElevation > 0.dp) Modifier.shadow(shadowElevation, shape) else Modifier)
            .posterMarkCover(mark)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        CoverImage(url, description, Modifier.matchParentSize())
        overlay()
        PosterMarkBadges(mark)
    }
}

/**
 * 封面的尺寸：调用方定了宽高（放送卡 104×146、热门大图铺满轮播项）就按给的；否则按 3:4，从给的宽（没给宽时从高）推出另一边。
 * 官方 `aspectRatio` 在宽高都定死、比例对不上时会无视约束量出 3:4，再被居中裁回槽位——画面中段之外的部分（大图底部的标题）落到看不见的地方。
 */
private fun Modifier.coverRatio(): Modifier = layout { measurable, constraints ->
    val frame = when {
        constraints.hasFixedWidth && constraints.hasFixedHeight -> constraints
        constraints.hasBoundedWidth -> {
            val width = constraints.maxWidth
            Constraints.fixed(width, constraints.constrainHeight((width / ChogumiDimens.CoverRatio).roundToInt()))
        }
        else -> {
            val height = constraints.maxHeight
            Constraints.fixed(constraints.constrainWidth((height * ChogumiDimens.CoverRatio).roundToInt()), height)
        }
    }
    val placeable = measurable.measure(frame)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** 封面图本身：裁切铺满，取不到时铺 surfaceContainerHighest 占位色。只在 [SubjectPoster] 里画。 */
@Composable
private fun CoverImage(url: String?, description: String?, modifier: Modifier) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest)
    AsyncImage(
        model = rememberCoverRequest(url),
        contentDescription = description,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
        modifier = modifier.testTag(if (url.isNullOrBlank()) "subject_cover_placeholder" else "subject_cover"),
    )
}

/**
 * 一张封面模糊、放大后铺满它的框（详情页头部的底色、生涯卡封面不够铺满时的底图）；[alpha] 由调用方定。
 * 模糊 28、放大 1.2 出自详情页原型 `.wash img`（设计决定，not in M3）。`Modifier.blur` 只在 API 31+ 生效，低版本只剩放大的原图。
 */
@Composable
fun CoverWash(url: String?, modifier: Modifier = Modifier, alpha: Float = 1f) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = rememberCoverRequest(url, blurred = true),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .scale(WASH_SCALE)
            .blur(WASH_BLUR)
            .alpha(alpha),
    )
}

private val WASH_BLUR = 28.dp
private const val WASH_SCALE = 1.2f
