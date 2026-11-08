package io.github.touhougo.chogumi.core.ui.image

import android.app.Activity
import android.content.Intent
import android.net.Uri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.disk.DiskCache
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** 分享出去的副本：接收方可能在分享面板关掉后才读，下一次分享不能把它删掉或覆盖；过了一天才清。 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageShareLifetimeTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var directory: File
    private lateinit var cache: DiskCache
    private lateinit var loader: ImageLoader

    private val first = "GIF89a-first".toByteArray()
    private val second = "GIF89a-second".toByteArray()
    private val urls = listOf("https://example.invalid/one/image.gif", "https://example.invalid/two/image.gif")

    @BeforeTest
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        directory = Files.createTempDirectory("image-share-test").toFile()
        cache = DiskCache.Builder().directory(directory.toOkioPath()).build()
        loader = ImageLoader.Builder(activity).diskCache(cache).build()
        SingletonImageLoader.setUnsafe(loader)
        for ((url, bytes) in urls.zip(listOf(first, second))) {
            val editor = requireNotNull(cache.openEditor(url))
            cache.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
        }
    }

    @AfterTest
    fun tearDown() {
        SingletonImageLoader.reset()
        loader.shutdown()
        cache.shutdown()
        directory.deleteRecursively()
        controller.pause().stop().destroy()
    }

    /** 分享面板收到的那个地址。 */
    private fun sharedUri(): Uri {
        val chooser = Shadows.shadowOf(activity).nextStartedActivity
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        return requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

    private fun read(uri: Uri) = requireNotNull(activity.contentResolver.openInputStream(uri)).use { it.readBytes() }

    /**
     * 一条用例走完整个寿命：FileProvider 按 authority 在进程里缓存根目录，而 Robolectric 每条用例换一个数据目录，
     * 拆成两条用例时后一条的副本落在缓存的根外面。
     */
    @Test
    fun `再次分享不删前一次的副本_过了一天的副本在下一次分享时清掉`() = runBlocking {
        assertTrue(shareImage(activity, urls[0]))
        val firstUri = sharedUri()
        assertTrue(shareImage(activity, urls[1]))
        val secondUri = sharedUri()

        assertNotEquals(firstUri, secondUri)
        assertContentEquals(first, read(firstUri), "第二次分享同名原图后，第一次的地址仍读到第一张图")
        assertContentEquals(second, read(secondUri))
        assertEquals("image.gif", firstUri.lastPathSegment, "接收方看到的仍是原图的文件名")

        val shares = checkNotNull(File(activity.cacheDir, "shared_images").listFiles())
        assertEquals(2, shares.size)
        val dayAgo = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        shares.forEach { it.setLastModified(dayAgo) }

        assertTrue(shareImage(activity, urls[0]))
        val thirdUri = sharedUri()

        shares.forEach { assertFalse(it.exists(), "一天前的副本 ${it.name} 应在这次分享时删掉") }
        assertContentEquals(first, read(thirdUri))
    }
}
