package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 进度行的骨架与真实内容各一张截图（字体比例 1 与 2），叠起来看对不对得上（[saveScreenshot] 的 `progress-row-skeleton/`）。
 * 位置由 [SkeletonGeometryTest] 逐边断言。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class SkeletonScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    private companion object {
        const val FOLDER = "progress-row-skeleton"
    }

    @Test
    fun 进度行骨架与内容各存一张截图() = captureSkeletonAndContent(fontScale = 1f, prefix = "progress-row")

    @Test
    fun 字体放大两倍时进度行骨架与内容各存一张截图() = captureSkeletonAndContent(fontScale = 2f, prefix = "progress-row-font2x")

    private fun captureSkeletonAndContent(fontScale: Float, prefix: String) {
        val title = "芙莉莲"
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.size(width = 400.dp, height = 200.dp)) {
                        if (showReal) {
                            CollectionListItem(
                                item = UserCollectionBo(
                                    subjectId = 1,
                                    subjectType = SubjectType.ANIME,
                                    type = CollectionType.DOING,
                                    epStatus = 1,
                                    subject = SubjectBo(id = 1, type = SubjectType.ANIME, name = title, nameCn = title, eps = 12),
                                ),
                                onClick = {},
                                onEdit = {},
                                progressCount = ProgressCount.Known(null, 1, 12),
                                epButtonState = EpButtonState.Next(
                                    EpisodeBo(id = 2, subjectId = 1, type = 0, name = "", nameCn = "", sort = 2.0),
                                    position = 2,
                                ),
                            )
                        } else {
                            CollectionListItemSkeleton(SubjectType.ANIME, CollectionType.DOING)
                        }
                    }
                }
            }
        }
        listOf("skeleton" to false, "content" to true).forEach { (name, real) ->
            composeRule.runOnIdle { showReal = real }
            composeRule.waitForIdle()
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "$prefix-$name", bitmap)
        }
    }
}
