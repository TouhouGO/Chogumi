package io.github.touhougo.chogumi.core.ui.image

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.ui.theme.contentColorScheme
import io.github.touhougo.chogumi.core.ui.theme.isDarkThemeActive
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 封面取色的计算上下文由应用组合根下发；没有配置时不取色，预览沿用页面自身的配色。 */
val LocalCoverComputationDispatcher = staticCompositionLocalOf<CoroutineDispatcher?> { null }

/**
 * 一张封面的配色（M3 content-based color）——全 App 从封面取色只经这里（另一种取法 [rememberCoverBands] 与它共用一次解码）。
 *
 * 规范的做法（color-schemes.md「Dynamic color schemes」）：图经量化取出一个源色，再按 M3 的色调规则生成整套角色色，
 * 对比度由规则保证；用在紧挨着那张封面的容器上（同上：content-based color is best used for contained screen
 * elements adjacent to the source image）。调用方从结果里取成对的角色色（容器与它的 on 色），不自己调色。
 *
 * 取的是模糊底图（`CoverWash`）的那一档小图，与底图共用一次下载。还没取到（图在路上、取不到、
 * 封面里挑不出够鲜明的颜色）时为 null，调用方用页面自己的配色。深浅跟着 App 主题（[isDarkThemeActive]）。
 */
@Composable
fun rememberCoverScheme(url: String?): ColorScheme? {
    val source = rememberCoverColors(url)?.source
    val dark = isDarkThemeActive()
    return remember(source, dark) { source?.let { contentColorScheme(Color(it), dark) } }
}

/**
 * 封面自上而下三段（上、中、下各三分之一）的颜色：书脊顺着封面的颜色走，抽出来的封面接得上。
 * 每段是那一段像素的加权平均：越鲜艳、越不靠近纯黑纯白的像素分量越重（灰白的边与留白不把颜色冲淡）。
 * 这是画书脊用的原色，不是 M3 的角色色：调用方按它的亮度自己挑字色。
 */
@Immutable
data class CoverBands(val top: Color, val middle: Color, val bottom: Color)

/** 取封面的 [CoverBands]（与 [rememberCoverScheme] 共用一次解码与缓存）；还没取到（图在路上、取不到）时为 null。 */
@Composable
fun rememberCoverBands(url: String?): CoverBands? = rememberCoverColors(url)?.bands

/** 一张封面取出的颜色（一次解码同时取）：M3 源色（挑不出时 null）与上中下三段的颜色。 */
internal class CoverColors(val source: Int?, val bands: CoverBands)

@Composable
private fun rememberCoverColors(url: String?): CoverColors? {
    val context = LocalPlatformContext.current
    val dispatcher = LocalCoverComputationDispatcher.current
    val colors by produceState(initialValue = url?.let(CoverColorCache::get), url, context, dispatcher) {
        value = url?.let { CoverColorCache.get(it) ?: dispatcher?.let { computation -> coverColorsOf(context, it, computation) } }
    }
    return colors
}

/**
 * 封面 → 颜色：取小图、缩到 [QUANTIZE_WIDTH] × [QUANTIZE_HEIGHT]。源色是量化成至多 [QUANTIZE_COLORS] 种颜色后
 * 按 M3 的打分（色相占比与彩度）挑的一个，挑不出（灰白封面）时为 null；三段颜色同一份像素按行切三段求出。取图失败为 null。
 */
private suspend fun coverColorsOf(context: PlatformContext, url: String, dispatcher: CoroutineDispatcher): CoverColors? {
    val request = coverRequestOf(context, url, CoverQuality.AUTO, blurred = true).newBuilder().allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return coverColorsOf(dispatcher) { result.image.toBitmap() }.also { CoverColorCache.put(url, it) }
}

/** 同一份封面像素的全部取色运算只在传入的计算上下文执行。 */
internal suspend fun coverColorsOf(dispatcher: CoroutineDispatcher, bitmap: suspend () -> Bitmap): CoverColors = withContext(dispatcher) {
    val small = Bitmap.createScaledBitmap(bitmap(), QUANTIZE_WIDTH, QUANTIZE_HEIGHT, true)
    val pixels = IntArray(QUANTIZE_WIDTH * QUANTIZE_HEIGHT)
    small.getPixels(pixels, 0, QUANTIZE_WIDTH, 0, 0, QUANTIZE_WIDTH, QUANTIZE_HEIGHT)
    val source = Score.score(QuantizerCelebi.quantize(pixels, QUANTIZE_COLORS), desired = 1, fallbackColorArgb = NO_SOURCE)
        .first()
        .takeUnless { it == NO_SOURCE }
    val rows = QUANTIZE_HEIGHT / BAND_COUNT
    val bands = List(BAND_COUNT) { band -> bandColor(pixels, fromRow = band * rows, toRow = (band + 1) * rows) }
    CoverColors(source, CoverBands(bands[0], bands[1], bands[2]))
}

/**
 * [fromRow] 到 [toRow]（不含）这几行像素的加权平均色。权重 = [BAND_BASE_WEIGHT] + 饱和度 ×（1 − |亮度 − 0.5| × [BAND_LIGHTNESS_FALLOFF]），
 * 饱和度与亮度按 HSL 算（设计决定，not in M3）。
 */
private fun bandColor(pixels: IntArray, fromRow: Int, toRow: Int): Color {
    var red = 0f
    var green = 0f
    var blue = 0f
    var total = 0f
    for (index in fromRow * QUANTIZE_WIDTH until toRow * QUANTIZE_WIDTH) {
        val pixel = pixels[index]
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val lightness = (max + min) / 2
        val saturation = when {
            max == min -> 0f
            lightness > 0.5f -> (max - min) / (2 - max - min)
            else -> (max - min) / (max + min)
        }
        val weight = BAND_BASE_WEIGHT + saturation * (1 - abs(lightness - 0.5f) * BAND_LIGHTNESS_FALLOFF)
        red += r * weight
        green += g * weight
        blue += b * weight
        total += weight
    }
    return Color(red / total, green / total, blue / total)
}

/** 取过的颜色（按封面地址）：滚回来、换页再来不再解码一次。只留取到了图的。 */
private object CoverColorCache : LruCache<String, CoverColors>(COVER_COLOR_CACHE_SIZE)

/** 记几张封面的颜色：一页货架上的封面都放得下（设计决定，not in M3）。 */
private const val COVER_COLOR_CACHE_SIZE = 256

/** 量化前缩到的尺寸：3:4，取主色不需要更多像素（设计决定，not in M3）。 */
private const val QUANTIZE_WIDTH = 48
private const val QUANTIZE_HEIGHT = 64

/** 量化成几种颜色：M3 取色实现的常用值（设计决定，not in M3 规范正文）。 */
private const val QUANTIZE_COLORS = 128

/** 打分挑不出颜色时的返回值：全透明不是任何封面的源色。 */
private const val NO_SOURCE = 0

/** 封面自上而下切几段（[CoverBands]）。 */
private const val BAND_COUNT = 3

/** 三段颜色的权重：灰像素也有的底分、亮度离 0.5 越远权重掉得越快的系数（原型 `BAND`，设计决定，not in M3）。 */
private const val BAND_BASE_WEIGHT = 0.3f
private const val BAND_LIGHTNESS_FALLOFF = 1.4f
