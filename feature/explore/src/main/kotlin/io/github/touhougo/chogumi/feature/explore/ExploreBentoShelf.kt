package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.ui.collection.posterCoverShape
import io.github.touhougo.chogumi.core.ui.collection.posterMarkGesture
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.CoverScoreBadge
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.theme.isDarkThemeActive

/**
 * 拼盘（原型 `.bento`）：一页一大两小，大的竖跨两行，三张都是完整的 3:4 封面（不裁成横图）；横滑翻页、停在整页上，
 * 隔页左右翻转（大图一页在左、一页在右）。大图底部压渐变托住白字的标题与「年份、分数」，小图右下角是封面分数角标。
 * 页宽是行宽减去露出下一页的 [BentoPeek]，宽屏上不超过 [BentoMaxWidth]。[subjects] 为 null 时画同样几何的骨架。
 */
@Composable
internal fun BentoPages(subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val pageWidth = min(maxWidth - BentoPeek, BentoMaxWidth)
        // 大图与两张小图等高：大高 = 2 × 小高 + 间隔，3:4 下大宽 = 2 × 小宽 + 间隔 × 3/4
        val small = (pageWidth - BentoGap - BentoBigExtra) / 3
        val big = small * 2 + BentoBigExtra
        CarouselRow(userScrollEnabled = subjects != null, snapToItems = true) {
            if (subjects == null) {
                items(BENTO_SKELETON_PAGES) { page -> BentoPage(List(BENTO_PAGE_SIZE) { null }, flipped = page % 2 == 1, big, small, onSubjectClick) }
            } else {
                itemsIndexed(subjects.chunked(BENTO_PAGE_SIZE), key = { _, page -> page.first().id }) { index, page ->
                    // 不满三张的末页不翻：翻过去左边是空的一列
                    BentoPage(page, flipped = index % 2 == 1 && page.size == BENTO_PAGE_SIZE, big, small, onSubjectClick)
                }
            }
        }
    }
}

/** 一页：大图一列、两张小图一列，[flipped] 时小图在左。[page] 里的 null 画骨架。 */
@Composable
private fun BentoPage(page: List<SubjectBo?>, flipped: Boolean, big: Dp, small: Dp, onSubjectClick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(BentoGap)) {
        val bigCover = @Composable { BentoCover(page[0], big, onSubjectClick, caption = true) }
        val smallCovers = @Composable {
            if (page.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(BentoGap)) {
                    page.drop(1).forEach { BentoCover(it, small, onSubjectClick, caption = false) }
                }
            }
        }
        if (flipped) {
            smallCovers()
            bigCover()
        } else {
            bigCover()
            smallCovers()
        }
    }
}

/**
 * 拼盘里的一张封面：整张可点、可长按标记（与热门大图同一种写法：[PressArea] 包着 [SubjectPoster]，点哪里都由这张封面长出详情页）。
 * [caption] 的大图压标题与「年份、分数」，小图压封面分数角标。[subject] 为 null 时画同宽的骨架。
 */
@Composable
private fun BentoCover(subject: SubjectBo?, width: Dp, onSubjectClick: (Int) -> Unit, caption: Boolean) {
    if (subject == null) {
        SubjectPosterSkeletonBox(Modifier.width(width))
        return
    }
    val mark = rememberPosterMark(subject.id, subject.type)
    val interaction = remember { MutableInteractionSource() }
    val shape = posterCoverShape(mark, interaction, BentoCorner)
    PressArea(
        onClick = { onSubjectClick(subject.id) },
        shape = shape,
        interactionSource = interaction,
        modifier = Modifier.width(width).posterMarkGesture(mark),
    ) {
        SubjectPoster(
            url = subject.coverUrl,
            description = displayNameOf(subject),
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            mark = mark,
        ) {
            if (caption) BentoCaption(subject) else CoverScoreBadge(subject.ratingScore, mark)
        }
    }
}

/** 大图底部的渐变与白字：标题两行，下面一行年份与分数（原型 `.bcap`）。 */
@Composable
private fun BoxScope.BentoCaption(subject: SubjectBo) {
    // 与热门大图同一套托底：scrim 色，深色主题再深一档（设计决定，not in M3）
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = if (isDarkThemeActive()) BENTO_SCRIM_DARK else BENTO_SCRIM_LIGHT)
    val gradient = remember(scrim) { Brush.verticalGradient(listOf(scrim.copy(alpha = 0f), scrim)) }
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(BENTO_SCRIM_FRACTION)
            .background(gradient),
    )
    // 图上的字恒为白：底下是封面加托底，不随主题变
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
    ) {
        Text(
            text = displayNameOf(subject),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = yearAndScoreParts(subject)
        if (meta.isNotEmpty()) MetaText(meta, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = BENTO_META_ALPHA))
    }
}

/** 一页几张：一大两小。 */
private const val BENTO_PAGE_SIZE = 3

/** 骨架画几页：一页多一点就铺满一屏。 */
private const val BENTO_SKELETON_PAGES = 2

/** 页里各封面的间隔（原型 `.bp` 的 gap 12）。 */
private val BentoGap = 12.dp

/** 大图比两张小图宽出的那一截：间隔的 3/4（大图 3:4 且与两张小图加间隔等高）。 */
private val BentoBigExtra = BentoGap * 3 / 4

/** 露出下一页的宽度：页宽 = 行宽 − 它（原型 `--bs` 里的 64）。 */
private val BentoPeek = 64.dp

/** 宽屏上一页最宽：再宽大图就比屏幕还高。我的设计决定（not in M3）。 */
private val BentoMaxWidth = 480.dp

/** 封面圆角：与海报同一档 8（原型 `.cv`）；长按标记的按住变形要 Dp 值。 */
private val BentoCorner = 8.dp

/** 大图托底占封面下部的比例与不透明度（原型 `.bp .bg::after` 的 42%、.7；深色主题再深一点）。我的设计决定（not in M3）。 */
private const val BENTO_SCRIM_FRACTION = 0.42f
private const val BENTO_SCRIM_LIGHT = 0.7f
private const val BENTO_SCRIM_DARK = 0.78f

/** 大图上年份与分数那一行的白字不透明度（原型 `.bcap .t-lm` 的 .9）。 */
private const val BENTO_META_ALPHA = 0.9f
