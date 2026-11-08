package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 封面的尺寸：只给宽按 3:4 推高；调用方定死宽高（热门大图铺满轮播项、放送卡 104×146）就是给的那一块，
 * 压在底边的内容（大图的标题）贴着这一块的底边，不会因为按 3:4 量高了而落到槽位外面。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubjectPosterSizeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `只给宽_按三比四推出高`() {
        composeRule.setContent {
            ChogumiTheme { SubjectPoster(url = null, modifier = Modifier.width(120.dp).testTag(COVER)) }
        }
        val cover = composeRule.onNodeWithTag(COVER).getUnclippedBoundsInRoot()
        assertEquals(120f, (cover.right - cover.left).value, 0.5f)
        assertEquals(160f, (cover.bottom - cover.top).value, 0.5f)
    }

    @Test
    fun `定死宽高的横幅_压在底边的内容贴着给的那一块的底边`() {
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.size(300.dp, 200.dp).testTag(SLOT)) {
                    SubjectPoster(url = null, modifier = Modifier.fillMaxSize()) {
                        Text("标题", Modifier.align(Alignment.BottomStart).testTag(TITLE))
                    }
                }
            }
        }
        val slot = composeRule.onNodeWithTag(SLOT).getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithTag(TITLE, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("标题的底边应贴着横幅的底边", slot.bottom.value, title.bottom.value, 0.5f)
        assertEquals(slot.top.value, composeRule.onNodeWithTag("subject_cover_placeholder", useUnmergedTree = true).getUnclippedBoundsInRoot().top.value, 0.5f)
    }

    private companion object {
        const val COVER = "cover"
        const val SLOT = "slot"
        const val TITLE = "title"
    }
}
