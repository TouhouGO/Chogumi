package io.github.touhougo.chogumi.core.ui.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogoTrimTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFE60012.toInt()
    private val clear = 0x00000000

    /** [w]×[h] 的底色 [ground] 上，(x0, y0) 起画一块 [bw]×[bh] 的红。 */
    private fun image(w: Int, h: Int, ground: Int, x0: Int, y0: Int, bw: Int, bh: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if (x in x0 until x0 + bw && y in y0 until y0 + bh) red else ground
    }

    @Test
    fun `白底留白多的标志裁到内容加余量`() {
        val pixels = image(200, 200, white, 50, 80, 100, 40)
        // 余量是较长一边 100 的 4%
        assertEquals(LogoBounds(46, 76, 154, 124), logoContentBounds(pixels, 200, 200))
    }

    @Test
    fun `透明底也算白底`() {
        val pixels = image(100, 100, clear, 40, 40, 20, 20)
        assertEquals(LogoBounds(40, 40, 60, 60), logoContentBounds(pixels, 100, 100))
    }

    @Test
    fun `底不是白的整张就是标志不裁`() {
        val pixels = image(100, 100, red, 10, 10, 10, 10).also { it[it.size / 2] = white }
        assertNull(logoContentBounds(pixels, 100, 100))
    }

    @Test
    fun `留白本来就少的标志不裁`() {
        val pixels = image(100, 100, white, 2, 2, 96, 96)
        assertNull(logoContentBounds(pixels, 100, 100))
    }

    @Test
    fun `整张都是白的不裁`() {
        assertNull(logoContentBounds(IntArray(100) { white }, 10, 10))
    }
}
