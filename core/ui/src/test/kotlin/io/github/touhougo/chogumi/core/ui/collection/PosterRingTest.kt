package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.touhougo.chogumi.core.model.CollectionType
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 环的方向判定与摆放：方向固定、出了死区按角度取最近一格、距离不限、贴边整圈平移而方向仍从按下点算。 */
class PosterRingTest {
    private val dead = 24f

    private fun toward(degrees: Double, distance: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees)
        return (distance * cos(r)).toFloat() to (distance * sin(r)).toFloat()
    }

    @Test
    fun `六个方向各是一格_正上看过右上想看左上在看左下管理正下搁置右下抛弃`() {
        val expected = mapOf(
            -90.0 to RingSlot.DONE,
            -30.0 to RingSlot.WISH,
            -150.0 to RingSlot.DOING,
            150.0 to RingSlot.MANAGE,
            90.0 to RingSlot.ON_HOLD,
            30.0 to RingSlot.DROPPED,
        )
        expected.forEach { (degrees, slot) ->
            val (dx, dy) = toward(degrees, 40f)
            assertEquals("方向 $degrees°", slot, ringSlotAt(dx, dy, dead))
        }
        assertEquals(CollectionType.DONE, RingSlot.DONE.type)
        assertNull(RingSlot.MANAGE.type)
    }

    @Test
    fun `死区里不指任何一格_出了死区距离不限`() {
        val (inX, inY) = toward(-90.0, dead - 1f)
        assertNull(ringSlotAt(inX, inY, dead))
        val (farX, farY) = toward(-90.0, 2000f)
        assertEquals(RingSlot.DONE, ringSlotAt(farX, farY, dead))
    }

    @Test
    fun `扇区没有空隙_偏离格心不到30度仍是那一格`() {
        val (dx, dy) = toward(-90.0 + 29.0, 60f)
        assertEquals(RingSlot.DONE, ringSlotAt(dx, dy, dead))
        val (dx2, dy2) = toward(-90.0 + 31.0, 60f)
        assertEquals(RingSlot.WISH, ringSlotAt(dx2, dy2, dead))
        val (dx3, dy3) = toward(180.0, 60f)
        // 正左在在看（-150°）与管理（150°）正中间，两格等距时取先列出的那格，不落空
        assertEquals(RingSlot.MANAGE, ringSlotAt(dx3, dy3, dead))
    }

    @Test
    fun `贴边时整圈往里平移_放得下时不动`() {
        val area = Rect(0f, 0f, 400f, 800f)
        val extent = 120f
        assertEquals(Offset(200f, 400f), ringCenter(Offset(200f, 400f), area, extent))
        assertEquals(Offset(120f, 120f), ringCenter(Offset(10f, 30f), area, extent))
        assertEquals(Offset(280f, 680f), ringCenter(Offset(390f, 790f), area, extent))
    }

    @Test
    fun `区域比环还小时居中`() {
        assertEquals(Offset(100f, 400f), ringCenter(Offset(10f, 400f), Rect(0f, 0f, 200f, 800f), 120f))
    }

    @Test
    fun `预演在环正上方_上面放不下放到正下方`() {
        val area = Rect(0f, 0f, 400f, 800f)
        assertEquals(400f - 112f - 8f - 32f, ringPreviewTop(Offset(200f, 400f), area, 112f, 32f, 8f))
        assertEquals(130f + 112f + 8f, ringPreviewTop(Offset(200f, 130f), area, 112f, 32f, 8f))
    }

    @Test
    fun `圆钮中心在格的方向上离环心一个半径`() {
        val c = ringSlotCenter(Offset(100f, 100f), RingSlot.DONE, 76f)
        assertEquals(100f, c.x, 0.01f)
        assertEquals(24f, c.y, 0.01f)
    }
}
