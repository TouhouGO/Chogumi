package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.collection.PosterMark
import io.github.touhougo.chogumi.core.ui.collection.posterCoverShape
import io.github.touhougo.chogumi.core.ui.collection.posterMarkGesture

/**
 * 海报卡的三处排法。结构只有一份（[PosterCard]）：3:4 封面（可压角标）+ 固定两行标题（同一行卡片等高），
 * 整块可点、只裁水波纹（[PressArea]）；这里只差标题字阶与色、标题上下留白、封面圆角。
 */
enum class PosterCardStyle {
    /** 探索货架与搜索结果：标题 `bodyLarge`、`onSurfaceVariant`，封面下 8；圆角 8（`CornerSmall`）。 */
    SHELF,

    /** 资料页收藏网格（原型 `.gcell{gap:6px}` + `.t-body-s.clamp2`）：标题 `bodySmall`、正文色，封面下 6；圆角 8。 */
    GRID,

    /** 详情页的作品 / 关联横排与关联网格（原型 `.car .p` / `.rgrid`）：标题 `labelMedium`，封面下 6；圆角 12（`CornerMedium`）。 */
    CAROUSEL,
}

/** 一种排法落到具体数值（标题留白与骨架标题宽是设计决定，not in M3，取各自原型）。 */
private class PosterCardSpec(
    val titleStyle: TextStyle,
    val titleColor: Color,
    val titlePadding: PaddingValues,
    /** 封面圆角：8（CornerSmall）或 12（CornerMedium）；可长按标记时按住从它收到 16（[posterCoverShape]）。 */
    val corner: Dp,
    /** 骨架里标题第二行的宽度比例。 */
    val skeletonTitleFraction: Float,
)

@Composable
private fun PosterCardStyle.spec(): PosterCardSpec = when (this) {
    PosterCardStyle.SHELF -> PosterCardSpec(
        titleStyle = MaterialTheme.typography.bodyLarge,
        titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
        titlePadding = PaddingValues(top = 8.dp, bottom = 2.dp),
        corner = PosterCorner,
        skeletonTitleFraction = 0.9f,
    )
    PosterCardStyle.GRID -> PosterCardSpec(
        titleStyle = MaterialTheme.typography.bodySmall,
        titleColor = LocalContentColor.current,
        titlePadding = PaddingValues(top = 6.dp, bottom = 2.dp),
        corner = PosterCorner,
        skeletonTitleFraction = 0.8f,
    )
    PosterCardStyle.CAROUSEL -> PosterCardSpec(
        titleStyle = MaterialTheme.typography.labelMedium,
        titleColor = LocalContentColor.current,
        titlePadding = PaddingValues(top = 6.dp),
        corner = CarouselPosterCorner,
        skeletonTitleFraction = 0.9f,
    )
}

/** 横排里一张海报卡的宽度（原型 `.car .p{width:104px}`；设计决定，not in M3）。 */
val PosterCardWidth = 104.dp

/** 详情页横排海报的圆角 12（`CornerMedium`）。 */
private val CarouselPosterCorner = 12.dp

/**
 * 海报卡（全 App 唯一一份）：探索、搜索、收藏网格、详情页横排都是它，排法见 [PosterCardStyle]。
 * 宽度由调用方的 [modifier] 给（横排给定宽，网格给一格的宽）；[coverOverlay] 压在封面上，给名次、分数、关系名这类角标留的槽位，
 * 和封面图一起裁进封面的圆角（[SubjectPoster]）。
 * [onClick] 为 null 时不可点（详情页还没做的入口别伪装成能点）。
 * [supporting] 是标题下的一行辅助文字（追番进度、在看人数），见 [PosterCardLayout]；null 时不占这一行。
 *
 * [mark] 是这部作品的长按标记（[rememberPosterMark]，页面装了 [io.github.touhougo.chogumi.core.ui.collection.PosterMarkHost] 才有）：
 * 右上角是「我」的收藏状态，按住时封面圆角收到 [io.github.touhougo.chogumi.core.ui.collection.PosterChargedCorner] 作预示，长按展开状态环。
 */
@Composable
fun PosterCard(
    imageUrl: String?,
    title: String,
    style: PosterCardStyle,
    onClick: (() -> Unit)?,
    mark: PosterMark?,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    coverOverlay: @Composable BoxScope.() -> Unit = {},
) {
    val spec = style.spec()
    val interaction = remember { MutableInteractionSource() }
    val shape = posterCoverShape(mark, interaction, spec.corner)
    PressArea(onClick = onClick, shape = shape, interactionSource = interaction, modifier = modifier.posterMarkGesture(mark)) {
        PosterCardLayout(spec, title, PosterSupporting.of(supporting)) {
            SubjectPoster(imageUrl, Modifier.fillMaxWidth(), shape = shape, mark = mark, overlay = coverOverlay)
        }
    }
}

/**
 * 海报卡的骨架：布局与真实卡同一份（[PosterCardLayout]），同宽就同高（含两行标题）。
 * [supporting] 为 true 时标题下多一行辅助文字的色条，对应带 `supporting` 的真卡。
 */
@Composable
fun PosterCardSkeleton(style: PosterCardStyle, modifier: Modifier = Modifier, supporting: Boolean = false) {
    val spec = style.spec()
    PosterCardLayout(spec, title = null, supporting = if (supporting) PosterSupporting.Pending else PosterSupporting.None, modifier = modifier) {
        SubjectPosterSkeletonBox(Modifier.fillMaxWidth(), shape = RoundedCornerShape(spec.corner))
    }
}

/** 标题下那一行辅助文字：没有、有字、骨架里的色条。 */
private sealed interface PosterSupporting {
    data object None : PosterSupporting
    data object Pending : PosterSupporting
    data class Shown(val text: String) : PosterSupporting

    companion object {
        fun of(text: String?): PosterSupporting = if (text == null) None else Shown(text)
    }
}

/** 辅助文字骨架色条占卡宽的比例（真实文字只占一行，色条宽度只是示意；设计决定，not in M3）。 */
private const val SUPPORTING_SKELETON_FRACTION = 0.5f

/**
 * 真实卡与骨架共用的布局：标题字阶、留白与固定两行只写在这里；[title] 为 null 时画 [SkeletonText]。
 * 辅助文字一行 `bodySmall`、`onSurfaceVariant`，放不下省略而不折行：字号放大、文字换了，卡片高度都不变。
 */
@Composable
private fun PosterCardLayout(
    spec: PosterCardSpec,
    title: String?,
    supporting: PosterSupporting,
    modifier: Modifier = Modifier,
    poster: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        poster()
        val titleModifier = Modifier.padding(spec.titlePadding)
        if (title != null) {
            Text(
                text = title,
                modifier = titleModifier.heightIn(min = rememberTextLinesHeight(spec.titleStyle, 2)),
                color = spec.titleColor,
                style = spec.titleStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            SkeletonText(style = spec.titleStyle, modifier = titleModifier, lines = 2, widthFraction = spec.skeletonTitleFraction)
        }
        val supportingStyle = MaterialTheme.typography.bodySmall
        when (supporting) {
            PosterSupporting.None -> Unit
            PosterSupporting.Pending -> SkeletonText(style = supportingStyle, widthFraction = SUPPORTING_SKELETON_FRACTION)
            is PosterSupporting.Shown -> Text(
                text = supporting.text,
                style = supportingStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
