package io.github.touhougo.chogumi.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** 刷新率：哪些档位能选、存下的值落到哪个模式上。 */
class RefreshRateTest {

    private fun mode(id: Int, rate: Float, width: Int = 1080, height: Int = 2400) = DisplayModeSpec(id, width, height, rate)

    /** 一块 60/90/120 的屏，另有一套 1440p 的模式（只有 60 与 120）。 */
    private val screen = ScreenModes(
        current = mode(2, 90f),
        supported = listOf(
            mode(1, 60f), mode(2, 90f), mode(3, 120f),
            mode(4, 60f, 1440, 3200), mode(5, 120f, 1440, 3200), mode(6, 144f, 1440, 3200),
        ),
    )

    @Test
    fun `档位是跟随系统加上当前分辨率下屏幕有的刷新率_别的分辨率的不算`() {
        assertEquals(listOf(RefreshRate.SYSTEM, 60, 90, 120), RefreshRate.optionsFor(screen))
    }

    @Test
    fun `只有一种刷新率的屏只有跟随系统`() {
        val single = ScreenModes(current = mode(1, 60f), supported = listOf(mode(1, 60f), mode(4, 60f, 1440, 3200)))
        assertEquals(listOf(RefreshRate.SYSTEM), RefreshRate.optionsFor(single))
    }

    @Test
    fun `选了一档就请求当前分辨率下那一档的模式`() {
        assertEquals(3, RefreshRate.modeIdFor(120, screen))
        assertEquals(1, RefreshRate.modeIdFor(60, screen))
    }

    @Test
    fun `跟随系统与这块屏没有的档位都不请求`() {
        assertEquals(0, RefreshRate.modeIdFor(RefreshRate.SYSTEM, screen))
        assertEquals(0, RefreshRate.modeIdFor(144, screen), "144 只在别的分辨率下有")
        assertEquals(RefreshRate.SYSTEM, RefreshRate.effective(165, screen))
        assertEquals(90, RefreshRate.effective(90, screen))
    }

    @Test
    fun `取整后同一档有两个模式时请求刷新率高的那个`() {
        val ntsc = ScreenModes(current = mode(1, 59.94f), supported = listOf(mode(1, 59.94f), mode(2, 60f), mode(3, 120f)))
        assertEquals(listOf(RefreshRate.SYSTEM, 60, 120), RefreshRate.optionsFor(ntsc))
        assertEquals(2, RefreshRate.modeIdFor(60, ntsc))
    }
}
