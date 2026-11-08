package io.github.touhougo.chogumi.core.ui.color

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import kotlin.coroutines.cancellation.CancellationException

/*
 * 从头像提取一个代表色，用于「我的」页顶部背景渐变。
 *
 * ## 为什么不用真正的高斯模糊
 *
 * 需求是"模糊取色"，但 `Modifier.blur` / `RenderEffect` 走 GPU，是**每帧**开销，
 * 放在常驻的页面头部并不划算。这里把"模糊"换成 [BLUR_SAMPLE_SIZE]×[BLUR_SAMPLE_SIZE]
 * 的**降采样**：让 Coil 直接按目标尺寸解码头像（`BitmapFactory` 的 `inSampleSize`
 * 在解码阶段就完成缩采样，不额外分配中间大图），再对这批像素求均值。
 *
 * 数学上，对整图求均值 == 对整图做一次均值模糊后取中心像素；
 * 而这个尺寸下**肉眼已无法分辨和真模糊的差别**——因为结果只被用来生成一层
 * 低饱和渐变，本身就不需要细节。于是：
 *
 * - **不占 GPU**：全程没有 RenderEffect、没有 RenderNode；
 * - **CPU 只算一次**：32×32 = 1024 次整数累加，微秒级；
 * - **解码不占主线程**：`ImageLoader.execute` 自己切线程；
 * - **重组不重算**：结果记在 `remember(url)` 里；重新进入页面会再取一次（这个尺寸的请求与页面上显示头像的请求
 *   内存缓存键不同，要再解码一次，磁盘缓存是共用的）。
 *
 * 采样点已经是解码后的位图，不需要再逐像素做色彩空间转换——直接取 ARGB 分量累加。
 */

/**
 * 降采样边长。32 是"足够稳定"与"足够便宜"的平衡点：再小会被单像素噪声带偏。
 */
private const val BLUR_SAMPLE_SIZE = 32

/**
 * 判定"这个颜色能不能当主题色用"的最低饱和度。
 *
 * 黑白漫画头像求均值必然是灰的（实测某账号得到 #A4A4A4），
 * 灰色背景看起来像渲染坏了，而不是设计。这类结果一律判为不可用，
 * 由调用方回退到主题色——**宁可显示应用的粉色，也不要显示一块脏灰**。
 */
private const val MIN_USABLE_SATURATION = 0.15f

/** 背景色饱和度下限：太淡的色在浅色主题下等于没有。 */
private const val MIN_BG_SATURATION = 0.35f

/** 背景色明度区间：够显眼，又不至于压过其上的文字。 */
private const val MIN_BG_VALUE = 0.62f
private const val MAX_BG_VALUE = 0.88f

/** 整图均值，忽略全透明像素（PNG 头像常有透明边）。 */
private fun Bitmap.averageColorOrNull(): Color? {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return averageColorOfPixels(pixels)
}

/**
 * 均值 → 可用的强调色；饱和度过低时返回 null（调用方回退主题色）。
 *
 * 只做判废与色调归位，不做饱和度增强：
 * 灰阶没有色相可用，强行拉饱和只会得到偏色噪声。
 *
 * 归位的原因是渐变要**垫在文字下面**：头像均值常常是中间调（如 #A4A4A4 那样的亮度），
 * 直接铺满顶部会让深色文字难以辨认。这里只调整 chroma 与明度到"能当背景"的区间，
 * 色相保持用户头像的原样。
 */
internal fun Color.accentOrNull(): Color? {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val saturation = if (max <= 0f) 0f else (max - min) / max
    if (saturation < MIN_USABLE_SATURATION) return null
    return normalizeForBackground()
}

/**
 * 把颜色换算到 HSV，把明度收敛到 [MIN_BG_VALUE]..[MAX_BG_VALUE]，
 * 饱和度抬到 [MIN_BG_SATURATION] 以上，再换回 RGB。
 *
 * 色相不动——背景仍然是"用户自己的颜色"，只是保证它有足够的存在感、
 * 又不会亮到看不清其上的文字。
 *
 * HSV 换算用纯 Kotlin 实现，不依赖 `android.graphics.Color`：
 * 一来逻辑可被 JVM 单测直接覆盖，二来这段会被反复调用（每个像素一次判废不需，
 * 但每次取色一次），纯算术比跨 JNI 便宜。
 */
private fun Color.normalizeForBackground(): Color {
    val hsv = rgbToHsv(red, green, blue)
    val s = hsv[1].coerceAtLeast(MIN_BG_SATURATION)
    val v = hsv[2].coerceIn(MIN_BG_VALUE, MAX_BG_VALUE)
    return hsvToRgb(hsv[0], s, v)
}

/** RGB(0..1) → HSV，h 单位为度（0..360），s/v 为 0..1。 */
internal fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf(if (h < 0f) h + 360f else h, if (max == 0f) 0f else d / max, max)
}

/** HSV → RGB(0..1)。 */
internal fun hsvToRgb(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r1 + m, g1 + m, b1 + m)
}

/** 从解码结果里取位图；非位图（如矢量）不参与取色。 */
private fun Image.toBitmapOrNull(): Bitmap? = when (this) {
    is BitmapImage -> bitmap
    else -> null
}

/**
 * 提取 [url] 的代表色。
 *
 * 返回 [State]，初值 null（尚未算出）。图片加载器异步解码，返回后在调用协程对小尺寸像素求均值与强调色，
 * 得到结果触发重组。URL 为空或解码失败时保持 null，由调用方回退到主题色。
 */
@Composable
fun rememberImageColor(url: String?): State<Color?> {
    val context = LocalContext.current
    val loader: ImageLoader = context.imageLoader
    val state = remember(url) { mutableStateOf<Color?>(null) }

    LaunchedEffect(url, loader) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(url)
            // 让解码阶段就缩到目标尺寸，不额外分配中间大图
            .size(BLUR_SAMPLE_SIZE, BLUR_SAMPLE_SIZE)
            // 硬件位图不能 getPixels，必须显式关掉
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .build()
        val color = try {
            loader.execute(request).image?.toBitmapOrNull()?.averageColorOrNull()?.accentOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (color != null) state.value = color
    }
    return state
}

/** 供 JVM 单测使用：跳过位图，直接对已知像素求均值。 */
internal fun averageColorOfPixels(argb: IntArray): Color? {
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    for (p in argb) {
        val a = p ushr 24 and 0xFF
        if (a < 16) continue
        r += p ushr 16 and 0xFF
        g += p ushr 8 and 0xFF
        b += p and 0xFF
        n++
    }
    if (n == 0) return null
    return Color(r / n / 255f, g / n / 255f, b / n / 255f)
}
