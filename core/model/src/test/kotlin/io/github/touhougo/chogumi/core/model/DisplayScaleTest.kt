package io.github.touhougo.chogumi.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** 界面大小：哪些档位能选、存下的值怎么落到这块屏上、一档对应的密度与最小宽度。 */
class DisplayScaleTest {

    @Test
    fun `小屏上缩完不足320dp的放大档不给选_缩小档与跟随系统都在`() {
        assertEquals(listOf(80, 85, 90, 95, 100, 105, 110), DisplayScale.optionsFor(360))
        assertEquals(DisplayScale.OPTIONS, DisplayScale.optionsFor(411))
        assertEquals(listOf(80, 85, 90, 95, 100), DisplayScale.optionsFor(320))
    }

    @Test
    fun `存下的放大档这块屏放不下时退到能放下的最大一档`() {
        assertEquals(110, DisplayScale.effective(stored = 120, systemSmallestWidthDp = 360))
        assertEquals(DisplayScale.SYSTEM, DisplayScale.effective(stored = 120, systemSmallestWidthDp = 320))
    }

    @Test
    fun `放得下的档位原样用_缺省与不认识的值跟随系统`() {
        assertEquals(120, DisplayScale.effective(stored = 120, systemSmallestWidthDp = 411))
        assertEquals(85, DisplayScale.effective(stored = 85, systemSmallestWidthDp = 360))
        assertEquals(DisplayScale.SYSTEM, DisplayScale.effective(stored = null, systemSmallestWidthDp = 411))
        assertEquals(DisplayScale.SYSTEM, DisplayScale.effective(stored = 103, systemSmallestWidthDp = 411))
    }

    @Test
    fun `密度按比例放大_最小宽度按比例缩小_跟随系统两者不变`() {
        assertEquals(504, DisplayScale.densityDpiOf(systemDensityDpi = 420, percent = 120))
        assertEquals(343, DisplayScale.smallestWidthDpOf(systemSmallestWidthDp = 411, percent = 120))
        assertEquals(420, DisplayScale.densityDpiOf(systemDensityDpi = 420, percent = DisplayScale.SYSTEM))
        assertEquals(411, DisplayScale.smallestWidthDpOf(systemSmallestWidthDp = 411, percent = DisplayScale.SYSTEM))
    }
}
