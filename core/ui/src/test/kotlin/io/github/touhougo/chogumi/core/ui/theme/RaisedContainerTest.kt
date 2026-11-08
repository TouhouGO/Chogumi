package io.github.touhougo.chogumi.core.ui.theme

import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import org.junit.Assert.assertTrue

/** 浮起的块（[raisedContainer]）在每种配色的浅深两色下都比它落在的页面、面板、卡片亮，深色下不是一块黑。 */
class RaisedContainerTest {

    @Test
    fun `浮起的块比页面面板卡片都亮`() {
        val failures = mutableListOf<String>()
        for (source in CONTRAST_TEST_SOURCES) for (dark in listOf(false, true)) {
            val scheme = appColorScheme(source, dark)
            val raised = scheme.raisedContainer.luminance()
            val parents = listOf(
                "页面 surface" to scheme.surface,
                "面板 surfaceContainerLow" to scheme.surfaceContainerLow,
                "卡片 surfaceContainer" to scheme.surfaceContainer,
            )
            for ((name, parent) in parents) {
                if (raised <= parent.luminance()) failures += "${source.label()} ${if (dark) "深" else "浅"}：不比$name 亮"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
