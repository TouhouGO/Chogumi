package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 菜单项高度是 M3 菜单项的 48dp，也就是 48dp 的触控目标。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectableDropdownMenuHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `菜单项不低于48dp`() {
        composeRule.setContent {
            ChogumiTheme {
                SelectableDropdownMenu(
                    open = true,
                    options = listOf("热度", "评分"),
                    selected = "热度",
                    label = { it },
                    onDismiss = {},
                    onPick = {},
                )
            }
        }
        listOf("热度", "评分").forEach { text ->
            val item = composeRule.onNode(hasText(text)).getUnclippedBoundsInRoot()
            assertTrue("「$text」高 ${item.height} 不低于 48dp", item.height >= 48.dp)
        }
    }
}
