package io.github.touhougo.chogumi.feature.monodetails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@VisualCapture
@RunWith(AndroidJUnit4::class)
class MonoIndexesScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private var dark by mutableStateOf(false)
    private var sheetOpen by mutableStateOf(false)

    @Test
    fun capture448() = captureWidth(fontScale = 1f, prefix = "448")

    @Test
    fun capture380Font11() = captureWidth(fontScale = 1.1f, prefix = "380-font1.1")

    @Test
    fun capture800() = captureWidth(fontScale = 1f, prefix = "800")

    private fun captureWidth(fontScale: Float, prefix: String) {
        val state = PagedList(List(31) { index(it + 1) }, total = 31, nextOffset = null, isLoading = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme(darkTheme = dark) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        if (sheetOpen) {
                            PageSheetTestHost {
                                MonoIndexesSheet(
                                    state = state,
                                    onDismiss = {},
                                    onRetry = {},
                                    onLoadMore = {},
                                    onIndexClick = {},
                                )
                            }
                        } else {
                            MonoIndexesEntry(state = state, onOpen = {}, onRetry = {})
                        }
                    }
                }
            }
        }

        listOf(false to "light", true to "dark").forEach { (isDark, themeName) ->
            compose.runOnIdle {
                dark = isDark
                sheetOpen = false
            }
            compose.waitForIdle()
            capture("$prefix-$themeName-entry")

            compose.runOnIdle { sheetOpen = true }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            capture("$prefix-$themeName-sheet")
        }
    }

    private fun capture(name: String) {
        saveScreenshot(FOLDER, name, compose.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun index(id: Int) = IndexBo(id = id, title = "目录 $id", total = 0, createdAt = id.toLong())

    private companion object {
        const val FOLDER = "mono-indexes"
    }
}
