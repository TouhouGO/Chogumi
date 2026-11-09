package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.ui.collection.posterCoverShape
import io.github.touhougo.chogumi.core.ui.collection.posterMarkGesture
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.composables.CarouselSlot
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.isDarkThemeActive

/** 轮播项圆角：即 `MaterialTheme.shapes.extraLarge` 的 28dp，长按封面的 `posterCoverShape` 要 Dp 值。 */
private val HeroCorner = 28.dp

/** 小项宽度：M3 把小项限定在 40–56dp（containment.md:126），取上限。 */
private val HeroSmallItemWidth = 56.dp

/** 卡片间距（原型第 892 行 `GAP`）。 */
private val HeroItemSpacing = 8.dp

/** 轮播容器两侧的留白（原型第 892 行 `PAD`）。 */
private val HeroHorizontalPadding = 16.dp

/**
 * 大项的最窄宽度：M3 multi-browse 让小项约为大项的 1/3（官方 `multiBrowseKeylineList`），
 * 大项不窄于 3 × 56 时小项恰好取满 56。宽屏多放一个大项会窄过它时就不再加（设计决定，not in M3）。
 */
private val HeroMinLargeItemWidth = HeroSmallItemWidth * 3

/** 热门轮播里第 N 项（真实与骨架同一个 tag 前缀，几何测试据此对位）。 */
internal const val HeroItemTagPrefix = "explore_hero_item_"

/** 文字淡出的下限宽度：卡片窄于此就完全看不见（原型第 918 行的 140）。 */
private val HeroTextFadeFloor = 140.dp

/**
 * 大项宽度：[maxWidth] 是轮播所在的整行宽度，减去两侧留白就是轮播容器的宽度。容器里正好排下 n 个大项、
 * 1 个中项、1 个小项，中项取 M3 的目标宽度（大小两项的平均），n 取大项不窄于 [HeroMinLargeItemWidth] 的最多个数（至少 1 个）。
 *
 * 官方排布只在排不满时才挪大项的宽度；这里给的宽度一项不差地排满，大项就停在这个宽度，
 * 轮播高度按封面 3:4 从它推出（[TrendingHeroFrame]），停稳的大项就是一张完整的封面。
 */
internal fun heroLargeItemWidth(maxWidth: Dp): Dp {
    val available = maxWidth - HeroHorizontalPadding * 2
    fun widthFor(largeCount: Int): Dp =
        (available - HeroItemSpacing * (largeCount + 1) - HeroSmallItemWidth * 1.5f) / (largeCount + 0.5f)
    var largeCount = 1
    while (widthFor(largeCount + 1) >= HeroMinLargeItemWidth) largeCount++
    return widthFor(largeCount).coerceAtLeast(0.dp)
}

/**
 * 近期热门：M3 Carousel 的 multi-browse 形态——大项是一张完整的 3:4 封面，后面跟一个中项、一个小项，
 * 宽屏并排多个大项；滚动时各项宽度连续插值，形状跟着变。
 *
 * 封面是竖的，hero 形态（一个大项占满整宽）只能把它裁成横条，所以用 multi-browse：
 * 规范里它就是「一次浏览多张图」的形态（`references/components/containment.md:112`），
 * 宽屏上大项更多（同上 :198）。遮罩裁切、形状变形、吸附都由官方 [HorizontalMultiBrowseCarousel] 给；
 * 一次可滑过多项（同上 :207），用 [CarouselDefaults.multiBrowseFlingBehavior]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrendingHeroCarousel(
    items: List<SubjectBo>,
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    TrendingHeroFrame(items.size, modifier) { index, textAlpha, itemModifier ->
        HeroCard(
            rank = index + 1,
            subject = items[index],
            textAlpha = textAlpha,
            onClick = { onSubjectClick(items[index].id) },
            modifier = itemModifier,
        )
    }
}

/** 真卡与骨架共用官方轮播：宽度、遮罩、间距和圆角只在这里配置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendingHeroFrame(
    itemCount: Int,
    modifier: Modifier,
    userScrollEnabled: Boolean = true,
    /** 第几项、这一项此刻的文字透明度（绘制时读，滑动时各项宽度变而不重组）、槽位的修饰符。 */
    item: @Composable (Int, () -> Float, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val largeItemWidth = heroLargeItemWidth(maxWidth)
        val carouselState = rememberCarouselState { itemCount }
        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = largeItemWidth,
            itemSpacing = HeroItemSpacing,
            userScrollEnabled = userScrollEnabled,
            flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(carouselState),
            minSmallItemWidth = HeroSmallItemWidth,
            maxSmallItemWidth = HeroSmallItemWidth,
            // 规范的 16dp 左右边距（containment.md:133）放在容器外：官方按容器宽度排大中小项，
            // 若用 contentPadding，起止两端还会再挪、缩一次大项，停在开头的那张就不再是 3:4。
            modifier = Modifier
                .padding(horizontal = HeroHorizontalPadding)
                .fillMaxWidth()
                .height(largeItemWidth / ChogumiDimens.CoverRatio),
        ) { index ->
            val drawInfo = carouselItemDrawInfo
            val floorPx = with(LocalDensity.current) { HeroTextFadeFloor.toPx() }
            val largePx = with(LocalDensity.current) { largeItemWidth.toPx() }
            CarouselSlot(MaterialTheme.shapes.extraLarge, Modifier.fillMaxHeight().testTag(HeroItemTagPrefix + index)) {
                item(index, { ((drawInfo.size - floorPx) / (largePx - floorPx)).coerceIn(0f, 1f) }, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun HeroCard(
    rank: Int,
    subject: SubjectBo,
    textAlpha: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 压在图上的遮罩要够暗才托得住白字，深色主题下再深一档：底色取 M3 的 scrim 色，
    // 透明度 0.55 / 0.6 取自原型第 16、33 行（设计决定，not in M3）。深浅按 App 主题判（isDarkThemeActive）。
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = if (isDarkThemeActive()) 0.6f else 0.55f)
    val gradient = remember(scrim) { Brush.verticalGradient(listOf(Color.Transparent, scrim)) }
    // 热门大图也是一部作品的海报：长按标记、右上角「我」的收藏状态，与海报卡同一个入口；圆角 28 已比按住的预示更圆，按住不变形
    val mark = rememberPosterMark(subject.id, subject.type)
    val interaction = remember { MutableInteractionSource() }
    val coverShape = posterCoverShape(mark, interaction, HeroCorner)

    PressArea(onClick = onClick, shape = coverShape, interactionSource = interaction, modifier = modifier.posterMarkGesture(mark)) {
        SubjectPoster(
            url = subject.coverUrl,
            description = displayNameOf(subject),
            modifier = Modifier.fillMaxSize(),
            shape = coverShape,
            mark = mark,
        ) {
            // 底部 60% 的垂直渐变（原型第 161 行），保证标题的对比度
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(gradient),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    .graphicsLayer { alpha = textAlpha() },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 图上的字与名次底恒为白：底下是封面加 scrim，不随主题变（白 0.22 的名次底取自原型，设计决定，not in M3）
                Surface(
                    color = Color.White.copy(alpha = 0.22f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(R.string.explore_hero_rank, rank),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                Text(
                    text = displayNameOf(subject),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 近期热门的加载骨架：和真卡同宽同高，免得内容到位时整页跳一下。 */
@Composable
internal fun TrendingHeroSkeleton(modifier: Modifier = Modifier) {
    // 本项目设计决定（not in M3）：预留 8 项，避免只有两项时官方轮播切换排列模式。
    TrendingHeroFrame(itemCount = 8, modifier = modifier, userScrollEnabled = false) { _, _, itemModifier ->
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // 空 Surface 需要显式撑满轮播分配的槽位；真卡靠内部 Box.fillMaxSize() 撑开。
            modifier = itemModifier.fillMaxSize(),
            content = {},
        )
    }
}
