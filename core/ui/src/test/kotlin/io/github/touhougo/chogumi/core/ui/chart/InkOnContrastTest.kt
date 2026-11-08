package io.github.touhougo.chogumi.core.ui.chart

import io.github.touhougo.chogumi.core.ui.theme.CONTRAST_TEST_SOURCES
import io.github.touhougo.chogumi.core.ui.theme.MIN_TEXT_CONTRAST
import io.github.touhougo.chogumi.core.ui.theme.appColorScheme
import io.github.touhougo.chogumi.core.ui.theme.contrastOf
import io.github.touhougo.chogumi.core.ui.theme.label
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档色块上的字（热度格里的集数、题材版图的名字与部数）：换几种主题色与配色方案、浅色深色主题、1 到 [MAX_TIERS] 档的每一档，
 * [inkOn] 给的字色和 [ordinalTones] 的底色对比度都不低于 M3 小字下限。
 */
class InkOnContrastTest {

    @Test
    fun `每种档数的每一档上字和底色的对比度都够小字下限`() {
        val failures = mutableListOf<String>()
        for (source in CONTRAST_TEST_SOURCES) for (dark in listOf(false, true)) {
            val scheme = appColorScheme(source, dark)
            for (count in 1..MAX_TIERS) {
                ordinalTones(scheme, count).forEachIndexed { tier, fill ->
                    val ratio = contrastOf(inkOn(fill), fill)
                    if (ratio < MIN_TEXT_CONTRAST) {
                        failures += "${source.label()} ${if (dark) "深" else "浅"} $count 档的第 ${tier + 1} 档：%.2f".format(ratio)
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        /** 写字的热度格 4 档、题材版图 5 档，其余档位图也都在这个范围内。 */
        const val MAX_TIERS = 8
    }
}
