package io.github.touhougo.chogumi.core.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.touhougo.chogumi.core.model.CoverQuality
import kotlin.math.max

/**
 * 一张封面画成 [width] × [height] 像素的位图：按比例放大铺满再居中裁切（与 `SubjectPoster` 的 Crop 一样），四角 [cornerPx] 圆角。
 * 给只能收位图的地方（桌面小组件：RemoteViews 不跑 Coil，Android 12 以下也不替图片裁圆角）。
 *
 * 取图走应用唯一的 Coil 加载器，缩放档由 [CoverSizeInterceptor] 按 [width] 与 [quality] 选（[coverUrlFor]）。取不到为 null。
 */
suspend fun coverBitmapOf(context: Context, url: String, width: Int, height: Int, cornerPx: Float, quality: CoverQuality): Bitmap? {
    val request = coverRequestOf(context, url, quality, blurred = false).newBuilder()
        .size(width, height)
        .allowHardware(false)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val source = result.image.toBitmap()
    val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
    val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((width - source.width * scale) / 2, (height - source.height * scale) / 2)
            },
        )
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawRoundRect(
        RectF(0f, 0f, width.toFloat(), height.toFloat()),
        cornerPx,
        cornerPx,
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader },
    )
    return out
}
