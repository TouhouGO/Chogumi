package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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
import io.github.touhougo.chogumi.core.model.EpPendingVisual
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
 * 同一行在「无徽标 / +1 / +12」三种状态下的截图（[saveScreenshot] 的 `progress-row-badge/`）：进度恒为 1/12，
 * 三张图里进度条的位置与长度应当一致。列宽由 [CollectionListItemSlotTest] 逐状态断言。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class ProgressRowScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private var pending by mutableStateOf<EpPendingVisual?>(null)

    private companion object {
        const val FOLDER = "progress-row-badge"
    }

    @Test
    fun 无徽标_加1_加12三种状态各存一张截图() = captureThreeStates(fontScale = 1f, prefix = "")

    @Test
    fun 字体放大两倍时三种状态各存一张截图() = captureThreeStates(fontScale = 2f, prefix = "font2x-")

    private fun captureThreeStates(fontScale: Float, prefix: String) {
        val title = "葬送的芙莉莲"
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(400.dp)) {
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
                            pendingVisual = pending,
                        )
                    }
                }
            }
        }
        listOf(
            "1-none" to null,
            "2-plus1" to EpPendingVisual(count = 1, commitAtNanos = 0L),
            "3-plus12" to EpPendingVisual(count = 12, commitAtNanos = 0L),
        ).forEach { (name, visual) ->
            composeRule.runOnIdle { pending = visual }
            composeRule.waitForIdle()
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "$prefix$name", bitmap)
        }
    }
}
