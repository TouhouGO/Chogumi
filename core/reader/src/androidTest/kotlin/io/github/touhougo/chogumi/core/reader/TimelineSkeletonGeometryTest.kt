package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 动态页的骨架与真实内容**几何同源**。
 *
 * 动态页的真实结构是「作者头 + 一组分段行」（[TimelineGroupHeader] + [TimelineRow]），
 * 骨架是 [TimelineGroupHeaderSkeleton] + [TimelineRowSkeleton]，必须同形，否则内容出现时整体下跳。
 * 做法同 [SkeletonGeometryTest]：同一个窗口里先渲染骨架、读锚点，再翻转 state 换成真实内容、读同一批锚点，
 * 上、下、左三边逐一比较（容差 0.5dp）。第二行封面只在第一行整体等高时才对得上，是「行高不同源」最灵敏的探针。
 *
 * 两态内容见 [TimelineSkeletonPair]。
 */
@RunWith(AndroidJUnit4::class)
class TimelineSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    /** 同名节点里的第 [index] 个（两行按组合顺序排列）。 */
    private fun nodeAt(tag: String, index: Int) =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)[index]

    private fun assertTwoRowsGeometry(fontScale: Float, friendMarks: Boolean = true) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(400.dp)) { TimelineSkeletonPair(showReal, friendMarks) }
                }
            }
        }
        composeRule.waitForIdle()
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor("作者头", { nodeAt(TimelineGroupHeaderTag, 0) }, { nodeAt(TimelineGroupHeaderTag, 0) }),
                SkeletonAnchor("第一行封面", { nodeAt(TimelineCoverTag, 0) }, { nodeAt(TimelineCoverTag, 0) }),
                SkeletonAnchor("第二行封面", { nodeAt(TimelineCoverTag, 1) }, { nodeAt(TimelineCoverTag, 1) }),
            ),
            flipToReal = {
                composeRule.runOnIdle { showReal = true }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 骨架与真实的作者头和分段行同形_第二行不下移() = assertTwoRowsGeometry(fontScale = 1f)

    @Test
    fun 字体放大两倍时骨架与真实的作者头和分段行同形_第二行不下移() = assertTwoRowsGeometry(fontScale = 2f)

    @Test
    fun 好友标识关闭时两倍字号骨架与真实作者头仍同形_第二行不下移() =
        assertTwoRowsGeometry(fontScale = 2f, friendMarks = false)
}
