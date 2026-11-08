package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 回顶之后外框归位：里层的入口一次调用，外层与里层登记的归位都执行，外层先。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TopResetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 嵌套登记的归位一并执行_外层先() {
        val ran = mutableListOf<String>()
        var entry: () -> Unit = {}
        composeRule.setContent {
            ProvideTopReset({ ran += "outer" }) {
                ProvideTopReset({ ran += "inner" }) { entry = LocalTopReset.current }
            }
        }
        composeRule.runOnIdle { entry() }
        assertEquals(listOf("outer", "inner"), ran)
    }
}
