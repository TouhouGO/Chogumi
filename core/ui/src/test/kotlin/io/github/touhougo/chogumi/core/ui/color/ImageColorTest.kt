package io.github.touhougo.chogumi.core.ui.color

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 头像取色的算术契约。
 *
 * 这里只测纯计算（不碰位图、不碰 Coil）——取色的正确性关键是
 * "忽略透明像素"与"均值落在 0..1"，这两点出错都会让顶部渐变整体发黑或偏色。
 * 真机上的解码路径由设备冒烟验证。
 */
class ImageColorTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `纯色图的均值等于该色`() {
        val pixels = IntArray(16) { argb(255, 200, 100, 50) }
        val color = averageColorOfPixels(pixels)
        assertEquals(200f / 255f, color!!.red)
        assertEquals(100f / 255f, color.green)
        assertEquals(50f / 255f, color.blue)
    }

    @Test
    fun `透明像素不参与否则整体被拉黑`() {
        // 一半是纯白不透明，一半是全透明。若透明像素参与，均值会掉到一半亮度。
        val pixels = intArrayOf(
            argb(255, 255, 255, 255),
            argb(255, 255, 255, 255),
            argb(0, 0, 0, 0),
            argb(0, 0, 0, 0),
        )
        val color = averageColorOfPixels(pixels)!!
        assertEquals(1f, color.red)
        assertEquals(1f, color.green)
        assertEquals(1f, color.blue)
    }

    @Test
    fun `全透明返回 null 由调用方回退主题色`() {
        val pixels = IntArray(4) { argb(0, 0, 0, 0) }
        assertNull(averageColorOfPixels(pixels))
    }

    @Test
    fun `alpha 低于阈值的近乎透明像素同样被忽略`() {
        val pixels = intArrayOf(
            argb(255, 10, 20, 30),
            argb(8, 250, 250, 250), // alpha=8 < 16，忽略
        )
        val color = averageColorOfPixels(pixels)!!
        assertEquals(10f / 255f, color.red)
        assertEquals(20f / 255f, color.green)
        assertEquals(30f / 255f, color.blue)
    }

    // ---- accentOrNull：灰度判废与色调归位 ----

    @Test
    fun `灰色头像判废由调用方回退主题色`() {
        // 实测某黑白漫画头像均值 #A4A4A4 —— 直接铺成背景看着像渲染坏了
        assertNull(Color(0.6431373f, 0.6431373f, 0.6431373f).accentOrNull())
    }

    @Test
    fun `纯黑纯白同样判废`() {
        assertNull(Color.Black.accentOrNull())
        assertNull(Color.White.accentOrNull())
    }

    @Test
    fun `有色彩的色相保持不变`() {
        // 一个偏橙的红，色相应原样保留
        val source = Color(0.80f, 0.40f, 0.30f)
        val out = source.accentOrNull()!!
        val a = rgbToHsv(source.red, source.green, source.blue)
        val b = rgbToHsv(out.red, out.green, out.blue)
        assertEquals(a[0], b[0], 1.0f)   // 色相误差容忍 1 度
    }

    @Test
    fun `归位后明度落在可当背景的区间`() {
        // 过暗的深蓝
        val out = Color(0.10f, 0.10f, 0.45f).accentOrNull()!!
        val hsv = rgbToHsv(out.red, out.green, out.blue)
        assertEquals(true, hsv[2] in 0.60f..0.90f, "明度应在可读区间内，实际 ${hsv[2]}")
        assertEquals(true, hsv[1] >= 0.34f, "饱和度应被抬到可见水平，实际 ${hsv[1]}")
    }

    @Test
    fun `HSV 往返换算保真`() {
        // 纯算术实现必须自洽：RGB→HSV→RGB 应回到原值
        val source = Color(0.80f, 0.40f, 0.30f)
        val hsv = rgbToHsv(source.red, source.green, source.blue)
        val back = hsvToRgb(hsv[0], hsv[1], hsv[2])
        assertEquals(source.red, back.red, 0.005f)
        assertEquals(source.green, back.green, 0.005f)
        assertEquals(source.blue, back.blue, 0.005f)
    }

    @Test
    fun `顶色与底色的色相在换算中保持一致`() {
        // 六段色相分界（0/60/120/180/240/300）都应还原到同一色相
        for (h in listOf(0f, 59f, 61f, 120f, 180f, 240f, 300f, 359f)) {
            val rgb = hsvToRgb(h, 0.8f, 0.7f)
            val back = rgbToHsv(rgb.red, rgb.green, rgb.blue)
            assertEquals(h, back[0], 0.6f, "色相 $h 往返后偏离")
        }
    }
}
