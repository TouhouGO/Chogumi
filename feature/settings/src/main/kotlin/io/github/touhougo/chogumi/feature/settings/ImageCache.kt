package io.github.touhougo.chogumi.feature.settings

import android.content.Context
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图片缓存的占用与清理。占用只量不记：界面画的永远是刚量出来的那个数。 */
interface ImageCache {
    /** 磁盘上缓存的图片现在占多少字节。 */
    suspend fun size(): Long

    /** 清掉磁盘与内存里缓存的图片；之后需要时重新下载。 */
    suspend fun clear()
}

/** 应用唯一的 Coil 加载器（`SingletonImageLoader`）的缓存；磁盘上的量与删都在 IO 线程做。 */
class CoilImageCache(private val context: Context) : ImageCache {
    override suspend fun size(): Long = withContext(Dispatchers.IO) {
        SingletonImageLoader.get(context).diskCache?.size ?: 0L
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            val loader = SingletonImageLoader.get(context)
            loader.diskCache?.clear()
            loader.memoryCache?.clear()
        }
    }
}
