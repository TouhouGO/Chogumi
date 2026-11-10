package io.github.touhougo.chogumi.feature.community

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.common.navigation.InboxTab
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** 不用服务器与真实通知内容，截取生产标题组件的指定宽度、字号和明暗组合。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class NotificationHeaderScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private data class State(
        val width: Int,
        val fontScale: Float,
        val dark: Boolean,
        val english: Boolean,
        val name: String,
    )

    private var state by mutableStateOf(State(448, 1f, false, english = false, name = "448-light"))

    @Test
    fun 通知标题在验收宽度字号与明暗下各存一张() {
        compose.setContent {
            val density = LocalDensity.current
            val context = LocalContext.current
            val localizedContext = remember(context, state.english) {
                val configuration = Configuration(context.resources.configuration).apply {
                    setLocale(if (state.english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
                }
                context.createConfigurationContext(configuration)
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, state.fontScale),
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalResources provides localizedContext.resources,
            ) {
                ChogumiTheme(darkTheme = state.dark) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Surface(
                            modifier = Modifier.width(state.width.dp).height(420.dp).testTag(SURFACE),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                NotificationHeader(
                                    shown = InboxTab.NOTICES,
                                    unreadCount = 1,
                                    unreadMessages = 0,
                                    markAllEnabled = true,
                                    onSelect = {},
                                    onMarkAll = {},
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                repeat(3) { PreviewRow() }
                            }
                        }
                    }
                }
            }
        }

        listOf(
            State(448, 1f, false, english = false, name = "448-light"),
            State(448, 1f, true, english = false, name = "448-dark"),
            State(380, 1.1f, false, english = true, name = "380-font1.1-en-light"),
            State(380, 1.1f, true, english = true, name = "380-font1.1-en-dark"),
            State(800, 1f, false, english = false, name = "800-light"),
            State(800, 1f, true, english = false, name = "800-dark"),
            State(380, 2f, false, english = true, name = "380-font2-en-light"),
            State(380, 1f, false, english = false, name = "380-font1-zh"),
            State(380, 1f, false, english = true, name = "380-font1-en"),
        ).forEach { next ->
            compose.runOnIdle { state = next }
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag(SURFACE, useUnmergedTree = true).captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, next.name, bitmap)
        }
    }

    private companion object {
        const val FOLDER = "notification-header"
        const val SURFACE = "notification-screenshot-surface"
    }
}

@androidx.compose.runtime.Composable
private fun PreviewRow() {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(16.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest))
            Box(Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
    }
}
