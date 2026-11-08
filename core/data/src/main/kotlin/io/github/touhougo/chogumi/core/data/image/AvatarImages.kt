package io.github.touhougo.chogumi.core.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用户选的图片 → 要上传的头像（正方形的小 JPEG）。 */
interface AvatarImages {
    /** [uri] 是系统选图器给的地址；读不了的图（不是图片、文件没了）是 [RejectReason.IMAGE_UNREADABLE]。 */
    suspend fun squareJpeg(uri: String): Outcome<ByteArray>
}

/**
 * 在设备上按 [squareCropOf] 居中裁成正方形，照片的拍摄方向（EXIF）转正，大于 [AVATAR_SIZE] 的缩到它，压成 JPEG。
 * 网页的头像框收什么尺寸没有写明，先在设备上把图变小，上传的只是这一张小图（设计决定，not in M3）。
 */
class DeviceAvatarImages(private val context: Context) : AvatarImages {

    override suspend fun squareJpeg(uri: String): Outcome<ByteArray> = outcomeOf {
        withContext(Dispatchers.IO) { encode(Uri.parse(uri)) }
    }

    private fun encode(uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw unreadable()
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: throw unreadable()
        val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees() } ?: 0
        val crop = squareCropOf(decoded.width, decoded.height)
        val scale = AVATAR_SIZE.toFloat() / crop.size
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val square = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.size, crop.size, matrix, true)
        return ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}

/** 正方形裁剪框：取短边为边长，居中。 */
internal data class SquareCrop(val left: Int, val top: Int, val size: Int)

internal fun squareCropOf(width: Int, height: Int): SquareCrop {
    val size = minOf(width, height)
    return SquareCrop(left = (width - size) / 2, top = (height - size) / 2, size = size)
}

/** 解码时的缩小倍数：2 的幂，缩完短边仍不小于 [AVATAR_SIZE]（大照片不整张解进内存）。 */
internal fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= AVATAR_SIZE) sample *= 2
    return sample
}

private fun ExifInterface.rotationDegrees(): Int = when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90
    ExifInterface.ORIENTATION_ROTATE_180 -> 180
    ExifInterface.ORIENTATION_ROTATE_270 -> 270
    else -> 0
}

private fun unreadable() = FailureException(Failure.Rejected(RejectReason.IMAGE_UNREADABLE))

/** 上传的头像边长（像素）：网页最大一档头像显示得比这小（设计决定，not in M3）。 */
internal const val AVATAR_SIZE = 512

private const val JPEG_QUALITY = 90
