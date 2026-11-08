package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeAlign
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.CoverBadge
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.MissingImageIcon
import io.github.touhougo.chogumi.core.ui.composables.keyedListSaver
import io.github.touhougo.chogumi.core.ui.image.FailedImage
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln

// 帖子里的图片（以下几何都是设计决定，not in M3）：
// - 不裁内容：每张图按原比例画。宽不超过栏宽，也不放大（原图像素按 1px≈1dp）；只有高超过这一行宽的 4/3 时顶对齐裁切、
//   角上标「长图」；
// - 连续多张（之间只有空白 / 空行）按比例排行：同一行的图等高、连起来铺满栏宽；一行放几张取「行高最接近栏宽一半」的张数，
//   所以横向的宽图自己占一行，竖的手机截图几张并一行。单张图是只有一张的一组，同一个函数（imageRowsOf）定尺寸；
// - 点任意一张进全屏看图（core:ui 的 ImageViewer，全 App 唯一的看图处），翻页范围是这段正文里的全部图片；
// - 加载失败（十年前的外链图大多已失效）：单张收成一行「图片加载失败」小条、点开浏览器看原链接，
//   不留 16:9 的空灰框；一组里的那一格保持大小、居中画裂图图标；
// - 折叠线不切进图片（见 foldLineOf）。

/** 高度上限 = 这一行的宽 × 4/3。 */
private const val LONG_IMAGE_MAX_RATIO = 4f / 3f

/** 尺寸未知（还没加载、BBCode 也没写 `[img=W,H]`）时先按 16:9 占位。 */
private const val UNKNOWN_IMAGE_RATIO = 9f / 16f

/** 一组图的目标行高 = 栏宽 × 1/2：一行放几张取行高最接近它的张数。 */
private const val TARGET_ROW_HEIGHT_RATIO = 0.5f

private val ImageGap = 4.dp

/** 一组图片（[BBCodeText] 里的一个图片行）。[onOpen] 的参数是组内下标；放不满栏宽的行按所在这一栏的 [align] 摆。 */
@Composable
internal fun BBCodeImages(images: List<Block.Image>, onOpen: (Int) -> Unit, align: BBCodeAlign, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (images.size == 1) {
            val column = maxWidth
            Column(Modifier.fillMaxWidth(), horizontalAlignment = align.horizontal) {
                SingleImage(images.single(), column = column, onClick = { onOpen(0) })
            }
        } else {
            ImageRows(images, column = maxWidth, align = align, onOpen = onOpen)
        }
    }
}

/** 一张图在版面上的尺寸。[isLong]：高被这一行的上限截住，顶对齐裁掉了下面。 */
internal data class ImageCell(val width: Dp, val height: Dp, val isLong: Boolean)

/** 排好的一行：从组内第 [first] 张起的 [cells]，同一行的格子等高。 */
internal data class ImageRow(val first: Int, val cells: List<ImageCell>)

/**
 * 一组图怎么排（单张图是只有一张的一组）：[pixels] 是每张的原图像素尺寸（宽, 高；未知为 null，按 16:9 占位、不限放大），
 * [column] 是栏宽。
 *
 * 同一行的图等高、按各自的宽高比连起来铺满栏宽（中间隔 [ImageGap]）；从第一张起逐张加进这一行，加了之后行高离目标
 * （栏宽 × [TARGET_ROW_HEIGHT_RATIO]）更远就换行。行高不超过行里最矮那张的原图像素高（不放大，这一行就窄于栏宽），
 * 也不超过这一行宽的 [LONG_IMAGE_MAX_RATIO] 倍（超过的顶对齐裁切，标长图）。
 */
internal fun imageRowsOf(pixels: List<Pair<Float, Float>?>, column: Dp): List<ImageRow> {
    val sizes = pixels.map { it?.let { (width, height) -> validPixelsOf(width, height) } }
    val ratios = sizes.map { size -> size?.let { (width, height) -> width / height } ?: (1f / UNKNOWN_IMAGE_RATIO) }
    val target = column * TARGET_ROW_HEIGHT_RATIO
    fun justifiedHeight(first: Int, end: Int): Dp = (column - ImageGap * (end - first - 1)) / ratios.subList(first, end).sum()
    fun distance(first: Int, end: Int): Float = abs(ln(justifiedHeight(first, end) / target))

    val rows = mutableListOf<ImageRow>()
    var first = 0
    while (first < pixels.size) {
        var end = first + 1
        while (end < pixels.size && distance(first, end + 1) < distance(first, end)) end++
        val natural = sizes.subList(first, end).mapNotNull { size -> size?.second?.dp }.minOrNull()
        val drawn = natural?.let { minOf(justifiedHeight(first, end), it) } ?: justifiedHeight(first, end)
        val widths = ratios.subList(first, end).map { ratio -> drawn * ratio }
        val cap = (widths.fold(0.dp) { sum, width -> sum + width } + ImageGap * (widths.size - 1)) * LONG_IMAGE_MAX_RATIO
        rows += ImageRow(first, widths.map { width -> ImageCell(width, minOf(drawn, cap), drawn > cap) })
        first = end
    }
    return rows
}

/** 有效的图片像素尺寸；无法产生有限正布局尺寸的外部值一律视为尺寸未知。 */
private fun validPixelsOf(pixelWidth: Float, pixelHeight: Float): Pair<Float, Float>? {
    if (!pixelWidth.isFinite() || !pixelHeight.isFinite() || pixelWidth <= 0f || pixelHeight <= 0f) return null
    val ratio = pixelWidth / pixelHeight
    return if (ratio.isFinite() && ratio > 0f) pixelWidth to pixelHeight else null
}

/**
 * 一张图已知的：原图像素尺寸 [pixels]（宽, 高；先用 BBCode 写明的，加载成功后换成原图的；未知为 null）、加载失败了没有。
 * 图占多大只由它决定。
 */
private data class ImageFacts(val pixels: Pair<Float, Float>?, val failed: Boolean = false)

private fun declaredFactsOf(image: Block.Image) =
    ImageFacts(if (image.width != null && image.height != null) image.width.toFloat() to image.height.toFloat() else null)

/** [ImageFacts] 跟着图片地址存档：地址变了（楼层被编辑）不拿回旧图的尺寸。 */
private fun imageFactsSaver(url: String) = keyedListSaver<ImageFacts>(
    key = url,
    save = { facts -> listOf(facts.failed) + facts.pixels?.toList().orEmpty() },
    restore = { saved -> ImageFacts(if (saved.size == 3) saved[1] as Float to saved[2] as Float else null, saved[0] as Boolean) },
)

/**
 * 加载结果跟着所在的列表项存档，楼层划出屏幕再回来时第一帧就是最终尺寸。只记在组合里时，回来先按占位排、下一帧才换成
 * 真实尺寸：往回滑时这一层从顶上进来，随即缩出屏幕被丢掉，再进来又是占位，列表就卡在这一段来回跳。
 */
@Composable
private fun rememberImageFacts(image: Block.Image): MutableState<ImageFacts> =
    rememberSaveable(image.url, stateSaver = imageFactsSaver(image.url)) { mutableStateOf(declaredFactsOf(image)) }

@Composable
private fun SingleImage(image: Block.Image, column: Dp, onClick: () -> Unit) {
    var facts by rememberImageFacts(image)
    if (facts.failed) {
        FailedImage(image.url)
        return
    }
    val cell = imageRowsOf(listOf(facts.pixels), column).single().cells.single()
    ImageBox(cell.isLong, stringResource(R.string.image_open), onClick, Modifier.size(cell.width, cell.height)) {
        AsyncImage(
            model = image.url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            onSuccess = { facts = ImageFacts(it.result.image.width.toFloat() to it.result.image.height.toFloat()) },
            onError = { facts = facts.copy(failed = true) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ImageRows(images: List<Block.Image>, column: Dp, align: BBCodeAlign, onOpen: (Int) -> Unit) {
    val facts = images.mapIndexed { index, image -> key(index, image.url) { rememberImageFacts(image) } }
    val rows = imageRowsOf(facts.map { it.value.pixels }, column)
    val cells = rows.flatMap { it.cells }
    val openLabel = stringResource(R.string.image_open)
    // 每格是同一个父级下按组内顺序的子项：图加载出尺寸、换了行，格子和它的图不重建
    Layout(
        content = {
            images.forEachIndexed { index, image ->
                val state = facts[index]
                key(index, image.url) {
                    ImageBox(cells[index].isLong, openLabel, onClick = { onOpen(index) }) {
                        if (state.value.failed) {
                            MissingImageIcon(R.drawable.broken_image_24)
                        } else {
                            AsyncImage(
                                model = image.url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.TopCenter,
                                onSuccess = { loaded ->
                                    state.value = ImageFacts(loaded.result.image.width.toFloat() to loaded.result.image.height.toFloat())
                                },
                                onError = { state.value = state.value.copy(failed = true) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = ImageGap.roundToPx()
        // 格宽向下取整：一行的格子连起来不超出栏宽
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(floor(cells[index].width.toPx()).toInt(), cells[index].height.roundToPx()))
        }
        val width = constraints.maxWidth
        val tops = IntArray(rows.size)
        var y = 0
        rows.forEachIndexed { r, row ->
            if (r > 0) y += gap
            tops[r] = y
            y += placeables[row.first].height
        }
        layout(width, constraints.constrainHeight(y)) {
            rows.forEachIndexed { r, row ->
                val inRow = placeables.subList(row.first, row.first + row.cells.size)
                var x = align.horizontal.align(inRow.sumOf { it.width } + gap * (inRow.size - 1), width, layoutDirection)
                inRow.forEach { placeable ->
                    placeable.place(x, tops[r])
                    x += placeable.width + gap
                }
            }
        }
    }
}

/** 一格图：小圆角、未加载时的底色，点开看图；高被截住、裁掉了下面的（[isLong]）角上标「长图」。尺寸由调用方给。 */
@Composable
private fun ImageBox(
    isLong: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = onClickLabel, onClick = onClick),
    ) {
        content()
        if (isLong) {
            CoverBadge(stringResource(R.string.bbcode_long_image), Modifier.align(Alignment.BottomEnd).padding(CoverBadgeInset))
        }
    }
}
