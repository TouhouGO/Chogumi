package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 个人页收藏网格（作品、人物各一组）的骨架与真实内容各一张截图（字体比例 1 与 2），叠起来看对不对得上
 * （[saveScreenshot] 的 `collection-grid-skeleton/`）。位置由 [GridSkeletonGeometryTest] 逐边断言。
 * 网格与个人页同配置：列数取 [GridCell]（海报卡 / 人物卡各一种）、外侧留白与双向间距取 core:ui 的网格常量。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class GridSkeletonScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    private companion object {
        const val FOLDER = "collection-grid-skeleton"
        val titles = listOf("芙莉莲", "孤独摇滚", "迷宫饭", "药屋少女", "咒术回战", "间谍过家家")
    }

    @Test
    fun 收藏网格骨架与内容各存一张截图() = capture(fontScale = 1f, suffix = "")

    @Test
    fun 字体放大两倍时收藏网格骨架与内容各存一张截图() = capture(fontScale = 2f, suffix = "-font2x")

    private fun capture(fontScale: Float, suffix: String) {
        val gridKinds = listOf("shelf-subjects", "shelf-people")
        var kind by mutableStateOf(gridKinds.first())
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.size(width = 400.dp, height = 640.dp)) {
                        // 同生产：海报卡与人物卡各按自己的格宽排列
                        ContentGrid(cell = if (kind == gridKinds.first()) GridCell.Poster else GridCell.Person) {
                            val subjects = kind == gridKinds.first()
                            when {
                                subjects && showReal -> items(titles) { title ->
                                    PosterCard(imageUrl = null, title = title, style = PosterCardStyle.GRID, onClick = null, mark = null)
                                }
                                subjects -> items(6) { PosterCardSkeleton(PosterCardStyle.GRID) }
                                showReal -> items(titles) { title ->
                                    PersonGridItem(title = title, imageUrl = null, onClick = null)
                                }
                                else -> items(6) { PersonGridItemSkeleton() }
                            }
                        }
                    }
                }
            }
        }
        gridKinds.forEach { gridKind ->
            composeRule.runOnIdle { kind = gridKind }
            listOf("skeleton" to false, "content" to true).forEach { (name, real) ->
                composeRule.runOnIdle { showReal = real }
                composeRule.waitForIdle()
                val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
                saveScreenshot(FOLDER, "$gridKind$suffix-$name", bitmap)
            }
        }
    }
}
