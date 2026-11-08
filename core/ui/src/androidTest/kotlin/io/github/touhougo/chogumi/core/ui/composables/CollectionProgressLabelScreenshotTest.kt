package io.github.touhougo.chogumi.core.ui.composables

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 人工核对不同窗口、字号与主题下的进度行；参数由设备设置给出。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class CollectionProgressLabelScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun 保存当前窗口进度行() {
        var captureName = ""
        compose.setContent {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.ENGLISH) }
            val density = LocalDensity.current
            val dark = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            captureName = "w${configuration.screenWidthDp}-font${"%.1f".format(Locale.US, density.fontScale)}-${if (dark) "dark" else "light"}"
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
            ) {
                ChogumiTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxWidth().height(180.dp)) {
                        CollectionListItem(
                            item = UserCollectionBo(
                                subjectId = 1,
                                subjectType = SubjectType.ANIME,
                                type = CollectionType.DOING,
                                subject = SubjectBo(id = 1, type = SubjectType.ANIME, name = "A very long series title", nameCn = "", eps = 1155),
                            ),
                            onClick = {},
                            onEdit = {},
                            progressCount = ProgressCount.Known(null, 438, 1155),
                            epButtonState = EpButtonState.Empty,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        saveScreenshot("progress-label-fit", captureName, compose.onRoot().captureToImage().asAndroidBitmap())
    }
}
