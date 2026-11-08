package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 筛选格是多选的开关块：读屏念出选没选，点一下翻过来（名字与部数并在同一个节点里）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class FilterTileSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 筛选格读出选没选_点一下翻过来() {
        composeRule.setContent {
            ChogumiTheme {
                var selected by remember { mutableStateOf(false) }
                FilterTile(
                    label = "补番",
                    count = 3,
                    covers = listOf(SubjectBo(id = 1, type = SubjectType.ANIME, name = "作品", nameCn = "")),
                    selected = selected,
                    onClick = { selected = !selected },
                )
            }
        }

        composeRule.onNodeWithText("补番").assertIsOff().performClick()
        composeRule.onNodeWithText("补番").assertIsOn()
    }
}
