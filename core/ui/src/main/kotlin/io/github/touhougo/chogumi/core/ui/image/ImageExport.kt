package io.github.touhougo.chogumi.core.ui.image

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import coil3.imageLoader
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.ui.common.shareFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 看图页的「保存」「分享」：都取原图在 Coil 磁盘缓存里的原始字节原样写出（GIF 仍是动图，不经解码再编码）。

/** 能保存、分享的图片格式：按文件头认，不信地址里的扩展名（图床常给 .jpg 的地址回 PNG / GIF）。 */
internal enum class ImageFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    BMP("image/bmp", "bmp"),
}

/** 按文件头认格式；认不出（SVG、残缺的文件）为 null，这样的图不保存、不分享。 */
internal fun imageFormatOf(head: ByteArray): ImageFormat? {
    fun at(index: Int, vararg values: Int) = head.size >= index + values.size &&
        values.withIndex().all { (i, v) -> head[index + i].toInt() and 0xFF == v }
    return when {
        at(0, 0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
        at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
        at(0, 0x47, 0x49, 0x46, 0x38) -> ImageFormat.GIF
        at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> ImageFormat.WEBP
        at(0, 0x42, 0x4D) -> ImageFormat.BMP
        else -> null
    }
}

/**
 * 写出的文件名：取地址最后一段的文件名主体（图床的文件名本身就是唯一的散列），只留字母、数字、`_`、`-`，扩展名按实际格式。
 * 同一张图再存一次得到同一个名字（相册里由系统去重或沿用已有那张），不按时刻起名。
 */
internal fun exportFileNameOf(url: String, format: ImageFormat): String {
    val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', missingDelimiterValue = "")
    val stem = path.substringAfterLast('/').substringBeforeLast('.')
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(MAX_STEM_LENGTH)
        .ifEmpty { "image" }
    return "$stem.${format.extension}"
}

private const val MAX_STEM_LENGTH = 64

/** 保存到相册的位置：Pictures/Chogumi。 */
private const val ALBUM = "Chogumi"

/** 分享前把图复制到 cache 下的这个目录，经 FileProvider 交给别的 App（路径登记在 core:res 的 xml/image_share_paths.xml）。 */
private const val SHARE_DIR = "shared_images"

/** 分享出去的副本留多久：一天里接收方随时还能读到。 */
private const val SHARE_KEEP_MILLIS = 24L * 60 * 60 * 1000

/** FileProvider 的 authority，与 core:ui 清单里登记的同一个。 */
private fun shareAuthorityOf(context: Context) = "${context.packageName}.images"

/** API 28 及以下保存到公共图片目录要 WRITE_EXTERNAL_STORAGE（清单里只对这些版本申请），29 起经 MediaStore 不用权限。 */
internal val saveNeedsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/** 一张原图的字节与格式。 */
private class OriginalImage(val bytes: ByteArray, val format: ImageFormat, val fileName: String)

/**
 * 原图的原始字节：先查磁盘缓存；没有（看图页里只到了内存里的小图，或磁盘缓存被清过）就按原图请求取一次，取到会写进磁盘缓存，再读。
 * 取不到、认不出格式为 null。
 */
private suspend fun originalImageOf(context: Context, url: String): OriginalImage? = withContext(Dispatchers.IO) {
    val loader = context.imageLoader
    val cache = loader.diskCache ?: return@withContext null
    fun read(): ByteArray? = try {
        cache.openSnapshot(url)?.use { snapshot -> cache.fileSystem.read(snapshot.data) { readByteArray() } }
    } catch (_: IOException) {
        null
    }
    val bytes = read() ?: run {
        loader.execute(originalImageRequestOf(context, url, placeholder = false))
        read()
    } ?: return@withContext null
    val format = imageFormatOf(bytes) ?: return@withContext null
    OriginalImage(bytes, format, exportFileNameOf(url, format))
}

/** 把 [url] 的原图存进相册，成功为 true。API 28 及以下调用前要已取得 WRITE_EXTERNAL_STORAGE（[saveNeedsStoragePermission]）。 */
internal suspend fun saveImageToGallery(context: Context, url: String): Boolean {
    val image = originalImageOf(context, url) ?: return false
    return withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) insertIntoMediaStore(context, image) else writeToPictures(context, image)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun insertIntoMediaStore(context: Context, image: OriginalImage) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, image.fileName)
        put(MediaStore.Images.Media.MIME_TYPE, image.format.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore 拒绝新建")
    try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("MediaStore 打不开输出流")
        stream.use { it.write(image.bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (failure: IOException) {
        resolver.delete(uri, null, null)
        throw failure
    }
}

/** API 28 及以下：写进公共图片目录再请媒体扫描收录（这些版本没有 MediaStore 的 RELATIVE_PATH）。 */
@Suppress("DEPRECATION")
private fun writeToPictures(context: Context, image: OriginalImage) {
    val album = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
    if (!album.isDirectory && !album.mkdirs()) throw IOException("建不了相册目录")
    val file = File(album, image.fileName)
    if (!file.exists()) file.writeBytes(image.bytes)
    MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(image.format.mimeType), null)
}

/**
 * 把 [url] 的原图交给系统分享面板，面板弹出为 true。
 *
 * 每次分享写进自己的子目录：文件名还是原图的名字，又不覆盖、不删前几次交出去的副本（接收方可能在面板关掉后才读，
 * 比如先编辑再发）。超过 [SHARE_KEEP_MILLIS] 的副本在下一次分享时清掉。
 */
internal suspend fun shareImage(context: Context, url: String): Boolean {
    val image = originalImageOf(context, url) ?: return false
    val uri = withContext(Dispatchers.IO) {
        try {
            val root = File(context.cacheDir, SHARE_DIR)
            if (!root.isDirectory && !root.mkdirs()) throw IOException("建不了分享目录")
            val cutoff = AppClock.SYSTEM.nowMillis() - SHARE_KEEP_MILLIS
            root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
            val dir = File(root, UUID.randomUUID().toString())
            if (!dir.mkdirs()) throw IOException("建不了分享目录")
            val file = File(dir, image.fileName).apply { writeBytes(image.bytes) }
            FileProvider.getUriForFile(context, shareAuthorityOf(context), file)
        } catch (_: IOException) {
            null
        }
    } ?: return false
    return context.shareFile(uri, image.format.mimeType)
}
