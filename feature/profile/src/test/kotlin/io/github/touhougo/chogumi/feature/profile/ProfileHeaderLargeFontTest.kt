package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 资料头的「yyyy-MM-dd 加入」在小屏 2× 字号下折行显示完整，不被裁成「2025-11-27 加」。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileHeaderLargeFontTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 两倍字号下加入日期完整显示_任何宽度都不拆开加入二字() {
        var width by mutableStateOf(WIDTHS.first())
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(width.dp)) {
                        ProfileHeader(
                            user = UserBo(id = 1, username = "someone", nickname = "某人"),
                            accent = null,
                            joinedDate = "2025-11-27",
                            onOpenInfo = {},
                        )
                    }
                }
            }
        }
        WIDTHS.forEach { w ->
            compose.runOnIdle { width = w }
            compose.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText("2025-11-27", substring = true), useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.single()
            assertFalse("宽 ${w}dp：加入日期被裁", layout.hasVisualOverflow)
            val text = layout.layoutInput.text.text
            (1 until layout.lineCount).forEach { line ->
                assertFalse("宽 ${w}dp：「加入」被拆开，第 ${line + 1} 行以「入」起头", text[layout.getLineStart(line)] == '入')
            }
        }
    }

    private companion object {
        // 小屏窄宽一路扫过：「加入」二字只在某一段宽度里会被拆开
        val WIDTHS = (260..330 step 2).toList()
    }
}
