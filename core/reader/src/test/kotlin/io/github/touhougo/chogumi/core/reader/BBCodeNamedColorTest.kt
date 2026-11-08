package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `[color=名字]` **跟随明暗两档**——同一名字在明暗下必须取不同色值，
 * 且 black/white 不得再是纯黑/纯白写死（另一侧主题下会不可见）。
 */
class BBCodeNamedColorTest {

    private val names = listOf("red", "orange", "yellow", "green", "blue", "purple", "pink", "brown", "black", "gray", "white")

    @Test
    fun `每个具名色在明暗两档都解析成功且互不相同`() {
        names.forEach { name ->
            val light = namedBBCodeColor(name, darkTheme = false)
            val dark = namedBBCodeColor(name, darkTheme = true)
            assertTrue(light != Color.Unspecified, "$name 亮档未解析")
            assertTrue(dark != Color.Unspecified, "$name 暗档未解析")
            assertNotEquals(light, dark, "$name 两档色值必须不同")
        }
    }

    @Test
    fun `black不是纯黑_white亮档不是纯白`() {
        val blackLight = namedBBCodeColor("black", darkTheme = false)
        val whiteLight = namedBBCodeColor("white", darkTheme = false)
        assertEquals(Color(0xFF1C1B1F), blackLight, "亮档 black 用近黑替身")
        assertNotEquals(Color(0xFF000000), blackLight)
        assertNotEquals(Color(0xFFFFFFFF), whiteLight, "亮档 white 不能纯白（在白底不可见）")
    }

    @Test
    fun `未知颜色名回退Unspecified`() {
        assertEquals(Color.Unspecified, namedBBCodeColor("chartreuse", darkTheme = false))
    }
}
