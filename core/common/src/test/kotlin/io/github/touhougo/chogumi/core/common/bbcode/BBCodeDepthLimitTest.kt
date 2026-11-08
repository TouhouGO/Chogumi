package io.github.touhougo.chogumi.core.common.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用户写的正文是不可信输入：成千上万层未闭合的标签不能撑爆栈，也不能花掉可感知的时间。 */
class BBCodeDepthLimitTest {

    @Test
    fun `一万层未闭合的粗体不抛异常且很快返回`() {
        val source = "[b]".repeat(10_000) + "尾"
        val (ast, millis) = timedParse(source)
        assertTrue(ast.plainText.endsWith("尾"), "正文不丢")
        assertTrue(millis < 200, "解析耗时 ${millis}ms")
    }

    @Test
    fun `五万个没闭合的方括号文字不逐个往后扫正文`() {
        val source = "[动画]".repeat(50_000) + "[b]尾[/B]"
        val (ast, millis) = timedParse(source)
        assertEquals("[动画]".repeat(50_000) + "尾", ast.plainText, "方括号文字按字面留下，后面的粗体照常闭合")
        assertTrue(millis < 1_000, "解析耗时 ${millis}ms")
    }

    @Test
    fun `一万层嵌套的引用同样返回`() {
        val source = "[quote]".repeat(10_000) + "尾" + "[/quote]".repeat(10_000)
        assertTrue(BBCodeParser.parse(source).nodes.first() is BBCodeNode.Quote, "最外层仍是引用块")
    }

    /**
     * 解析 [source] 的耗时（毫秒）：先不计时解析一遍，量的是第二遍。第一遍里有类加载与 JIT 预热，
     * 在共享的 CI 机器上能占掉几百毫秒，那是 JVM 的冷启动，不是解析器的复杂度。
     */
    private fun timedParse(source: String): Pair<BBCodeAst, Long> {
        BBCodeParser.parse(source)
        val started = System.nanoTime()
        val ast = BBCodeParser.parse(source)
        return ast to (System.nanoTime() - started) / 1_000_000
    }
}
