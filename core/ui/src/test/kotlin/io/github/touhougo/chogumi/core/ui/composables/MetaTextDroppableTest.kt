package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 末段可有可无的元信息行（收藏面板抬头「在看、看到第 3 集、更新于 1 小时前」）：从宽到窄，末段要么整段在、要么整段不在，
 * 不会截成「更新于 1 小…」；去掉的只有它，前面各段完整。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetaTextDroppableTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val parts = listOf("在看", "看到第 3 集", "更新于 1 小时前")

    @Test
    fun `放不下时更新时间整段去掉 不截成半截`() {
        var width by mutableStateOf(400.dp)
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.width(width)) { MetaText(parts = parts, droppableFrom = 2) }
            }
        }
        val lines = (400 downTo 40 step 4).map { dp ->
            width = dp.dp
            composeRule.waitForIdle()
            shownLine()
        }
        val shown = lines.map { it.text }
        // 段间空白是一个空格：semantics 里的整行就是各段以空格相连
        val full = parts.joinToString(" ")
        val withoutTail = parts.dropLast(1).joinToString(" ")
        // 读屏文字是整行，截没截要看画出来的那一行：末段还在时这一行不能被省略号截断
        lines.forEach { line ->
            assertTrue("末段要么整段在、要么整段不在：${line.text}", line.text == full || !line.text.contains("更新"))
            assertTrue("末段在时整行画得下，不截成「更新于 1 小…」：${line.text}", !line.text.contains("更新") || !line.ellipsized)
        }
        assertEquals("宽的时候整行都在", full, shown.first())
        assertTrue("窄到放不下时只去掉更新时间，状态与进度完整", withoutTail in shown)
    }

    private class ShownLine(val text: String, val ellipsized: Boolean)

    private fun shownLine(): ShownLine {
        val node = composeRule.onNode(hasText("在看", substring = true)).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val layout = layouts.single()
        return ShownLine(
            text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text },
            ellipsized = layout.isLineEllipsized(0) || layout.hasVisualOverflow,
        )
    }
}
