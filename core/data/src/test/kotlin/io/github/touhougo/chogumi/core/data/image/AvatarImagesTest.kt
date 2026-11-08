package io.github.touhougo.chogumi.core.data.image

import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarImagesTest {
    @Test
    fun 裁剪框取短边居中() {
        assertEquals(SquareCrop(left = 500, top = 0, size = 3000), squareCropOf(4000, 3000))
        assertEquals(SquareCrop(left = 0, top = 250, size = 1080), squareCropOf(1080, 1580))
        assertEquals(SquareCrop(left = 0, top = 0, size = 512), squareCropOf(512, 512))
    }

    @Test
    fun 解码缩小到短边仍不小于头像边长() {
        assertEquals(4, sampleSizeFor(4000, 3000))
        assertEquals(1, sampleSizeFor(1000, 800))
        assertEquals(1, sampleSizeFor(300, 300))
    }
}
