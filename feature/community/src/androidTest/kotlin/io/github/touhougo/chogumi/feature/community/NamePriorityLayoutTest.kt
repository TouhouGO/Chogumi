package io.github.touhougo.chogumi.feature.community

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
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
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 姓名优先的三种生产行：通知发送者、动态作者与帖子作者。普通字号保持同一基线；两倍字号下姓名需要整行时，
 * 尾部动作或来源移到下一行，姓名只在自己一行也放不下时才截断。
 */
@RunWith(AndroidJUnit4::class)
class NamePriorityLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 通知姓名在普通一倍字号下与动作同基线且都完整() {
        setNotice(name = "小明", fontScale = 1f)

        assertComplete("小明")
        assertComplete("收到新通知")
        assertSameBaseline("小明", "收到新通知")
    }

    @Test
    fun 通知姓名在小屏常用一点一倍字号下仍优先完整显示() {
        setNotice(name = "小明", fontScale = 1.1f)

        assertComplete("小明")
        assertComplete("收到新通知")
        assertSameBaseline("小明", "收到新通知")
    }

    @Test
    fun 两倍字号下中文长姓名独占一行且动作完整换到下一行() {
        setNotice(name = "姓名优先显示", fontScale = 2f)

        assertComplete("姓名优先显示")
        assertComplete("收到新通知")
        assertSameBaseline("姓名优先显示", "收到新通知", expectSeparateLines = true)
    }

    @Test
    fun 两倍字号下英文姓名同样先占整行() {
        setNotice(name = "NameFirst", fontScale = 2f, locale = "en")

        assertComplete("NameFirst")
        assertComplete("New notification")
        assertSameBaseline("NameFirst", "New notification", expectSeparateLines = true)
    }

    @Test
    fun 两倍字号下姓名自身整行仍不足才省略() {
        val name = "姓名".repeat(20)
        setNotice(name = name, fontScale = 2f)

        assertTrue("姓名自身仍超过一整行时应省略", layoutOf(name).multiParagraph.didExceedMaxLines)
        assertComplete("收到新通知")
        assertSameBaseline(name, "收到新通知", expectSeparateLines = true)
    }

    @Test
    fun RTL通知同行时姓名在动作右侧() {
        setNotice(name = "小明", fontScale = 1f, direction = LayoutDirection.Rtl)
        val name = bounds("小明")
        val action = bounds("收到新通知")

        assertTrue("RTL 中姓名应在动作右侧", name.left > action.left)
        assertSameBaseline("小明", "收到新通知")
    }

    @Test
    fun RTL通知换行后姓名与动作都靠右() {
        setNotice(name = "姓名优先显示", fontScale = 2f, direction = LayoutDirection.Rtl)
        val name = bounds("姓名优先显示")
        val action = bounds("收到新通知")

        assertComplete("姓名优先显示")
        assertComplete("收到新通知")
        assertTrue("动作应在姓名下一行", action.top >= name.bottom - BaselineTolerance.dp)
        assertEquals("RTL 换行后姓名与动作应同靠右", name.right.value, action.right.value, BaselineTolerance)
    }

    @Test
    fun RTL动态作者的好友牌在姓名物理左侧() {
        setTimeline(author = "TimelineAuthor", fontScale = 1f, direction = LayoutDirection.Rtl)
        val name = bounds("TimelineAuthor")
        val friend = bounds("好友")

        assertTrue("RTL 中好友牌应在姓名左侧", friend.left < name.left)
    }

    @Test
    fun RTL帖子来源贴logicalEnd物理左侧() {
        setPost(author = "PostAuthor", source = "小组名称", fontScale = 1f, direction = LayoutDirection.Rtl)
        val author = bounds("PostAuthor")
        val source = bounds("小组名称")

        assertTrue("RTL 中来源应贴逻辑 end，即姓名物理左侧", source.left < author.left)
    }

    @Test
    fun 动态作者头在两倍字号下姓名先占整行() {
        setTimeline(author = "动态作者名字需要显示", fontScale = 2f)

        assertComplete("动态作者名字需要显示")
        assertComplete("好友")
        assertSameBaseline("动态作者名字需要显示", "好友", expectSeparateLines = true)
    }

    @Test
    fun 帖子作者在两倍字号下姓名先占整行_来源随后() {
        setPost(author = "PostAuthorLong", source = "小组名称", fontScale = 2f)

        assertComplete("PostAuthorLong")
        assertComplete("小组名称")
        assertSameBaseline("PostAuthorLong", "小组名称", expectSeparateLines = true)
    }

    private fun setNotice(
        name: String,
        fontScale: Float,
        locale: String? = null,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            withDensity(fontScale) {
                withLocale(locale) {
                    withDirection(direction) {
                        ChogumiTheme {
                            Surface {
                                Box(Modifier.width(NotificationContentWidth)) {
                                    NotificationItemRow(
                                        item = NoticeBo(
                                            id = 1,
                                            type = 0,
                                            sender = NoticeSenderBo(id = 1, username = name, nickname = name),
                                            title = "通知标题",
                                            mainId = 1,
                                            relatedId = 1,
                                            createdAt = 0L,
                                            unread = true,
                                        ),
                                        onUser = {},
                                        onOpen = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setTimeline(author: String, fontScale: Float, direction: LayoutDirection = LayoutDirection.Ltr) {
        composeRule.setContent {
            withDensity(fontScale) {
                withDirection(direction) {
                    ChogumiTheme {
                        Surface {
                            CompositionLocalProvider(LocalFriends provides Friends(setOf(1))) {
                                Box(Modifier.width(380.dp)) {
                                    TimelineGroupHeader(
                                        user = UserBo(id = 1, username = author, nickname = author),
                                        uid = 1,
                                        createdAt = 0L,
                                        count = 1,
                                        markFriend = true,
                                        onUser = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setPost(
        author: String,
        source: String,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            withDensity(fontScale) {
                withDirection(direction) {
                    ChogumiTheme {
                        Surface {
                            Box(Modifier.width(380.dp)) {
                                PostListItem(
                                    title = "帖子标题",
                                    replies = 0,
                                    repliesDescription = "0 条回复",
                                    index = 0,
                                    count = 1,
                                    onClick = {},
                                    leading = PostLeading.Avatar(null),
                                    author = author,
                                    source = source,
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
    private fun withDensity(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            content()
        }
    }

    @Composable
    private fun withDirection(direction: LayoutDirection, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            content()
        }
    }

    @Composable
    private fun withLocale(locale: String?, content: @Composable () -> Unit) {
        if (locale == null) {
            content()
            return
        }
        val base = LocalContext.current
        val localized = remember(base, locale) { base.localized(locale) }
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides localized.resources.configuration,
            LocalResources provides localized.resources,
        ) {
            content()
        }
    }

    private fun assertComplete(text: String) {
        val layout = layoutOf(text)
        assertFalse("「$text」折到单行上限之外", layout.multiParagraph.didExceedMaxLines)
        assertTrue(
            "「$text」画出自己的宽度",
            layout.getLineRight(layout.lineCount - 1) <= layout.size.width + WidthTolerance,
        )
    }

    private fun assertSameBaseline(primary: String, trailing: String, expectSeparateLines: Boolean = false) {
        val primaryBounds = composeRule.onNodeWithText(primary, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val trailingBounds = composeRule.onNodeWithText(trailing, useUnmergedTree = true).getUnclippedBoundsInRoot()
        if (expectSeparateLines) {
            assertTrue(
                "「$trailing」应在「$primary」下一行，实际 ${trailingBounds.top} < ${primaryBounds.bottom}",
                trailingBounds.top >= primaryBounds.bottom - BaselineTolerance.dp,
            )
        } else {
            assertEquals("「$primary」与「$trailing」基线不同", baseline(primary), baseline(trailing), BaselineTolerance)
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private fun bounds(text: String) = composeRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun baseline(text: String): Float {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
        return node.getUnclippedBoundsInRoot().top.value + node.getAlignmentLinePosition(androidx.compose.ui.layout.FirstBaseline).value
    }

    private fun Context.localized(locale: String): Context {
        val configuration = Configuration(resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(locale)))
        }
        return createConfigurationContext(configuration)
    }

    private companion object {
        /** 380dp 屏在通知面板左右各 16dp 后的内容宽；列表行内部再给圆点与头像留位。 */
        val NotificationContentWidth = 348.dp
        const val WidthTolerance = 0.5f
        const val BaselineTolerance = 1f
    }
}
