package io.github.touhougo.chogumi.core.common.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 嵌套与容错语义的回归契约。 */
class BBCodeNestingSemanticsTest {

    private fun parse(source: String) = BBCodeParser.parse(source)

    // ---- 基本标签 ----

    @Test
    fun `粗斜下划线删除线都能解析`() {
        val ast = parse("[b]粗[/b][i]斜[/i][u]下[/u][s]删[/s]")
        assertEquals(4, ast.nodes.size)
        assertTrue(ast.nodes[0] is BBCodeNode.Bold)
        assertTrue(ast.nodes[1] is BBCodeNode.Italic)
        assertTrue(ast.nodes[2] is BBCodeNode.Underline)
        assertTrue(ast.nodes[3] is BBCodeNode.Strike)
        assertEquals("粗斜下删", ast.plainText)
    }

    @Test
    fun `未闭合的已知标签宽松地吃到结尾`() {
        // 旧站宽松语义：不因为漏写闭合标签就丢弃内容
        val ast = parse("[b]没有闭合")
        assertEquals("没有闭合", ast.plainText)
        assertTrue(ast.nodes.single() is BBCodeNode.Bold)
    }

    // ---- 治愈语义（两种畸形输入）----

    @Test
    fun `手误的闭合标签结束最内层框架而不是泄漏成字面文本`() {
        // 本该写 [/color] 却写成了 [/size]：旧站治愈，next 会整段显示原文
        val ast = parse("[color=red]红字[/size]后面的正文")
        assertEquals("红字后面的正文", ast.plainText)
        assertTrue(
            ast.nodes.none { it is BBCodeNode.Text && it.value.contains("[/size]") },
            "手误的闭合标签不该作为字面文本出现在正文里",
        )
    }

    @Test
    fun `交叠的闭合标签按所属框架分配`() {
        // [s][mask]…[/s][/mask]：[/s] 先结束打开的最内层 [mask]，
        // 然后才轮到它自己的 [s]
        val ast = parse("[s][mask]内容[/s][/mask]")
        assertEquals("内容", ast.plainText)
        assertTrue(ast.nodes.none { it is BBCodeNode.Text && it.value.contains("[") })
    }

    @Test
    fun `顶层孤立的闭合标签被静默丢弃`() {
        val ast = parse("前[/b]后")
        assertEquals("前后", ast.plainText)
    }

    // ---- 未知标签：字面 vs 递归 ----

    @Test
    fun `未闭合的未知标签按字面括号文本渲染`() {
        // 纯中文方括号（wiki 片段、`[动画]` 这种）绝不能当标签吞掉
        val ast = parse("这是[动画]分类")
        assertEquals("这是[动画]分类", ast.plainText)
    }

    @Test
    fun `能闭合的未知标签照常递归`() {
        val ast = parse("[foo]内容[/foo]")
        val unknown = ast.nodes.single() as BBCodeNode.Unknown
        assertTrue(unknown.hasClosingTag)
        // 子节点被正确解析出来
        assertEquals("内容", unknown.children.joinToString("") { it.plainText() })
        // 但 plainText 保留方括号拼写：不知道怎么渲染时，原样显示比静默丢弃更诚实
        assertEquals("[foo]内容[/foo]", ast.plainText)
    }

    @Test
    fun `未闭合的未知标签与已闭合的在树上可区分`() {
        // 未闭合成 .Text（字面），闭合成 .Unknown（结构化）——
        // 这个区别是渲染层决定"当普通文字"还是"当未知标签降级"的依据
        assertTrue(parse("[动画]").nodes.single() is BBCodeNode.Text)
        assertTrue(parse("[foo]x[/foo]").nodes.single() is BBCodeNode.Unknown)
    }

    // ---- code：逐字捕获 ----

    @Test
    fun `code 里的方括号不被当作 BBCode`() {
        // wiki 模板写在 code 里：方括号若被解析，整帖排版就毁了
        val ast = parse("[code][b]不是粗体[/b] {{模板|参数}}[/code]")
        val block = ast.nodes.single() as BBCodeNode.CodeBlock
        assertEquals("[b]不是粗体[/b] {{模板|参数}}", block.text)
        // plainText 输出原文，不做任何标签解释
        assertEquals("[b]不是粗体[/b] {{模板|参数}}", ast.plainText)
    }

    @Test
    fun `未闭合的 code 回退成字面文本`() {
        val ast = parse("[code]没有闭合")
        assertTrue(ast.plainText.contains("[code]"))
        assertTrue(ast.plainText.contains("没有闭合"))
    }

    // ---- 链接与图片 ----

    @Test
    fun `url 带值取该值末端点为链接`() {
        val ast = parse("[url=https://bgm.tv]站点[/url]")
        val url = ast.nodes.single() as BBCodeNode.Url
        assertEquals("https://bgm.tv", url.url)
        assertEquals("站点", ast.plainText)
    }

    @Test
    fun `url 无值时用显示文本作链接`() {
        val ast = parse("[url]https://bgm.tv[/url]")
        assertEquals("https://bgm.tv", (ast.nodes.single() as BBCodeNode.Url).url)
    }

    @Test
    fun `img 的尺寸声明不被当成 URL`() {
        // [img=宽,高] 的值是尺寸：当成 URL 去请求只会 404，图片永远是灰块
        val ast = parse("[img=250,180]d6/c4/xxx.jpg[/img]")
        val image = ast.nodes.single() as BBCodeNode.Image
        assertEquals(250.0, image.width)
        assertEquals(180.0, image.height)
        assertEquals("https://lain.bgm.tv/pic/photo/l/d6/c4/xxx.jpg", image.url)
    }

    @Test
    fun `相对路径的图片补全成绝对地址`() {
        // 没有 scheme 的路径原样交给图片加载器下载不到东西
        val ast = parse("[img]d6/c4/pic.jpg[/img]")
        assertEquals("https://lain.bgm.tv/pic/photo/l/d6/c4/pic.jpg", (ast.nodes.single() as BBCodeNode.Image).url)
    }

    @Test
    fun `协议相对的图片补 https`() {
        val ast = parse("[img]//lain.bgm.tv/pic/photo/l/a/b.jpg[/img]")
        assertEquals("https://lain.bgm.tv/pic/photo/l/a/b.jpg", (ast.nodes.single() as BBCodeNode.Image).url)
    }

    @Test
    fun `photo 标签映射成图片`() {
        val ast = parse("[photo=171308]d6/c4/xxx.jpg[/photo]")
        assertEquals("https://lain.bgm.tv/pic/photo/l/d6/c4/xxx.jpg", (ast.nodes.single() as BBCodeNode.Image).url)
    }

    // ---- 表情 ----

    @Test
    fun `bgm 表情被识别并映射到真实地址`() {
        val ast = parse("来一个(bgm38)")
        val smiley = ast.nodes.filterIsInstance<BBCodeNode.Smiley>().single()
        assertEquals("bgm38", smiley.code)
        // code 到文件的映射是 code-23：bgm38 -> tv/15.gif
        assertEquals("https://lain.bgm.tv/img/smiles/tv/15.gif", SmileyCatalog.remoteUrl(smiley.code))
    }

    @Test
    fun `普通括号内容不被误判成表情`() {
        val ast = parse("这是(普通的括号)")
        assertEquals("这是(普通的括号)", ast.plainText)
        assertTrue(ast.nodes.none { it is BBCodeNode.Smiley })
    }

    @Test
    fun `正文中以内联图片形式出现的表情也能识别`() {
        val ast = parse("[img]/img/smiles/tv/15.gif[/img]")
        // 反向识别同样是 code-23 的逆运算：tv/15.gif -> bgm38
        assertEquals("bgm38", (ast.nodes.single() as BBCodeNode.Smiley).code)
    }

    // ---- 剧透 ----

    @Test
    fun `摘要文本用占位块替代剧透内容`() {
        val ast = parse("前面[mask]凶手是A[/mask]后面")
        // 普通 plainText 含原文（正文渲染用）
        assertEquals("前面凶手是A后面", ast.plainText)
        // 摘要绝不泄漏
        assertEquals("前面${SPOILER_PLACEHOLDER}后面", excerptOf("前面[mask]凶手是A[/mask]后面"))
    }

    @Test
    fun `摘要去掉标签、图片与表情，丢掉截断留下的半个标签`() {
        assertEquals(
            "【示例日志",
            excerptOf("[b]【示例日志\r\n[url=https://example.invalid/video/truncated?source=example"),
        )
        assertEquals("看 这里 结束", excerptOf("看 [url=https://bgm.tv]这里[/url](bgm38)[img]https://a/b.jpg[/img] 结束"))
        assertEquals("第 1 集（写于 7 月 4 日） 示例回复。", excerptOf("[center]第 1 集（写于 7 月 4 日）[/center]\r\n示例回复。"))
        // 不是标签的方括号照常保留
        assertEquals("[动画] 好看", excerptOf("[动画] 好看"))
        assertEquals("写到一半", excerptOf("写到一半 ["))
    }

    // ---- 纯文本 ----

    @Test
    fun `纯文本原样通过`() {
        val source = "开发测试用账号"
        assertEquals(source, parse(source).plainText)
    }
}
