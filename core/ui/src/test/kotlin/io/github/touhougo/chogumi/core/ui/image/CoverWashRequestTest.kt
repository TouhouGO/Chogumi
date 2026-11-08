package io.github.touhougo.chogumi.core.ui.image

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.getExtra
import coil3.size.Dimension
import io.github.touhougo.chogumi.core.model.CoverQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 模糊底图不按铺满整屏的布局宽去取大图，也不被「原图」画质设置抬高。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CoverWashRequestTest {

    private val url = "https://lain.bgm.tv/pic/cover/l/4e/1f/515759_6kAqj.jpg"
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `模糊底图的请求固定小尺寸且画质取自动档`() {
        val request = coverRequestOf(context, url, CoverQuality.ORIGINAL, blurred = true)
        val size = runBlocking { request.sizeResolver.size() }
        assertEquals(Dimension.Pixels(CoverWashWidthPx), size.width)
        assertEquals(CoverQuality.AUTO, request.getExtra(CoverQualityKey))
        assertEquals("https://lain.bgm.tv/r/200/pic/cover/l/4e/1f/515759_6kAqj.jpg", coverUrlFor(url, CoverWashWidthPx, CoverQuality.AUTO))
    }

    @Test
    fun `海报封面的请求仍带设置里的画质`() {
        val request = coverRequestOf(context, url, CoverQuality.ORIGINAL, blurred = false)
        assertEquals(CoverQuality.ORIGINAL, request.getExtra(CoverQualityKey))
    }
}
