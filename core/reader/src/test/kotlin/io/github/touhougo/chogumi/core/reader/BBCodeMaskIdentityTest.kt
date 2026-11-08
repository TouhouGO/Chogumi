package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 遮罩的揭示属于这一份正文，页面槽位和回调不决定新内容是否已揭开。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BBCodeMaskIdentityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `当前正文替换后新遮罩保持未揭示`() {
        var source by mutableStateOf("[mask]原稿[/mask]")
        compose.setContent {
            MaterialTheme {
                BBCodeText(remember(source) { BBCodeParser.parse(source) })
            }
        }
        reveal("原稿")
        assertRevealed("原稿")
        compose.runOnIdle { source = "[mask]新稿[/mask]" }
        assertHidden("新稿")
    }

    @Test
    fun `同正文的回调变化和存档恢复保留已揭示`() {
        var callback by mutableStateOf<((String) -> Unit)?>(null)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                BBCodeText(remember { BBCodeParser.parse("[mask][user=alice]原稿[/user][/mask]") }, onUserClick = callback)
            }
        }
        reveal("原稿")
        compose.runOnIdle { callback = {} }
        assertRevealed("原稿")
        restoration.emulateSavedInstanceStateRestore()
        assertRevealed("原稿")
    }

    @Test
    fun `离屏期间换文再恢复不沿用旧揭示`() {
        var source by mutableStateOf("[mask]原稿[/mask]")
        var shown by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                val holder = rememberSaveableStateHolder()
                Box {
                    if (shown) holder.SaveableStateProvider("body") {
                        BBCodeText(remember(source) { BBCodeParser.parse(source) })
                    }
                }
            }
        }
        reveal("原稿")
        compose.runOnIdle { shown = false }
        compose.runOnIdle { shown = true }
        assertRevealed("原稿", "同内容返回保留已揭示")
        compose.runOnIdle { shown = false }
        compose.runOnIdle { source = "[mask]新稿[/mask]" }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { shown = true }
        assertHidden("新稿")
    }

    @Test
    fun `长帖存档只给含遮罩的段落存定长摘要`() {
        val registry = SaveableStateRegistry(restoredValues = null) { true }
        val paragraph = "长".repeat(400)
        val source = (0 until 40).joinToString("\n\n") { i -> if (i % 4 == 0) "$paragraph[mask]第${i}段[/mask]" else paragraph }
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                MaterialTheme { BBCodeText(remember { BBCodeParser.parse(source) }) }
            }
        }
        val saved = compose.runOnIdle { registry.performSave() }.values.flatten()
        assertEquals(10, saved.size, "40 段里只有 10 段带遮罩，只有它们存揭示状态")
        val chars = saved.sumOf(::savedChars)
        assertTrue(chars <= 10 * 64, "每段只存 64 位摘要，不随全文长度增长：实际 $chars 字符")
    }

    private fun rendered(text: String): AnnotatedString =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()

    private fun reveal(text: String) {
        val link = rendered(text).getLinkAnnotations(0, text.length).map { it.item }
            .filterIsInstance<LinkAnnotation.Clickable>().single { it.tag == "mask-0" }
        compose.runOnIdle { requireNotNull(link.linkInteractionListener).onClick(link) }
    }

    private fun assertHidden(text: String) {
        val style = rendered(text).spanStyles.last { it.item.background == Color(0xFF555555) }.item
        assertEquals(style.background, style.color, "新正文必须保持同色遮罩")
    }

    private fun assertRevealed(text: String, message: String = "已揭示的同一正文仍可读") {
        val style = rendered(text).spanStyles.last { it.item.background == Color(0xFF555555) }.item
        assertNotEquals(style.background, style.color, message)
    }

    /** 存档里一份值占多少字符（字符串计长度，列表逐项累加）。 */
    private fun savedChars(value: Any?): Int = when (value) {
        is String -> value.length
        is List<*> -> value.sumOf(::savedChars)
        else -> 0
    }
}
