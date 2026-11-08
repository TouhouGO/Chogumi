package io.github.touhougo.chogumi.feature.community

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.model.NoticeSenderBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.reader.TimelineGroupHeader
import io.github.touhougo.chogumi.core.ui.common.LocalFriends
import io.github.touhougo.chogumi.core.ui.composables.PostLeading
import io.github.touhougo.chogumi.core.ui.composables.PostListItem
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 三类生产行在设备宽度、字号与主题组合下的截图。每张同时显示通知发送者、动态作者和帖子作者，
 * 便于人工比较普通行是否保持基线、窄宽度长姓名是否先占整行。截图不计测试数。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class NamePriorityScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var fontScale by mutableFloatStateOf(1f)
    private var darkTheme by mutableStateOf(false)
    private var locale by mutableStateOf<String?>(null)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    @Test
    fun capture448() {
        setContent()
        captureThemes(prefix = "448-font-1.0", fontScale = 1f)
    }

    @Test
    fun capture380() {
        setContent()
        captureThemes(prefix = "380-font-1.1", fontScale = 1.1f)
        captureThemes(prefix = "380-font-2.0-zh", fontScale = 2f)
        composeRule.runOnIdle { locale = "en" }
        captureThemes(prefix = "380-font-2.0-en", fontScale = 2f)
    }

    @Test
    fun capture380Rtl() {
        setContent()
        composeRule.runOnIdle { direction = LayoutDirection.Rtl }
        captureThemes(prefix = "380-font-1.0-rtl", fontScale = 1f)
        captureThemes(prefix = "380-font-2.0-rtl", fontScale = 2f)
    }

    @Test
    fun capture800() {
        setContent()
        captureThemes(prefix = "800-font-1.0", fontScale = 1f)
    }

    private fun setContent() {
        composeRule.setContent {
            NamePriorityScreenshotContent(
                fontScale = fontScale,
                darkTheme = darkTheme,
                locale = locale,
                direction = direction,
                noticeName = if (fontScale == 2f) {
                    if (locale == "en") "NameFirst" else "姓名优先显示"
                } else {
                    "小明"
                },
                timelineName = if (fontScale == 2f) {
                    if (locale == "en") "TimelineAuthor" else "动态作者名字需要显示"
                } else {
                    "动态作者"
                },
            )
        }
    }

    private fun captureThemes(prefix: String, fontScale: Float) {
        listOf(false, true).forEach { dark ->
            composeRule.runOnIdle {
                this.fontScale = fontScale
                darkTheme = dark
            }
            composeRule.waitForIdle()
            val name = "$prefix-${if (dark) "dark" else "light"}"
            saveScreenshot(CaptureFolder, name, composeRule.onRoot().captureToImage().asAndroidBitmap())
        }
        composeRule.runOnIdle { darkTheme = false }
    }

    private companion object {
        const val CaptureFolder = "notification-name-priority"
    }
}

@Composable
private fun NamePriorityScreenshotContent(
    fontScale: Float,
    darkTheme: Boolean,
    locale: String?,
    direction: LayoutDirection,
    noticeName: String,
    timelineName: String,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            withLocale(locale) {
                ChogumiTheme(darkTheme = darkTheme) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surface).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CompositionLocalProvider(LocalFriends provides Friends(setOf(1))) {
                                NotificationItemRow(
                                    item = NoticeBo(
                                        id = 1,
                                        type = 0,
                                        sender = NoticeSenderBo(id = 1, username = noticeName, nickname = noticeName),
                                        title = "通知标题",
                                        mainId = 1,
                                        relatedId = 1,
                                        createdAt = 0L,
                                        unread = true,
                                    ),
                                    onUser = {},
                                    onOpen = {},
                                )
                                TimelineGroupHeader(
                                    user = UserBo(id = 1, username = timelineName, nickname = timelineName),
                                    uid = 1,
                                    createdAt = 0L,
                                    count = 1,
                                    markFriend = true,
                                    onUser = {},
                                )
                            }
                            PostListItem(
                                title = "帖子标题",
                                replies = 0,
                                repliesDescription = "0 条回复",
                                index = 0,
                                count = 1,
                                onClick = {},
                                modifier = Modifier.fillMaxWidth(),
                                leading = PostLeading.Avatar(null),
                                author = if (locale == "en") "PostAuthorLong" else "帖子作者名字需要显示",
                                source = if (locale == "en") "Source" else "小组名称",
                                time = "刚刚",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun withLocale(locale: String?, content: @Composable () -> Unit) {
    if (locale == null) {
        content()
        return
    }
    val base = LocalContext.current
    val localized = remember(base, locale) {
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(locale)))
        }
        base.createConfigurationContext(configuration)
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        LocalResources provides localized.resources,
    ) {
        content()
    }
}
