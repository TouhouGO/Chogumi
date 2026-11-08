package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 帖子作者/来源与骨架同用一个主次框架；2× 下预留来源换行的行高，内容出现时允许向上收。 */
@RunWith(AndroidJUnit4::class)
class PostListItemSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    @Test
    fun 帖子骨架与真实行一倍字号同高() = assertGeometry(fontScale = 1f)

    @Test
    fun 帖子两倍字号内容出现时不向下推_来源行也预留() = assertGeometry(fontScale = 2f)

    private fun assertGeometry(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(RowWidth).testTag(ROW)) {
                        if (showReal) {
                            PostListItem(
                                title = "帖子标题".repeat(8),
                                replies = 0,
                                repliesDescription = "0 条回复",
                                index = 0,
                                count = 1,
                                onClick = {},
                                leading = PostLeading.Avatar(null),
                                author = "PostAuthorLong",
                                source = "小组名称",
                                time = "刚刚",
                            )
                        } else {
                            PostListItemSkeleton(
                                index = 0,
                                count = 1,
                                leading = PostLeading.Avatar(null),
                                withAuthor = true,
                                withSource = true,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor(
                    name = "帖子行",
                    skeleton = { composeRule.onNodeWithTag(ROW, useUnmergedTree = true) },
                    real = { composeRule.onNodeWithTag(ROW, useUnmergedTree = true) },
                ),
            ),
            flipToReal = {
                composeRule.runOnIdle { showReal = true }
                composeRule.waitForIdle()
            },
            allowUpwardMovement = fontScale == 2f,
        )
    }

    private companion object {
        val RowWidth = 360.dp
        const val ROW = "post-skeleton-row"
    }
}
