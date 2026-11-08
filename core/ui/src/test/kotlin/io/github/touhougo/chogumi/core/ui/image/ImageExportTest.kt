package io.github.touhougo.chogumi.core.ui.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 保存、分享前按文件头认格式（[imageFormatOf]），写出的文件名取自地址（[exportFileNameOf]）。 */
class ImageExportTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `按文件头认出常见格式`() {
        assertEquals(ImageFormat.JPEG, imageFormatOf(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00)))
        assertEquals(ImageFormat.PNG, imageFormatOf(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)))
        assertEquals(ImageFormat.GIF, imageFormatOf("GIF89a".toByteArray()))
        assertEquals(ImageFormat.WEBP, imageFormatOf("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals(ImageFormat.BMP, imageFormatOf("BM\u0000\u0000".toByteArray()))
    }

    @Test
    fun `认不出的内容不当图片`() {
        assertNull(imageFormatOf("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertNull(imageFormatOf("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertNull(imageFormatOf(bytes(0xFF, 0xD8)))
        assertNull(imageFormatOf(ByteArray(0)))
    }

    @Test
    fun `文件名取地址最后一段的主体，扩展名按实际格式`() {
        assertEquals("515759_6kAqj.png", exportFileNameOf("https://lain.bgm.tv/pic/cover/l/4e/1f/515759_6kAqj.jpg", ImageFormat.PNG))
        assertEquals("1.gif", exportFileNameOf("https://lain.bgm.tv/pic/user/l/000/00/00/1.jpg?r=1700000000#x", ImageFormat.GIF))
    }

    @Test
    fun `取不出可用的名字时叫 image`() {
        assertEquals("image.jpg", exportFileNameOf("https://example.com/", ImageFormat.JPEG))
        assertEquals("image.jpg", exportFileNameOf("https://example.com/.jpg", ImageFormat.JPEG))
    }

    @Test
    fun `文件名只留字母、数字、下划线和短横`() {
        assertEquals("ab1-c_d.png", exportFileNameOf("https://example.com/img/a b(1)-c_d.png", ImageFormat.PNG))
    }
}
