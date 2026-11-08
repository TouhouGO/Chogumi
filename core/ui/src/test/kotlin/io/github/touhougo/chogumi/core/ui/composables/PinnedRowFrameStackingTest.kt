package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/** [PinnedRowFrame] 的吸顶条里放几行就自上而下排几行（搜索页：搜索栏在上、排序行在下），正文紧接在最后一行下面。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PinnedRowFrameStackingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 吸顶条里的几行竖向叠放_正文接在最后一行下面() {
        compose.setContent {
            ChogumiTheme {
                PinnedRowFrame(pinned = {
                    Box(Modifier.fillMaxWidth().height(56.dp).testTag("first"))
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("second"))
                }) {
                    Box(Modifier.fillMaxWidth().height(10.dp).testTag("content"))
                }
            }
        }
        val first = compose.onNodeWithTag("first").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("second").getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        assertEquals("第二行从第一行的下沿起", first.bottom, second.top)
        assertEquals("正文从最后一行的下沿起", second.bottom, content.top)
    }
}
