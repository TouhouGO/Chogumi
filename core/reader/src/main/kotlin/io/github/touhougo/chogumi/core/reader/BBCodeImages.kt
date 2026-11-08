package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeAlign
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.CoverBadge
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.MissingImageIcon
import io.github.touhougo.chogumi.core.ui.composables.keyedListSaver
import io.github.touhougo.chogumi.core.ui.image.FailedImage

// 帖子里的图片（成熟社区的通行做法；以下几何都是设计决定，not in M3）：
// - 单张：保持原比例，宽取「原图像素按 1px≈1dp」与栏宽的较小者；高超过宽的 4/3 时顶对齐裁切、角上标「长图」；
// - 连续多张（之间只有空白 / 空行）：方格宫格，2、4 张两列，其余三列，居中裁切；
// - 点任意一张进全屏看图（core:ui 的 ImageViewer，全 App 唯一的看图处），翻页范围是这段正文里的全部图片；
// - 加载失败（十年前的外链图大多已失效）：单张收成一行「图片加载失败」小条、点开浏览器看原链接，
//   不留 16:9 的空灰框；宫格里的格子保持大小、居中画裂图图标；
// - 折叠线不切进图片（见 foldLineOf）。

/** 单张图的高度上限 = 宽 × 4/3。 */
private const val LONG_IMAGE_MAX_RATIO = 4f / 3f

/** 尺寸未知（还没加载、BBCode 也没写 `[img=W,H]`）时先按 16:9 占位。 */
private const val UNKNOWN_IMAGE_RATIO = 9f / 16f

private val ImageGap = 4.dp

/** 一组图片（[BBCodeText] 里的一个图片行）。[onOpen] 的参数是组内下标；单张图按所在这一栏的 [align] 摆（宫格占满栏宽）。 */
@Composable
internal fun BBCodeImages(images: List<Block.Image>, onOpen: (Int) -> Unit, align: BBCodeAlign, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (images.size == 1) {
            val column = maxWidth
            Column(Modifier.fillMaxWidth(), horizontalAlignment = align.horizontal) {
                SingleImage(images.single(), column = column, onClick = { onOpen(0) })
            }
        } else {
            ImageGrid(images, column = maxWidth, onOpen = onOpen)
        }
    }
}

/** 图片宫格的列数：2、4 张两列，其余三列。 */
internal fun imageGridColumns(count: Int): Int = if (count == 2 || count == 4) 2 else 3

/**
 * 单张图的显示尺寸（宽, 高, 是否长图）。[pixelWidth] / [pixelHeight] 未知时按栏宽 16:9 占位。
 */
internal fun singleImageSize(pixelWidth: Float?, pixelHeight: Float?, column: Dp): Triple<Dp, Dp, Boolean> {
    val ratio = imageHeightRatio(pixelWidth, pixelHeight)
    if (ratio == null) {
        return Triple(column, column * UNKNOWN_IMAGE_RATIO, false)
    }
    val width = minOf(requireNotNull(pixelWidth).dp, column)
    val natural = width * ratio
    val cap = width * LONG_IMAGE_MAX_RATIO
    return Triple(width, minOf(natural, cap), natural > cap)
}

/** 有效图片像素的高宽比；无法产生有限正布局尺寸的外部值一律视为尺寸未知。 */
private fun imageHeightRatio(pixelWidth: Float?, pixelHeight: Float?): Float? {
    if (pixelWidth == null || pixelHeight == null || !pixelWidth.isFinite() || !pixelHeight.isFinite() ||
        pixelWidth <= 0f || pixelHeight <= 0f
    ) {
        return null
    }
    return (pixelHeight / pixelWidth).takeIf { it.isFinite() && it > 0f }
}

/**
 * 一张单图已知的：原图像素尺寸 [pixels]（宽, 高；先用 BBCode 写明的，加载成功后换成原图的；未知为 null）、加载失败了没有。
 * 单图占多大只由它决定。
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

@Composable
private fun SingleImage(image: Block.Image, column: Dp, onClick: () -> Unit) {
    // 加载结果跟着所在的列表项存档，楼层划出屏幕再回来时第一帧就是最终尺寸。只记在组合里时，回来先按占位排、下一帧才换成
    // 真实尺寸：往回滑时这一层从顶上进来，随即缩出屏幕被丢掉，再进来又是占位，列表就卡在这一段来回跳。
    var facts by rememberSaveable(image.url, stateSaver = imageFactsSaver(image.url)) { mutableStateOf(declaredFactsOf(image)) }
    if (facts.failed) {
        FailedImage(image.url)
        return
    }
    val (width, height, isLong) = singleImageSize(facts.pixels?.first, facts.pixels?.second, column)
    Box(
        Modifier
            .size(width, height)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = stringResource(R.string.image_open), onClick = onClick),
    ) {
        AsyncImage(
            model = image.url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            onSuccess = { facts = ImageFacts(it.result.image.width.toFloat() to it.result.image.height.toFloat()) },
            onError = { facts = facts.copy(failed = true) },
            modifier = Modifier.fillMaxSize(),
        )
        if (isLong) {
            CoverBadge(stringResource(R.string.bbcode_long_image), Modifier.align(Alignment.BottomEnd).padding(CoverBadgeInset))
        }
    }
}

@Composable
private fun ImageGrid(images: List<Block.Image>, column: Dp, onOpen: (Int) -> Unit) {
    val columns = imageGridColumns(images.size)
    val cell = (column - ImageGap * (columns - 1)) / columns
    val openLabel = stringResource(R.string.image_open)
    Column(verticalArrangement = Arrangement.spacedBy(ImageGap)) {
        images.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ImageGap)) {
                row.forEachIndexed { i, image ->
                    var failed by remember(image.url) { mutableStateOf(false) }
                    Box(
                        Modifier
                            .size(cell)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClickLabel = openLabel) { onOpen(rowIndex * columns + i) },
                    ) {
                        if (failed) {
                            MissingImageIcon(R.drawable.broken_image_24)
                        } else {
                            AsyncImage(
                                model = image.url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onError = { failed = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
