package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.performScrollTo
import org.robolectric.annotation.GraphicsMode
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagBo
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 测量候选不能进入可交互语义；实际展开与收起各只有一个入口。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-w380dp")
class SubjectTagsBudgetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `一行只保留一个展开入口且收展实际操作成功`() {
        compose.setContent {
            ChogumiTheme {
                Surface {
                    Column(Modifier.width(380.dp).verticalScroll(rememberScrollState())) {
                        SubjectDetailsHeader(SubjectBo(1, SubjectType.ANIME, "作品", "", tags = List(30) { TagBo("标签$it", 100) }),
                            24.dp, AppFeatures(true, true, true, true), {}, {}, {})
                    }
                }
            }
        }
        compose.onAllNodesWithTag("details_tags_expand").assertCountEquals(1)
        compose.onNodeWithTag("details_tags_expand").performClick()
        compose.onAllNodesWithTag("details_tags_expand").assertCountEquals(0)
        compose.onAllNodesWithTag("details_tags_collapse").assertCountEquals(1)
        compose.onNodeWithTag("details_tags_collapse").performScrollTo().performClick()
        compose.onAllNodesWithTag("details_tags_expand").assertCountEquals(1)
    }
}
