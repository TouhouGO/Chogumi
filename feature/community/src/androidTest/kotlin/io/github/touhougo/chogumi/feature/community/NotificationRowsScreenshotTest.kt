package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.composables.LocalNow
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic light and dark captures at the three review widths. The production rows and both skeleton siblings are used
 * directly; the density override fits an 800dp logical layout into the available device frame.
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class NotificationRowsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dark = mutableStateOf(false)

    @Test
    fun `448dp浅深色`() = capture(width = 448, fontScale = 1f, prefix = "448")

    @Test
    fun `380dp字号一点一倍浅深色`() = capture(width = 380, fontScale = 1.1f, prefix = "380-font1.1")

    @Test
    fun `800dp浅深色`() = capture(width = 800, fontScale = 1f, prefix = "800")

    private fun capture(width: Int, fontScale: Float, prefix: String) {
        compose.setContent {
            val density = LocalDensity.current
            val screenWidth = LocalConfiguration.current.screenWidthDp
            val fittedDensity = Density(
                density = density.density * screenWidth / width,
                fontScale = fontScale,
            )
            val now = remember { mutableLongStateOf(NotificationSyntheticFixtures.now) }
            CompositionLocalProvider(LocalDensity provides fittedDensity, LocalNow provides now) {
                ChogumiTheme(darkTheme = dark.value) {
                    Surface(
                        modifier = Modifier.width(width.dp).testTag(CAPTURE_TAG),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(CAPTURE_PADDING)) {
                            NotificationItemRow(
                                item = NotificationSyntheticFixtures.notice(unread = true),
                                onUser = {},
                                onOpen = {},
                            )
                            NotificationItemRow(
                                item = NotificationSyntheticFixtures.notice(unread = false),
                                onUser = {},
                                onOpen = {},
                            )
                            ConversationRow(
                                conversation = NotificationSyntheticFixtures.conversation(NotificationSyntheticFixtures.UNREAD_COUNT),
                                onClick = {},
                            )
                            ConversationRow(
                                conversation = NotificationSyntheticFixtures.conversation(unread = 0),
                                onClick = {},
                            )
                            NotificationItemSkeleton()
                            ConversationRowSkeleton()
                        }
                    }
                }
            }
        }
        listOf(false, true).forEach { night ->
            compose.runOnIdle { dark.value = night }
            compose.waitForIdle()
            val image = compose.onNodeWithTag(CAPTURE_TAG).captureToImage()
            saveScreenshot(FOLDER, "$prefix-${if (night) "dark" else "light"}", image.asAndroidBitmap())
        }
    }

    private companion object {
        const val CAPTURE_TAG = "notification-unread-capture"
        const val FOLDER = "notification-unread-semantics"
        val CAPTURE_PADDING = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
    }
}
