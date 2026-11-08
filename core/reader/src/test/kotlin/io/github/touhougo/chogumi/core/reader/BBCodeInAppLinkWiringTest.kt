package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * 站内链接**接线**必须打通。
 * 只测纯函数 inAppRoute 时，`onInAppLink` 没传进 buildText 也照样全绿，
 * 全部站内链接静默走了浏览器——单测只测纯函数逮不住接线断。
 */
class BBCodeInAppLinkWiringTest {

    private fun annotationsOf(annotated: androidx.compose.ui.text.AnnotatedString): List<LinkAnnotation> =
        annotated.getLinkAnnotations(0, annotated.length).map { it.item }

    @Test
    fun `站内链接产出Clickable而不是Url`() {
        val ast = BBCodeParser.parse("[url=https://bgm.tv/subject/123]条目[/url]")
        val blocks = ast.nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = {}, onUserClick = null)
        val annotated = blocks.filterIsInstance<Block.Inline>().firstOrNull()
        assertNotNull(annotated, "应产出行内文本块")
        val links = annotationsOf(annotated.text)
        assertEquals(1, links.size, "应有一个链接注解")
        assertIs<LinkAnnotation.Clickable>(links.first(), "站内链接必须是 Clickable（经 onInAppLink），不能是 Url（浏览器）")
    }

    @Test
    fun `站外链接仍是Url`() {
        val ast = BBCodeParser.parse("[url=https://example.com]外站[/url]")
        val blocks = ast.nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = {}, onUserClick = null)
        val annotated = blocks.filterIsInstance<Block.Inline>().firstOrNull()
        assertNotNull(annotated)
        val links = annotationsOf(annotated.text)
        assertEquals(1, links.size)
        assertIs<LinkAnnotation.Url>(links.first())
    }

    @Test
    fun `触发Clickable回调拿到条目路由`() {
        var received: Route? = null
        val ast = BBCodeParser.parse("[url=https://bgm.tv/subject/123]条目[/url]")
        val blocks = ast.nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = { received = it }, onUserClick = null)
        val annotated = blocks.filterIsInstance<Block.Inline>().firstOrNull()
        assertNotNull(annotated)
        val link = annotationsOf(annotated.text).first()
        assertIs<LinkAnnotation.Clickable>(link)
        // 直接触发监听器，断言解析结果送达回调（LinkInteractionListener.onClicked）
        link.linkInteractionListener?.onClick(link)
        assertEquals(Route.SubjectDetails(123), received, "点击应把条目 123 的路由送达回调")
    }

    @Test
    fun `handler为null时站内链接退回Url`() {
        val ast = BBCodeParser.parse("[url=https://bgm.tv/subject/123]条目[/url]")
        val blocks = ast.nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = null)
        val annotated = blocks.filterIsInstance<Block.Inline>().firstOrNull()
        assertNotNull(annotated)
        val links = annotationsOf(annotated.text)
        assertIs<LinkAnnotation.Url>(links.first(), "未接线环境（预览/测试）应退回浏览器行为")
    }
}
