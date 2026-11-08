package io.github.touhougo.chogumi.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 时间线正文的分段：**纯文本 + 内联表情**。
 *
 * ## 为什么不是 HTML 也不是 BBCode
 *
 * 查证结论：
 *
 * | 来源 | 服务端 | 客户端拿到 |
 * |---|---|---|
 * | 时间线 status | 写入 `lo.escape`、读取 `lo.unescape` | 反转义后**纯文本** |
 * | 回复 content | 两端都不处理 | 用户原样输入 |
 *
 * 故 `HtmlCompat.fromHtml(...)` 的前提是错的 —— 数据里没有 HTML。
 * 表情只可能以纯文本 `(bgmNN)`、`(musume_NN)` 或颜文字原文 `(=w=)` 出现。
 *
 * ## 分段的职责
 *
 * 把一段正文切成有序的「文本 / 表情」单元，交给 Compose 交错渲染。
 * 分割逻辑是纯函数，故可直接单测（渲染本身需要设备，不在此测）。
 */
class TimelineBodySegmentTest {

    @Test
    fun `纯文本切成单个文本段`() {
        assertEquals(
            listOf(BodySegment.Text("今天看了部好番")),
            segmentTimelineBody("今天看了部好番"),
        )
    }

    @Test
    fun `内联表情被切出来`() {
        assertEquals(
            listOf(
                BodySegment.Text("笑死"),
                BodySegment.Smiley("bgm38"),
                BodySegment.Text("确实"),
            ),
            segmentTimelineBody("笑死(bgm38)确实"),
        )
    }

    @Test
    fun `开头就是表情`() {
        assertEquals(
            listOf(BodySegment.Smiley("bgm38"), BodySegment.Text("开头")),
            segmentTimelineBody("(bgm38)开头"),
        )
    }

    @Test
    fun `结尾就是表情`() {
        assertEquals(
            listOf(BodySegment.Text("结尾"), BodySegment.Smiley("bgm38")),
            segmentTimelineBody("结尾(bgm38)"),
        )
    }

    @Test
    fun `连续多个表情之间不产生空文本段`() {
        assertEquals(
            listOf(BodySegment.Smiley("bgm38"), BodySegment.Smiley("bgm72")),
            segmentTimelineBody("(bgm38)(bgm72)"),
        )
    }

    /**
     * **越界 code 退回原文**。
     *
     * `(bgm999)` 语法合法但解析不出地址 —— 显示原文比显示空白诚实，
     * 用户至少能看到对方写了什么。
     */
    @Test
    fun `越界的表情code保留原文`() {
        assertEquals(
            listOf(BodySegment.Text("(bgm999)")),
            segmentTimelineBody("(bgm999)"),
        )
    }

    /**
     * **普通括号不是表情**。
     *
     * 与 `BBCodeNestingSemanticsTest` 的 `普通括号内容不被误判成表情` 同源：
     * `(笑)`、`(1)` 这类必须原样保留。
     */
    @Test
    fun `普通括号内容不被误判成表情`() {
        assertEquals(listOf(BodySegment.Text("这是(普通的括号)")), segmentTimelineBody("这是(普通的括号)"))
        assertEquals(listOf(BodySegment.Text("(1)")), segmentTimelineBody("(1)"))
    }

    /**
     * **字面 HTML 按字面显示**。
     *
     * 这是安全底线：用户输入里可能出现 `<b>`、`<script>` 这类字符，
     * 它们必须作为**文本**呈现，绝不能被当作富文本或代码执行
     * （走 `fromHtml` 就是在做这件事，是错的）。
     */
    @Test
    fun `字面HTML按字面显示而不被解析`() {
        assertEquals(
            listOf(BodySegment.Text("<b>不是粗体</b>")),
            segmentTimelineBody("<b>不是粗体</b>"),
        )
        assertEquals(
            listOf(BodySegment.Text("<script>alert(1)</script>")),
            segmentTimelineBody("<script>alert(1)</script>"),
        )
    }

    @Test
    fun `颜文字按原文切出_大小写不同是两个`() {
        assertEquals(
            listOf(BodySegment.Text("初恋的味道"), BodySegment.Smiley("=///="), BodySegment.Smiley("=W="), BodySegment.Text("(=x=)")),
            segmentTimelineBody("初恋的味道(=///=)(=W=)(=x=)"),
        )
    }

    @Test
    fun `空串切出空列表`() {
        assertEquals(emptyList(), segmentTimelineBody(""))
    }
}
