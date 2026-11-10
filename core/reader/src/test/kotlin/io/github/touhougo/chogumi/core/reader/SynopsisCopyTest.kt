package io.github.touhougo.chogumi.core.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 简介：长按正文复制去掉标记的全文（开头的引用也留着，那不是别人的话），点正文仍是切换折叠、不复制。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w448dp-h900dp")
class SynopsisCopyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
    private var toggles = 0

    private fun show() {
        clipboard.setPrimaryClip(ClipData.newPlainText(null, BEFORE))
        composeRule.setContent {
            ChogumiTheme {
                CollapsibleSynopsis(text = SYNOPSIS, expanded = false, onToggle = { toggles++ })
            }
        }
    }

    /** 第二行正文的中间、离链接远：链接的点按区域按最小触控尺寸向外扩，按在它附近的点与长按先被链接接住。 */
    private fun TouchInjectionScope.bodyPoint() = percentOffset(0.5f, 0.8f)

    private fun clip(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun 长按简介_复制去掉标记的全文() {
        show()
        composeRule.onNodeWithTag(SynopsisTag).performTouchInput { longClick(bodyPoint()) }
        composeRule.runOnIdle {
            assertEquals("出自原作\n粗体与链接", clip())
            assertEquals(0, toggles)
        }
    }

    @Test
    fun 点简介_切换折叠不复制() {
        show()
        composeRule.onNodeWithTag(SynopsisTag).performTouchInput { click(bodyPoint()) }
        composeRule.runOnIdle {
            assertEquals(1, toggles)
            assertEquals(BEFORE, clip())
        }
    }

    private companion object {
        const val SYNOPSIS = "[quote]出自原作[/quote]\n[b]粗体[/b]与[url=https://bgm.tv]链接[/url]"
        const val BEFORE = "原来的剪贴板"
    }
}
