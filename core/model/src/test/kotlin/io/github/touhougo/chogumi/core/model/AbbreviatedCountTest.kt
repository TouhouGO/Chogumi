package io.github.touhougo.chogumi.core.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AbbreviatedCountTest {

    private fun zh(count: Int) = formatAbbreviatedCount(count, Locale.SIMPLIFIED_CHINESE)
    private fun en(count: Int) = formatAbbreviatedCount(count, Locale.US)

    @Test
    fun `不到一千原样显示`() {
        assertEquals("0", zh(0))
        assertEquals("354", zh(354))
        assertEquals("999", zh(999))
        assertEquals("999", en(999))
    }

    @Test
    fun `中文一千起用K一万起用W都保留一位小数`() {
        assertEquals("1.0K", zh(1_000))
        assertEquals("8.5K", zh(8_500))
        assertEquals("1.0W", zh(10_000))
        assertEquals("2.1W", zh(21_000))
        assertEquals("21.5W", zh(215_952))
    }

    @Test
    fun `小数截断不进位_单位边界不会出现比下一档大的读数`() {
        assertEquals("9.9K", zh(9_999))
        assertEquals("2.1W", zh(21_595))
        assertEquals("999.9K", en(999_999))
    }

    @Test
    fun `英文一千起用K一百万起用M`() {
        assertEquals("1.0K", en(1_000))
        assertEquals("10.0K", en(10_000))
        assertEquals("1.0M", en(1_000_000))
        assertEquals("2.1M", en(2_150_000))
    }
}
