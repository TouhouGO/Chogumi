package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonChipGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 默认窗口与380dp大字号下真实人物chip保持48dp目标() {
        var narrow by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = if (narrow) 1.1f else 1f),
            ) {
                ChogumiTheme {
                    Column(Modifier.width(if (narrow) 380.dp else 448.dp)) {
                        PersonChip(onClick = {}, avatar = null) { Text(NAME) }
                    }
                }
            }
        }

        for (state in listOf(false, true)) {
            compose.runOnIdle { narrow = state }
            compose.waitForIdle()
            compose.onNodeWithText(NAME).assertHasClickAction()
            val bounds = compose.onNodeWithText(NAME).fetchSemanticsNode().touchBoundsInRoot
            val minTouchPx = with(compose.density) { 48.dp.toPx() }
            assertEquals("${if (state) "380dp/1.1" else "默认"}的真实人物 chip 热区高度", minTouchPx, bounds.height, 0.5f)
        }
    }

    private companion object {
        const val NAME = "人物"
    }
}
