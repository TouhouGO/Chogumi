package io.github.touhougo.chogumi.core.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.Extras
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.Dimension
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.ui.common.LocalCoverQuality

/**
 * 条目封面取哪张图（全 App 选封面尺寸只经这里）。
 *
 * 接口给的封面原图（`large`，`…/pic/cover/l/…`）是上传的原尺寸：新番常有 1400–2000px 宽、300–800KB；
 * 一张 108dp 的海报卡在 3x 屏上只有 324px 宽。图床对同一张原图另有按宽缩放的几档，接口自己给出的就是这几档
 * （`grid` r/100、`small` r/200、`common` r/400、`medium` r/800；宽高比不变，原图不够宽时不放大），r/400 约 50KB。
 *
 * - [CoverQuality.AUTO]：取不窄于 [widthPx] 的最小一档；
 * - [CoverQuality.LARGE]：至少取最宽的一档（800）；
 * - [CoverQuality.ORIGINAL]：原图。
 *
 * 比最宽一档还宽、或不是封面原图的地址，原样返回。只按宽选：封面几乎都是竖图，宽够了按 3:4 裁切铺满时高也够；
 * 横图封面裁切后会略软一点，换来的是下载与解码都少一个数量级（设计决定，not in M3）。
 */
internal fun coverUrlFor(url: String, widthPx: Int, quality: CoverQuality): String {
    val needed = when (quality) {
        CoverQuality.AUTO -> widthPx
        CoverQuality.LARGE -> maxOf(widthPx, CoverSteps.last())
        CoverQuality.ORIGINAL -> return url
    }
    val match = CoverOriginal.matchEntire(url) ?: return url
    val step = CoverSteps.firstOrNull { it >= needed } ?: return url
    val (origin, path) = match.destructured
    return "$origin/r/$step$path"
}

/** 图床的封面原图地址：域名与原图路径分开取，缩放档插在两者之间。 */
private val CoverOriginal = Regex("""(https?://lain\.bgm\.tv)(/pic/cover/l/.+)""")

/** 接口给出的缩放档（像素宽），从窄到宽。 */
private val CoverSteps = listOf(100, 200, 400, 800)

/**
 * 一张图在别处可能已经取过的小图地址，宽的在前：作品封面原图按 [coverUrlFor] 的各缩放档取过，其余图只有地址本身。
 * 看原图时（[originalImageRequestOf]）从内存缓存里按这个顺序找一张先垫着。
 */
internal fun thumbnailUrlsOf(url: String): List<String> {
    val match = CoverOriginal.matchEntire(url) ?: return listOf(url)
    val (origin, path) = match.destructured
    return listOf(url) + CoverSteps.reversed().map { "$origin/r/$it$path" }
}

/**
 * 看原图的请求（全 App 取原图只经这里）：画质记成 [CoverQuality.ORIGINAL]，[CoverSizeInterceptor] 不缩档，下载、磁盘缓存的
 * 都是原图本身。[placeholder] 为 true 时，原图到之前先显示内存里已有的那张小图（[thumbnailUrlsOf]），不留一屏空白也不转圈；
 * 只取原始字节（保存、分享）时不要它，也不读内存缓存，好让原图必经磁盘缓存。
 */
internal fun originalImageRequestOf(context: PlatformContext, url: String, placeholder: Boolean): ImageRequest =
    ImageRequest.Builder(context).data(url).apply {
        extras[CoverQualityKey] = CoverQuality.ORIGINAL
        if (placeholder) {
            val memory = SingletonImageLoader.get(context).memoryCache
            placeholderMemoryCacheKey(thumbnailUrlsOf(url).firstOrNull { memory?.get(MemoryCache.Key(it)) != null })
        } else {
            memoryCachePolicy(CachePolicy.DISABLED)
        }
    }.build()

/** 一次封面请求带着的画质档位；没带的请求（封面以外的图）按默认档。 */
internal val CoverQualityKey = Extras.Key(default = CoverQuality.DEFAULT)

/**
 * 模糊底图（`CoverWash`）取图的宽：模糊 28dp 之后分不出 200px 与原图，铺满整屏宽的底图因此不按布局宽去取大图。
 * 档位仍只在 [coverUrlFor] 里选。
 */
internal const val CoverWashWidthPx = 200

/**
 * 一张作品封面的图片请求：带上设置里的封面画质（[LocalCoverQuality]）。改了画质，请求跟着换，屏上的封面重新取图。
 * 只给作品封面用（`SubjectPoster`、`CoverWash`）；[url] 为空时为 null，交给 `AsyncImage` 的 fallback。
 * [blurred] 是模糊底图：尺寸固定为 [CoverWashWidthPx]，画质设置不抬高它（设了「原图」也只取小档）。
 */
@Composable
internal fun rememberCoverRequest(url: String?, blurred: Boolean = false): ImageRequest? {
    val context = LocalPlatformContext.current
    val quality = LocalCoverQuality.current
    return remember(url, quality, context, blurred) { url?.let { coverRequestOf(context, it, quality, blurred) } }
}

internal fun coverRequestOf(context: PlatformContext, url: String, quality: CoverQuality, blurred: Boolean): ImageRequest =
    ImageRequest.Builder(context).data(url).apply {
        if (blurred) {
            size(CoverWashWidthPx)
            extras[CoverQualityKey] = CoverQuality.AUTO
        } else {
            extras[CoverQualityKey] = quality
        }
    }.build()

/**
 * 把 [coverUrlFor] 接进 Coil：拦截器拿到的尺寸已按布局解析成像素（`AsyncImage` 取自约束），
 * 每个封面因而按它实际的宽与请求带的画质取图。宽未定（不受约束）时按原图取。
 */
object CoverSizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val width = chain.size.width as? Dimension.Pixels ?: return chain.proceed()
        val sized = coverUrlFor(url, width.px, chain.request.getExtra(CoverQualityKey))
        if (sized == url) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(sized).build()).proceed()
    }
}
