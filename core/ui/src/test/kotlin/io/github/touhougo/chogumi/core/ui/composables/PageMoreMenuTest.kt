package io.github.touhougo.chogumi.core.ui.composables

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalIndexPicker
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.common.shareTextOf
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 页面 ⋮：固定的「网页」「分享」（能收录的页再加「收录」）在前，页面自己的项在后；点了哪一项菜单都收起。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageMoreMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val opened = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun show(extra: (@Composable PageMoreMenuScope.() -> Unit)? = null) {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    PageMoreMenu(PageLink(TITLE, URL), extra = extra)
                }
            }
        }
        composeRule.onNodeWithContentDescription(app.getString(R.string.page_more)).performClick()
    }

    @Test
    fun `网页在 Custom Tab 打开这一页的地址 然后菜单收起`() {
        show()
        composeRule.onNodeWithText(app.getString(R.string.page_more_web)).performClick()
        assertEquals(listOf(URL), opened)
        composeRule.onNodeWithText(app.getString(R.string.page_more_web)).assertDoesNotExist()
    }

    @Test
    fun `分享交给系统分享面板 正文是标题一行链接一行`() {
        show()
        composeRule.onNodeWithText(app.getString(R.string.page_more_share)).performClick()
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("$TITLE\n$URL", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `页面自己的项排在后面 点了执行一次并收起菜单`() {
        var blocked = 0
        show { item(R.string.block, R.drawable.block_24) { blocked++ } }
        val web = composeRule.onNodeWithText(app.getString(R.string.page_more_web)).fetchSemanticsNode().boundsInRoot
        val block = composeRule.onNodeWithText(app.getString(R.string.block)).fetchSemanticsNode().boundsInRoot
        assertTrue("页面的项应排在固定项下面：网页 $web，绝交 $block", block.top > web.bottom)
        composeRule.onNodeWithText(app.getString(R.string.block)).performClick()
        assertEquals(1, blocked)
        composeRule.onNodeWithText(app.getString(R.string.block)).assertDoesNotExist()
    }

    @Test
    fun `页面菜单项先收起再执行动作`() {
        val calls = mutableListOf<String>()
        val scope = PageMoreMenuScope { calls += "收起" }
        composeRule.setContent {
            ChogumiTheme {
                with(scope) { item(R.string.block, R.drawable.block_24) { calls += "执行" } }
            }
        }
        composeRule.onNodeWithText(app.getString(R.string.block)).performClick()
        composeRule.runOnIdle { assertEquals(listOf("收起", "执行"), calls) }
    }

    /** 收录：能收录的页给了 [IndexCollect]，点「收录」先过登录门，已登录才打开面板，面板拿到的是页面交出的目标。 */
    private fun showCollectable(loggedIn: Boolean, picked: MutableList<IndexTarget>, logins: () -> Unit) {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(
                    LocalUriHandler provides uriHandler,
                    LocalIndexPicker provides { target, _ -> SideEffect { picked += target } },
                ) {
                    PageMoreMenu(
                        PageLink(TITLE, URL),
                        IndexCollect(IndexTarget(IndexCategory.GROUP_TOPIC, 42), rememberLoginGate(loggedIn, logins)),
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription(app.getString(R.string.page_more)).performClick()
    }

    @Test
    fun `收录_已登录打开收录面板_拿到页面交出的目标`() {
        val picked = mutableListOf<IndexTarget>()
        showCollectable(loggedIn = true, picked) { error("已登录不该去登录页") }
        composeRule.onNodeWithText(app.getString(R.string.page_more_collect)).performClick()
        composeRule.waitForIdle()
        assertEquals(IndexTarget(IndexCategory.GROUP_TOPIC, 42), picked.distinct().single())
        composeRule.onNodeWithText(app.getString(R.string.page_more_collect)).assertDoesNotExist()
    }

    @Test
    fun `收录_未登录去登录页_不开面板`() {
        val picked = mutableListOf<IndexTarget>()
        var logins = 0
        showCollectable(loggedIn = false, picked) { logins++ }
        composeRule.onNodeWithText(app.getString(R.string.page_more_collect)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, logins)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `收录_不能收录的页与没接面板时不出现`() {
        show()
        composeRule.onNodeWithText(app.getString(R.string.page_more_share)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.page_more_collect)).assertDoesNotExist()
    }

    @Test
    fun `标题还没到时只分享链接`() {
        assertEquals(URL, shareTextOf("", URL))
        assertEquals("$TITLE\n$URL", shareTextOf(TITLE, URL))
    }

    private companion object {
        const val TITLE = "某个话题"
        const val URL = "https://example.org/topic/1"
    }
}
