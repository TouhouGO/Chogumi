package io.github.touhougo.chogumi.core.ui.image

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * 标志图的白边：Bangumi 上不少公司的标志图在白底上留了大片空白（万代南梦宫、网易游戏……），放进标志框后标志只剩一小块。
 * 四个角都是白的（或透明）时，裁到有内容的那一块，四边各留 [LOGO_TRIM_MARGIN] 的余量；底不是白的（KONAMI 的红底、Rockstar 的黑底）
 * 整张就是标志，不裁。
 */
internal object LogoTrim : Transformation() {
    override val cacheKey: String = "logo_trim"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        val bounds = logoContentBounds(pixels, input.width, input.height) ?: return input
        return Bitmap.createBitmap(input, bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top)
    }
}

/** 一块矩形，右、下边不含。 */
internal data class LogoBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * [pixels]（ARGB，按行）里标志内容的范围，含余量；四个角有一个不是白底、整张都是白底、或裁不掉多少（两边都不到 [LOGO_TRIM_MIN]）时为 null。
 */
internal fun logoContentBounds(pixels: IntArray, width: Int, height: Int): LogoBounds? {
    if (width == 0 || height == 0) return null
    val corners = listOf(0, width - 1, (height - 1) * width, height * width - 1)
    if (!corners.all { isBlank(pixels[it]) }) return null
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (!isBlank(pixels[y * width + x])) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    if (right < 0) return null
    val margin = (maxOf(right - left + 1, bottom - top + 1) * LOGO_TRIM_MARGIN).toInt()
    val bounds = LogoBounds(
        left = (left - margin).coerceAtLeast(0),
        top = (top - margin).coerceAtLeast(0),
        right = (right + 1 + margin).coerceAtMost(width),
        bottom = (bottom + 1 + margin).coerceAtMost(height),
    )
    val trimmedX = 1f - (bounds.right - bounds.left).toFloat() / width
    val trimmedY = 1f - (bounds.bottom - bounds.top).toFloat() / height
    return bounds.takeIf { trimmedX >= LOGO_TRIM_MIN || trimmedY >= LOGO_TRIM_MIN }
}

/** 近白或近透明的像素算白底：JPEG 的白底压缩后不是纯白。 */
private fun isBlank(argb: Int): Boolean {
    val alpha = argb ushr 24
    if (alpha < BLANK_ALPHA) return true
    val r = argb shr 16 and 0xFF
    val g = argb shr 8 and 0xFF
    val b = argb and 0xFF
    return r >= BLANK_LEVEL && g >= BLANK_LEVEL && b >= BLANK_LEVEL
}

private const val BLANK_ALPHA = 16
private const val BLANK_LEVEL = 235

/** 裁完四边留的余量：内容较长一边的 4%，边上的笔画不贴着裁口。 */
private const val LOGO_TRIM_MARGIN = 0.04f

/** 至少能裁掉一边的 8% 才裁：留白本来就少的标志原样画。 */
private const val LOGO_TRIM_MIN = 0.08f
