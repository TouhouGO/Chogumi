package io.github.touhougo.chogumi.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselItemDrawInfo
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.ui.composables.CarouselSlot
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.EpButton
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

// 待看横滑的几何（横滑与它的骨架共用）。carousel 规格见 [ToWatchRow] 注释。
/** 设计决定（非 M3）：卡片宽 144dp（原型 .todo-card）。 */
internal val ToWatchItemWidth = 144.dp

internal val ToWatchItemSpacing = 8.dp

private val ToWatchEdgePadding = 16.dp

private val ToWatchVerticalPadding = 8.dp

/** 设计决定（非 M3）：海报、标题、按钮之间的纵向间距（原型 .todo-card）。 */
internal val ToWatchTextGap = 6.dp


/**
 * 待看横滑的 carousel 配置唯一出处：真实内容（[ToWatchRow]）与骨架（[HomeSkeleton]）都从这里来，
 * 条目宽、条目间距、首尾内边距、上下内边距、停手吸附不会在两处各抄一份而跑偏。
 * 骨架传 [userScrollEnabled] = false，其余完全一致。
 */
@Composable
internal fun ToWatchCarousel(
    itemCount: Int,
    userScrollEnabled: Boolean = true,
    content: @Composable CarouselItemScope.(Int) -> Unit,
) {
    val carouselState = rememberCarouselState { itemCount }
    HorizontalUncontainedCarousel(
        state = carouselState,
        itemWidth = ToWatchItemWidth,
        itemSpacing = ToWatchItemSpacing,
        flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(carouselState),
        userScrollEnabled = userScrollEnabled,
        contentPadding = PaddingValues(horizontal = ToWatchEdgePadding),
        modifier = Modifier.fillMaxWidth().padding(vertical = ToWatchVerticalPadding),
        content = content,
    )
}


/**
 * 待看横滑：M3 官方 [HorizontalUncontainedCarousel]——条目滑到两端时被遮罩收窄，读起来像叠在一起。
 *
 * 官方实现按**矩形**遮罩裁整张卡片（material3 `Carousel.kt` 的 `carouselItem`）：遮罩以条目中心
 * **左右对称**收窄，再整体平移把边缘拼齐。直接套用的话，海报圆角会被切成直边，标题与按钮的起始边被切掉一截；
 * 只做淡出也挡不住起始边被切。这里分三层处理：
 * - 海报放在 [CarouselSlot] 里（遮罩裁切）：圆角跟着遮罩边走，收窄时仍是完整圆角；
 * - 海报下方的标题与 EP 按钮**跟着遮罩的起始边平移**（[carouselTextShift]），
 *   与海报左缘始终对齐，起始边永远不被切；只在末端（本就滑出屏幕的一侧）被遮罩截断；
 * - 同时随收窄程度淡出（[carouselItemVisibleFraction]），收成窄条时透明，不会留下半截字。
 *   **设计决定（非 M3）**：M3 只说 uncontained 可在条目上下放文字，未规定收窄时文字如何处理。
 *
 * 间距取 `references/components/containment.md` carousel 一节 uncontained 的规格：
 * 首尾 16dp、条目间 8dp、上下 8dp；海报圆角 28dp（shapes.extraLarge）。
 * 停手吸附用官方 [CarouselDefaults.multiBrowseFlingBehavior]（对齐 keyline）。
 */
@Composable
internal fun ToWatchRow(
    items: List<HomeToWatchItem>,
    pendingVisuals: Map<Int, EpPendingVisual>,
    onEpButtonTap: (HomeProgressCard) -> Unit,
    onMarkFinished: (HomeProgressCard) -> Unit,
    onSubjectClick: (Int) -> Unit,
) {
    ToWatchCarousel(itemCount = items.size) { index ->
        val item = items.getOrNull(index) ?: return@ToWatchCarousel
        val subject = item.card.subject
        ToWatchCardFrame(
            modifier = Modifier.testTag(HomeToWatchCardTagPrefix + index),
            poster = {
                // 圆角随遮罩：长成详情页、缩回来都是同一个角
                SubjectPoster(
                    subject.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    description = displayNameOf(subject),
                    shape = MaterialTheme.shapes.extraLarge,
                    onClick = { onSubjectClick(subject.id) },
                ) {
                    UnwatchedCountBadge(item.airedUnwatchedCount, Modifier.align(Alignment.TopEnd).padding(CoverBadgeInset))
                }
            },
            details = {
                ToWatchTitle(displayNameOf(subject))
                // 进度按钮（宽度随内容，下限见 EpButton），靠左对齐于海报；卡片上没有进度文字，按钮写出目标「+ 第 N 集」
                EpButton(
                    modifier = Modifier.testTag(HomeToWatchEpTagPrefix + index),
                    state = item.card.epButtonState,
                    onNext = { onEpButtonTap(item.card) },
                    onMarkFinished = { onMarkFinished(item.card) },
                    showTarget = true,
                    subjectType = subject.type,
                    // 轮播按卡片边界裁切：大字号下按钮也要在卡片里
                    maxWidth = ToWatchItemWidth,
                    pending = pendingVisuals[subject.id],
                )
            },
        )
    }
}


/** 待看卡片的标题（一行）；[text] 为 null 画骨架——字阶与行数只在这里写一份。 */
@Composable
internal fun ToWatchTitle(text: String?) {
    val style = MaterialTheme.typography.bodyLarge
    if (text != null) {
        Text(text = text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        SkeletonText(style, Modifier.testTag(HomeSkeletonTitleTag), widthFraction = 0.8f)
    }
}


/**
 * 一张待看卡片的框架——真实卡片（[ToWatchRow]）与骨架（[HomeSkeleton]）共用的唯一出处。
 * 官方 carousel 的条目遮罩是矩形，卡片起始边会被它切掉一截，所以这里统一处理两件事，调用方只管往槽里填内容：
 * - [poster]：海报槽位走 [CarouselSlot]（遮罩裁切），圆角跟着遮罩边走；
 * - [details]：海报下方的内容列跟着遮罩起始边平移并随收窄淡出（见 [ToWatchRow] 注释），起始边永远不被切。
 */
@Composable
internal fun CarouselItemScope.ToWatchCardFrame(
    poster: @Composable BoxScope.() -> Unit,
    details: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawInfo = carouselItemDrawInfo
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        modifier = modifier.width(ToWatchItemWidth),
        verticalArrangement = Arrangement.spacedBy(ToWatchTextGap),
    ) {
        CarouselSlot(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ChogumiDimens.CoverRatio),
            content = poster,
        )
        // 海报下方的文字与按钮：对齐遮罩起始边并随收窄淡出（只读绘制层，滑动时不触发重组/重排）
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = carouselTextShift(drawInfo, isRtl)
                alpha = carouselItemVisibleFraction(drawInfo)
            },
            verticalArrangement = Arrangement.spacedBy(ToWatchTextGap),
            content = details,
        )
    }
}


/**
 * 条目的可见比例：完全展开 = 1，收成最窄 = 0（纯函数，读 [CarouselItemDrawInfo]）。
 * 用于海报下方文字与按钮的淡出——遮罩是矩形，文字若不淡出就会被切成半截。
 */
private fun carouselItemVisibleFraction(info: CarouselItemDrawInfo): Float {
    val range = info.maxSize - info.minSize
    if (range <= 0f) return 1f
    return ((info.size - info.minSize) / range).coerceIn(0f, 1f)
}


/**
 * 海报下方文字的平移量：把文字的起始边挪到遮罩的起始边（纯函数，读 [CarouselItemDrawInfo]）。
 * 遮罩在条目内左右对称，故 LTR 右移 `maskRect.left`、RTL 左移同样的量。
 */
private fun carouselTextShift(info: CarouselItemDrawInfo, isRtl: Boolean): Float =
    if (isRtl) -info.maskRect.left else info.maskRect.left
