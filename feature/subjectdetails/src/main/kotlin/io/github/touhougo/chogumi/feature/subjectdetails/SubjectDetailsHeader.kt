package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectMetaPart
import io.github.touhougo.chogumi.core.model.SubjectTagChip
import io.github.touhougo.chogumi.core.model.TagSource
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.subjectMetaParts
import io.github.touhougo.chogumi.core.model.subjectTagRow
import io.github.touhougo.chogumi.core.model.tagSearchCriteria
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.copyOnLongPress
import io.github.touhougo.chogumi.core.ui.composables.CoverWash
import io.github.touhougo.chogumi.core.ui.composables.HeaderChip
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectMetaText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.formatScore
import io.github.touhougo.chogumi.core.ui.composables.subjectRatingText
import io.github.touhougo.chogumi.core.ui.image.ImageViewer
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/** 详情页首屏各区块的 testTag：真实内容与骨架共用，几何测试据此对位。 */
internal const val DetailsBannerTag = "details_banner"
internal const val DetailsPosterTag = "details_poster"
internal const val DetailsTitleTag = "details_title"
internal const val DetailsMetaTag = "details_meta"
internal const val DetailsHeaderChipsTag = "details_header_chips"
internal const val DetailsTagsTag = "details_tags"
internal const val DetailsTabsTag = "details_tabs"

/** 封面宽 108、3:4（原型 `.poster`；设计决定，not in M3）。真实封面与骨架共用。 */
private val POSTER_WIDTH = 108.dp

/** 封面圆角 12 = M3 `CornerMedium`（tokens.md 圆角阶梯）。真实封面与骨架共用。 */
private val PosterShape = ShapeDefaults.Medium

/**
 * 顶部底色在状态栏以下的高度：原型 `.wash{height:260px}` 含 32px 的状态栏，这里只算状态栏以下的 228
 * （设计决定，not in M3）。
 */
private val WASH_HEIGHT_BELOW_STATUS_BAR = 228.dp

/**
 * 详情首屏一个槽的取值——「画真实内容 / 画占位 / 不画」的**唯一**判定结果。
 *
 * - [Real]：条目里确实有这个值，画真实内容；
 * - [Placeholder]：列表接口写的截断快照里没有它（见 [SubjectBo.hasFullDetail]），
 *   按真实排版画同框架的占位，等完整数据到了原地替换；
 * - [Absent]：已知为空，整槽不画——下面的区块往上收是允许的。
 */
internal sealed interface SlotFill<out T> {
    data class Real<T>(val value: T) : SlotFill<T>
    data object Placeholder : SlotFill<Nothing>
    data object Absent : SlotFill<Nothing>
}

/**
 * 首屏各槽唯一的判定入口：`value == null` 表示条目里没有这个值，此时「是否已知为空」由
 * [hasFullDetail] 决定——未经详情接口补全的记录里，缺席只代表「还没到」，必须预留占位。
 */
internal fun <T : Any> slotFill(value: T?, hasFullDetail: Boolean): SlotFill<T> = when {
    value != null -> SlotFill.Real(value)
    hasFullDetail -> SlotFill.Absent
    else -> SlotFill.Placeholder
}

/**
 * 详情首屏在某个条目下各槽的取值。真实首屏、整页骨架以及「半截条目 → 完整条目」三种情况
 * 都只从这里派生，界面不各自写 `tags.isEmpty()` 之类的判断。
 *
 * `subject == null` 是整页骨架：各槽一律占位。[synopsis] 画在概览 tab 的顶部（[SubjectOverviewSection]）。
 */
internal data class FirstScreenSlots(
    val meta: SlotFill<List<SubjectMetaPart>>,
    val tags: SlotFill<List<SubjectTagChip>>,
    val synopsis: SlotFill<String>,
)

internal fun firstScreenSlots(subject: SubjectBo?): FirstScreenSlots {
    val full = subject?.hasFullDetail == true
    return FirstScreenSlots(
        meta = slotFill(subject?.let(::subjectMetaParts)?.takeIf { it.isNotEmpty() }, full),
        tags = slotFill(subject?.let(::subjectTagRow)?.takeIf { it.isNotEmpty() }, full),
        synopsis = slotFill(trustedSummaryOf(subject), full),
    )
}

/**
 * 简介只有**详情接口**给的是终值：v0 搜索/浏览的短行带的是服务端截断的摘要（实测截在 120 字），
 * 拿它当终值渲染，完整简介到达时还会再长高几行、把下面整块推下去，所以未补全时按「未知」处理。
 */
private fun trustedSummaryOf(subject: SubjectBo?): String? =
    subject?.takeIf { it.hasFullDetail }?.summary?.takeIf { it.isNotBlank() }

/**
 * 头部的**框架**——真实头部 [SubjectDetailsHeader] 与骨架 [SubjectDetailsHeaderSkeleton] 共用的唯一出处：
 * 顶部底色的高度、封面与文字栏的排布（底对齐、间距 16、左右 16）、标签行的位置都只写在这里，
 * 两边只往各个槽位里填内容，所以内容出现时每个区块都落在骨架画出的位置上。
 *
 * 文字栏从顶栏下方开始（原型 `.dhead{padding-top:96px}` = 状态栏 + 64 的顶栏），底色铺在它们后面。
 *
 * **封面与文字栏这一行由 [PosterInfoRow] 排**：文字栏底对齐封面，比封面高的部分先**向上**溢出到顶栏下方的
 * 空白里（最多一个顶栏高），用完这个额度才把整行往下撑。默认字号下两行标题 + 原名只溢出约 22dp，所以标题一行还是两行、
 * 骨架还是真实内容，标签行与选项卡都在同一位置（行高若随标题行数变：骨架按两行预留、真实标题多为一行，
 * 内容出现时下方整块会上跳约 22dp）。放大字号时溢出超过额度，行才变高；骨架按两行预留，内容出现只会上收。
 * 文字栏起点在封面右侧（16 + 108 + 16），伸进顶栏时碰不到返回键；额度只到顶栏，碰不到状态栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectDetailsHeaderFrame(
    topPadding: Dp,
    wash: @Composable () -> Unit,
    poster: @Composable (Modifier) -> Unit,
    info: @Composable ColumnScope.() -> Unit,
    tags: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .testTag(DetailsBannerTag)
                .fillMaxWidth()
                .height(topPadding + WASH_HEIGHT_BELOW_STATUS_BAR)
                // 放大后的封面与渐变只绘制在底色区域内，避免溢出到标签行。
                .clipToBounds(),
        ) {
            wash()
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            PosterInfoRow(
                overflowAllowance = TopAppBarDefaults.TopAppBarExpandedHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topPadding + TopAppBarDefaults.TopAppBarExpandedHeight)
                    .padding(horizontal = 16.dp),
                poster = {
                    poster(
                        Modifier
                            .width(POSTER_WIDTH)
                            .aspectRatio(ChogumiDimens.CoverRatio)
                            .testTag(DetailsPosterTag),
                    )
                },
                info = {
                    Column(
                        modifier = Modifier.padding(bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        content = info,
                    )
                },
            )
            tags()
        }
    }
}

/**
 * 封面 + 文字栏一行（间距 16，文字栏占满剩余宽度、底对齐封面）。行高 = max(封面高, 文字栏高 − [overflowAllowance])：
 * 文字栏比封面高时先向上溢出、最多 [overflowAllowance]，超出才撑高本行。见 [SubjectDetailsHeaderFrame]。
 */
@Composable
private fun PosterInfoRow(
    overflowAllowance: Dp,
    poster: @Composable () -> Unit,
    info: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(poster, info), modifier = modifier) { (posterMeasurables, infoMeasurables), constraints ->
        val gap = POSTER_INFO_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val posterPlaceable = posterMeasurables.single().measure(loose)
        val infoWidth = (constraints.maxWidth - posterPlaceable.width - gap).coerceAtLeast(0)
        val infoPlaceable = infoMeasurables.single().measure(loose.copy(minWidth = infoWidth, maxWidth = infoWidth))
        val height = maxOf(posterPlaceable.height, infoPlaceable.height - overflowAllowance.roundToPx())
        layout(constraints.maxWidth, height) {
            posterPlaceable.place(0, height - posterPlaceable.height)
            infoPlaceable.place(posterPlaceable.width + gap, height - infoPlaceable.height)
        }
    }
}

/** 封面与文字栏的间距（原型 `.dhead` 的 `gap:16px`；设计决定，not in M3）。 */
private val POSTER_INFO_GAP = 16.dp

/**
 * 作品详情头部（原型 `.dhead`）：封面模糊底色 + 封面 + 标题 / 原名 / 元信息行 / 两枚小标，下面一行标签。
 *
 * - 元信息行取自规格表（[subjectMetaParts]）；
 * - 评分小标：公共评分开且有评分才画（[subjectRatingText] 唯一判定）；[onRatingClick] 为 null 时不可点（统计 tab 不可见）；
 * - 「资料」小标打开资料面板；
 * - 点封面全屏看原图（[ImageViewer]）。
 */
@Composable
internal fun SubjectDetailsHeader(
    subject: SubjectBo,
    topPadding: Dp,
    features: AppFeatures,
    onRatingClick: (() -> Unit)?,
    onInfoClick: () -> Unit,
    onTagClick: (SearchCriteria) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = displayNameOf(subject)
    val imageUrl = subject.coverUrl
    val slots = firstScreenSlots(subject)
    var viewingCover by rememberSaveable { mutableStateOf(false) }

    SubjectDetailsHeaderFrame(
        topPadding = topPadding,
        modifier = modifier,
        wash = { HeaderWash(imageUrl = imageUrl) },
        // 封面阴影 6dp（原型 `.poster` 的 box-shadow；设计决定，not in M3）
        poster = { posterModifier ->
            SubjectPoster(
                imageUrl,
                posterModifier,
                description = title,
                shadowElevation = 6.dp,
                shape = PosterShape,
                // 点封面看原图
                onClick = imageUrl?.let { { viewingCover = true } },
            )
        },
        info = {
            HeaderTitleLines(subject = subject)
            MetaLine(slots.meta)
            HeaderChips(
                ratingText = subjectRatingText(subject.ratingScore, features, ::formatScore),
                onRatingClick = onRatingClick,
                onInfoClick = onInfoClick,
            )
        },
        tags = {
            TagRow(
                fill = slots.tags,
                onTagClick = { chip ->
                    // 这里是全项目唯一知道「标签来自哪个条目类型」的地方：带上类型，搜索页据此切到同类型。
                    val source = if (chip.isMeta) TagSource.PUBLIC else TagSource.USER
                    tagSearchCriteria(subject.type, chip.name, source)?.let(onTagClick)
                },
            )
        },
    )
    if (viewingCover && imageUrl != null) {
        ImageViewer(listOf(imageUrl), startIndex = 0, onDismiss = { viewingCover = false })
    }
}

/**
 * 头部的骨架：与 [SubjectDetailsHeader] 同一个 [SubjectDetailsHeaderFrame]；`subject = null` 时
 * [firstScreenSlots] 把每个槽都判成占位，各区块因而取自真实区块的同一份排版，不另写几何。
 * 两枚小标的行高固定，骨架照样排一枚「资料」小标占住这一行，但只画成同尺寸的占位胶囊（[HeaderChip] 的 skeleton），
 * 不露出真实的图标与文字——否则骨架阶段整块灰底上会单独冒出一枚「ⓘ 资料」。
 */
@Composable
internal fun SubjectDetailsHeaderSkeleton(
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val slots = firstScreenSlots(null)

    SubjectDetailsHeaderFrame(
        topPadding = topPadding,
        modifier = modifier,
        wash = { SkeletonBox(Modifier.fillMaxSize()) },
        poster = { posterModifier -> SkeletonBox(posterModifier, shape = PosterShape) },
        info = {
            HeaderTitleLines(subject = null)
            MetaLine(slots.meta)
            HeaderChips(ratingText = null, onRatingClick = null, onInfoClick = {}, skeleton = true)
        },
        tags = { TagRow(fill = slots.tags, onTagClick = {}) },
    )
}

/**
 * 顶部底色（原型 `.wash`）：模糊放大的封面（[CoverWash]）45% 不透明，再叠一层到 `surface` 的渐变（92% 处到底）。
 * 低版本没有模糊，只剩半透明图与渐变（与原型的已知偏差）。
 */
@Composable
private fun HeaderWash(imageUrl: String?) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.fillMaxSize()) {
        CoverWash(imageUrl, alpha = WASH_ALPHA)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, WASH_GRADIENT_END to surface)),
        )
    }
}

/** 底色参数（原型 `.wash img` 与 `::after`；设计决定，not in M3）。 */
private const val WASH_ALPHA = 0.45f
private const val WASH_GRADIENT_END = 0.92f

/**
 * 标题（`headlineSmallEmphasized`，最多 2 行）与原名（与标题相同时不画），各自长按复制全文（[copyOnLongPress]）。[subject] 为 null 时画骨架：
 * 标题按上界画两行、原名照画——真实内容只会比它矮（文字栏底对齐，矮了只往上收）。
 */
@Composable
private fun HeaderTitleLines(subject: SubjectBo?) {
    val titleStyle = MaterialTheme.typography.headlineSmallEmphasized
    val originalStyle = MaterialTheme.typography.bodyMedium
    if (subject == null) {
        // 按两行预留：默认字号下多出的那行落在 PosterInfoRow 的向上溢出额度里，不影响下方；放大字号时骨架是最大情况，内容只会上收
        SkeletonText(titleStyle, Modifier.testTag(DetailsTitleTag), lines = 2, widthFraction = 0.8f)
        SkeletonText(originalStyle, widthFraction = 0.6f)
        return
    }
    val title = displayNameOf(subject)
    Text(
        text = title,
        modifier = Modifier.testTag(DetailsTitleTag).copyOnLongPress(title),
        style = titleStyle,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (subject.name.isNotBlank() && subject.name != title) {
        Text(
            text = subject.name,
            modifier = Modifier.copyOnLongPress(subject.name),
            style = originalStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 元信息行（原型 `.metaline`）：一行怎么排、放不下截哪段见 [SubjectMetaText]（全 App 唯一），这里只管真实内容与骨架。
 *
 * **固定一行**（设计稿「一行元信息」；原型 `.metaline` 虽然 `flex-wrap:wrap`，但那一行的高度会随字号与数据变，
 * 而它上方的封面/标题栏底对齐，一旦折成两行就把封面、标签行、选项卡整块往下推——正是「内容出现整体下移」。
 */
@Composable
internal fun MetaLine(fill: SlotFill<List<SubjectMetaPart>>) {
    // 元信息一行用 Regular 字重的 bodyMedium（与 labelLarge 同为 14/20，只是不加粗）：以中文类型字为准减轻数字的
    // 视觉字重，真实内容与骨架共用；选这个字阶是设计决定（not in M3）。
    val style = MaterialTheme.typography.bodyMedium
    when (fill) {
        is SlotFill.Real -> SubjectMetaText(
            parts = fill.value,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag(DetailsMetaTag)
                .fillMaxWidth(),
        )
        SlotFill.Placeholder -> SkeletonText(style, Modifier.testTag(DetailsMetaTag), widthFraction = 0.7f)
        SlotFill.Absent -> Unit
    }
}

/**
 * 两枚小标（原型 `.hchips .ratechip`）：「★ 7.2」与「ⓘ 资料」。
 * [ratingText] 为 null 时不画评分小标（判定在 [subjectRatingText]）。
 */
@Composable
private fun HeaderChips(
    ratingText: String?,
    onRatingClick: (() -> Unit)?,
    onInfoClick: () -> Unit,
    skeleton: Boolean = false,
) {
    Row(
        modifier = Modifier.testTag(DetailsHeaderChipsTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ratingText != null) {
            // 小标不可点（统计 tab 不可见）时，描述不念「查看统计」
            val description = stringResource(if (onRatingClick != null) R.string.detail_rating_chip_desc else R.string.detail_rating_chip_static_desc, ratingText)
            HeaderChip(
                icon = R.drawable.star_filled_24,
                onClick = onRatingClick,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                // 只写分数，不写评分人数（人数留给统计 tab）
                Text(ratingText)
            }
        }
        HeaderChip(icon = R.drawable.info_24, onClick = onInfoClick.takeIf { !skeleton }, skeleton = skeleton) {
            Text(stringResource(R.string.detail_info))
        }
    }
}

/**
 * 标签行（原型 `.tagrow`）：单行横向滚动，形式标签在前（`onSurface`、500），用户标签在后（`onSurfaceVariant`、400）。
 * 去重规则在 [subjectTagRow]。标签可点：带上条目类型去搜索。
 * 上 14 / 下 2：chip 的 48dp 触控区在 32dp 的 chip 上下各多出 8，这里只补上 6、下 0。
 */
@Composable
private fun TagRow(
    fill: SlotFill<List<SubjectTagChip>>,
    onTagClick: (SubjectTagChip) -> Unit,
) {
    if (fill == SlotFill.Absent) return
    Row(
        modifier = Modifier
            .testTag(DetailsTagsTag)
            .fillMaxWidth()
            .padding(top = 6.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (fill) {
            is SlotFill.Real -> fill.value.forEach { chip ->
                AssistChip(
                    onClick = { onTagClick(chip) },
                    label = {
                        // 官方分类标签用 chip 默认的 labelLarge（Medium），用户标签用同尺寸的 bodyMedium（Regular）以示区分（设计决定，not in M3）
                        Text(
                            text = chip.name,
                            style = if (chip.isMeta) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (chip.isMeta) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                )
            }
            SlotFill.Placeholder -> repeat(SKELETON_TAG_CHIP_COUNT) { index -> TagChipSkeleton(skeletonTagChipWidth(index)) }
            SlotFill.Absent -> Unit
        }
    }
}

/**
 * 骨架里画几枚 chip、第 i 枚（从 0 起）画多宽。
 * **本项目设计决定（not in M3）**：只影响横向，高度走官方 chip 的布局。
 */
private const val SKELETON_TAG_CHIP_COUNT = 5
private fun skeletonTagChipWidth(index: Int): Dp = (50 + index * 10).dp

/** 标签占位也走真实 AssistChip 的排版路径，触控区与随字号增长的标签高度由官方组件统一决定。 */
@Composable
private fun TagChipSkeleton(width: Dp) {
    AssistChip(
        onClick = {},
        enabled = false,
        modifier = Modifier.clearAndSetSemantics {},
        label = { SkeletonText(LocalTextStyle.current, Modifier.width(width)) },
    )
}
