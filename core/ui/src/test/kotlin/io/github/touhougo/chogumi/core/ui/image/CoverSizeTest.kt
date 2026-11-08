package io.github.touhougo.chogumi.core.ui.image

import io.github.touhougo.chogumi.core.model.CoverQuality
import kotlin.test.Test
import kotlin.test.assertEquals

/** 封面按画出来的宽与画质档位取哪张图（[coverUrlFor]）。 */
class CoverSizeTest {

    private val original = "https://lain.bgm.tv/pic/cover/l/4e/1f/515759_6kAqj.jpg"

    private fun step(width: Int) = "https://lain.bgm.tv/r/$width/pic/cover/l/4e/1f/515759_6kAqj.jpg"

    @Test
    fun `自动档取不窄于要画的宽的最小一档`() {
        assertEquals(step(100), coverUrlFor(original, 72, CoverQuality.AUTO))
        assertEquals(step(200), coverUrlFor(original, 200, CoverQuality.AUTO))
        // 108dp 的海报卡在 3x 屏上
        assertEquals(step(400), coverUrlFor(original, 324, CoverQuality.AUTO))
        assertEquals(step(800), coverUrlFor(original, 401, CoverQuality.AUTO))
    }

    @Test
    fun `大图档至少取 800 一档`() {
        assertEquals(step(800), coverUrlFor(original, 72, CoverQuality.LARGE))
        assertEquals(step(800), coverUrlFor(original, 800, CoverQuality.LARGE))
    }

    @Test
    fun `比最宽一档还宽时取原图`() {
        assertEquals(original, coverUrlFor(original, 801, CoverQuality.AUTO))
        assertEquals(original, coverUrlFor(original, 801, CoverQuality.LARGE))
    }

    @Test
    fun `原图档一律取原图`() {
        assertEquals(original, coverUrlFor(original, 72, CoverQuality.ORIGINAL))
    }

    @Test
    fun `不是封面原图的地址原样返回`() {
        val common = step(400)
        val avatar = "https://lain.bgm.tv/pic/user/l/000/00/00/1.jpg"
        val character = "https://lain.bgm.tv/pic/crt/l/4e/1f/1_abc.jpg"
        val elsewhere = "https://example.com/pic/cover/l/4e/1f/515759_6kAqj.jpg"
        for (url in listOf(common, avatar, character, elsewhere)) {
            assertEquals(url, coverUrlFor(url, 100, CoverQuality.AUTO))
            assertEquals(url, coverUrlFor(url, 100, CoverQuality.LARGE))
        }
    }

    @Test
    fun `看原图时可垫的小图：封面原图逐档从宽到窄，其余图只有地址本身`() {
        assertEquals(listOf(original, step(800), step(400), step(200), step(100)), thumbnailUrlsOf(original))
        val avatar = "https://lain.bgm.tv/pic/user/l/000/00/00/1.jpg"
        assertEquals(listOf(avatar), thumbnailUrlsOf(avatar))
    }
}
