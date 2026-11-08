package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.displayZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** 相对时间的文案：中文数字与汉字之间留空格（「9 小时前」），与仓库其它文案同一口径；英文按单复数。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RelativeTimeTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    // 本机时区里 10 月 10 日正午：一天前是日历上的昨天，八天前是 10 月 2 日
    private val now = LocalDate.of(2026, 10, 10).atTime(12, 0).atZone(displayZone()).toInstant().toEpochMilli()

    @Test
    @Config(qualifiers = "zh")
    fun `中文分钟小时天数与汉字之间留空格`() {
        assertEquals(
            listOf("刚刚", "1 分钟前", "5 分钟前", "1 小时前", "9 小时前", "昨天", "3 天前", "10 月 2 日"),
            textsAgo(30 * SECOND, MINUTE, 5 * MINUTE, HOUR, 9 * HOUR, DAY, 3 * DAY, 8 * DAY),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun `英文按单复数`() {
        assertEquals(
            listOf("Just now", "1 minute ago", "5 minutes ago", "1 hour ago", "9 hours ago", "Yesterday", "3 days ago", "Oct 2"),
            textsAgo(30 * SECOND, MINUTE, 5 * MINUTE, HOUR, 9 * HOUR, DAY, 3 * DAY, 8 * DAY),
        )
    }

    @Test
    fun `无效时间戳是空串`() {
        var text: String? = null
        composeRule.setContent { text = relativeTimeText(0L) }
        composeRule.waitForIdle()
        assertEquals("", text)
    }

    private fun textsAgo(vararg agoMillis: Long): List<String> {
        var texts = emptyList<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalNow provides remember { mutableLongStateOf(now) }) {
                texts = agoMillis.map { relativeTimeText(now - it) }
            }
        }
        composeRule.waitForIdle()
        return texts
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
