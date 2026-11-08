package io.github.touhougo.chogumi.core.common.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals

/** 短信正文 HTML → BBCode：覆盖服务端输出的换行、未闭合标签与单个写法。 */
class BBCodeFromHtmlTest {

    @Test
    fun `粗体 斜体 下划线 删除线`() {
        assertEquals("[b]粗体[/b]", bbcodeOfHtml("""<span style="font-weight:bold;">粗体</span>"""))
        assertEquals("[i]斜体[/i]", bbcodeOfHtml("""<span style="font-style:italic">斜体</span>"""))
        assertEquals("[u]下划线[/u]", bbcodeOfHtml("""<span style="text-decoration: underline;">下划线</span>"""))
        assertEquals("[s]删除线[/s]", bbcodeOfHtml("""<span style="text-decoration: line-through;">删除线</span>"""))
    }

    @Test
    fun `遮罩外层的配色不当成 color`() {
        val html = """<span class="text_mask" style="background-color:#555;color:#555;border:1px solid #555;"><span class="inner">遮罩</span></span>"""
        assertEquals("[mask]遮罩[/mask]", bbcodeOfHtml(html))
    }

    @Test
    fun `字号只取 font-size`() {
        assertEquals("[size=25]大字[/size]", bbcodeOfHtml("""<span style="font-size:25px; line-height:25px;">大字</span>"""))
        assertEquals("[size=10]小字[/size]", bbcodeOfHtml("""<span style="font-size:10px; line-height:10px;">小字</span>"""))
    }

    @Test
    fun `颜色`() {
        assertEquals("[color=red]红字[/color]", bbcodeOfHtml("""<span style="color: red;">红字</span>"""))
    }

    @Test
    fun `链接文字就是地址时写成 url 标签里的地址 否则地址写在值里`() {
        val href = "https://bgm.tv/subject/265"
        val attrs = "target=\"_blank\" rel=\"nofollow external noopener noreferrer\" class=\"l\""
        assertEquals("[url=$href]带文字的链接[/url]", bbcodeOfHtml("""<a href="$href" $attrs>带文字的链接</a>"""))
        assertEquals("[url]$href[/url]", bbcodeOfHtml("""<a href="$href" $attrs>$href</a>"""))
    }

    @Test
    fun `短信里的 img 旧站输出成链接 还原成链接`() {
        val src = "https://lain.bgm.tv/img/smiles/tv/15.gif"
        assertEquals("[url]$src[/url]", bbcodeOfHtml("""<a href="$src" target="_blank" class="l">$src</a>"""))
    }

    @Test
    fun `站内相对链接按 bgm_tv 补全`() {
        assertEquals("[url=https://bgm.tv/user/fixture_user]fixture_user[/url]", bbcodeOfHtml("""<a href="/user/fixture_user" class="l">fixture_user</a>"""))
    }

    @Test
    fun `裸网址原样是文字`() {
        assertEquals("https://bgm.tv/subject/265", bbcodeOfHtml("https://bgm.tv/subject/265"))
    }

    @Test
    fun `引用`() {
        assertEquals("[quote]引用一段[/quote]", bbcodeOfHtml("""<div class="quote"><q>引用一段</q></div>"""))
    }

    @Test
    fun `代码块逐字 nbsp 还原成空格`() {
        assertEquals("[code]code  两个空格[/code]", bbcodeOfHtml("""<div class="codeHighlight"><pre>code&nbsp;&nbsp;两个空格</pre></div>"""))
    }

    @Test
    fun `代码块里的方括号不转义`() {
        assertEquals("[code][b]x[/b][/code]", bbcodeOfHtml("""<div class="codeHighlight"><pre>[b]x[/b]</pre></div>"""))
    }

    @Test
    fun `代码块里的换行`() {
        assertEquals("[code]a\nb[/code]", bbcodeOfHtml("<div class=\"codeHighlight\"><pre>a<br />\nb</pre></div>"))
    }

    @Test
    fun `居中与靠右`() {
        assertEquals("[center]居中[/center] [right]靠右[/right]", bbcodeOfHtml("""<p style="text-align: center;">居中</p> <p style="text-align: right;">靠右</p>"""))
    }

    @Test
    fun `表情取 alt`() {
        val html = """表情 <img src="/img/smiles/tv/15.gif" smileid="54" class="smile" alt="(bgm38)" /> <img src="/img/smiles/tv/01.gif" smileid="40" class="smile" alt="(bgm24)" />"""
        assertEquals("表情 (bgm38) (bgm24)", bbcodeOfHtml(html))
    }

    @Test
    fun `br 是换行 后面跟的排版换行符不算`() {
        assertEquals("一\n二", bbcodeOfHtml("一<br />\n二"))
        assertEquals("一\n二", bbcodeOfHtml("一<br>\n二"))
        assertEquals("一\n\n二", bbcodeOfHtml("一<br />\n<br />\n二"))
    }

    @Test
    fun `实体解码`() {
        assertEquals("< > &", bbcodeOfHtml("&lt; &gt; &amp;"))
    }

    @Test
    fun `不认识的元素只留里面的文字`() {
        assertEquals("文字", bbcodeOfHtml("""<font face="x"><span class="unknown">文字</span></font>"""))
    }

    @Test
    fun `字面的标签写法不被认成标签`() {
        val text = "字面的 [b]x[/b] [/i] (bgm38) &nbsp; [url=https://a.com]y[/url] [foo]成对的未知名[/foo]"
        val html = text.replace("&", "&amp;")
        val nodes = BBCodeParser.parse(bbcodeOfHtml(html)).nodes
        assertEquals(1, nodes.size, "应只有一段文字：$nodes")
        assertEquals(text, (nodes.single() as BBCodeNode.Text).value.replace(WORD_JOINER, ""))
    }

    @Test
    fun `本来就按字面显示的括号原样不动`() {
        assertEquals("[动画] 普通文字 (笑)", bbcodeOfHtml("[动画] 普通文字 (笑)"))
    }

    @Test
    fun `没闭合的 span 补上闭合 还原后与原文显示相同`() {
        val restored = bbcodeOfHtml("""字面的 <span style="font-weight:bold;"> 和 &lt; &gt; &amp; 符号<br />""" + "\n第二行")
        assertEquals("字面的 [b] 和 < > & 符号\n第二行[/b]", restored)
        assertEquals(BBCodeParser.parse("字面的 [b] 和 < > & 符号\n第二行"), BBCodeParser.parse(restored))
    }

    @Test
    fun `整封测试信`() {
        assertEquals(RESTORED, bbcodeOfHtml(PEER_BODY))
    }

    @Test
    fun `整封测试信自己那边的网页 HTML 还原结果相同`() {
        assertEquals(RESTORED, bbcodeOfHtml(SELF_BODY))
    }

    @Test
    fun `整封测试信还原后与发信原文显示相同`() {
        // 网页上 [img] 是链接；[quote] 前后的换行服务端吃掉了（引用本身独占一段）
        val asShownOnWeb = ORIGINAL
            .replace("[img]https://lain.bgm.tv/img/smiles/tv/15.gif[/img]", "[url]https://lain.bgm.tv/img/smiles/tv/15.gif[/url]")
            .replace("[/url]\r\n[quote]", "[/url][quote]")
            .replace("[/quote]\r\n[code]", "[/quote][code]")
        assertEquals(BBCodeParser.parse(asShownOnWeb), BBCodeParser.parse(bbcodeOfHtml(PEER_BODY)))
    }

    private companion object {
        const val WORD_JOINER = "\u2060"

        const val ORIGINAL = "[b]粗体[/b] [i]斜体[/i] [u]下划线[/u] [s]删除线[/s] [mask]遮罩[/mask]\r\n" +
            "[size=25]大字[/size] [size=10]小字[/size] [color=red]红字[/color]\r\n" +
            "[url=https://bgm.tv/subject/265]带文字的链接[/url] [url]https://bgm.tv/subject/265[/url] https://bgm.tv/subject/265\r\n" +
            "[img]https://lain.bgm.tv/img/smiles/tv/15.gif[/img]\r\n" +
            "[quote]引用一段[/quote]\r\n" +
            "[code]code  两个空格[/code]\r\n" +
            "[center]居中[/center] [right]靠右[/right]\r\n" +
            "表情 (bgm38) (bgm24)\r\n" +
            "字面的 [b] 和 < > & 符号\r\n" +
            "第二行"

        const val RESTORED = "[b]粗体[/b] [i]斜体[/i] [u]下划线[/u] [s]删除线[/s] [mask]遮罩[/mask]\n" +
            "[size=25]大字[/size] [size=10]小字[/size] [color=red]红字[/color]\n" +
            "[url=https://bgm.tv/subject/265]带文字的链接[/url] [url]https://bgm.tv/subject/265[/url] https://bgm.tv/subject/265\n" +
            "[url]https://lain.bgm.tv/img/smiles/tv/15.gif[/url][quote]引用一段[/quote][code]code  两个空格[/code]\n" +
            "[center]居中[/center] [right]靠右[/right]\n" +
            "表情 (bgm38) (bgm24)\n" +
            "字面的 [b] 和 < > & 符号\n" +
            "第二行[/b]"

        const val PEER_BODY = """<span style="font-weight:bold;">粗体</span> <span style="font-style:italic">斜体</span> <span style="text-decoration: underline;">下划线</span> <span style="text-decoration: line-through;">删除线</span> <span class="text_mask" style="background-color:#555;color:#555;border:1px solid #555;"><span class="inner">遮罩</span></span><br />
<span style="font-size:25px; line-height:25px;">大字</span> <span style="font-size:10px; line-height:10px;">小字</span> <span style="color: red;">红字</span><br />
<a href="https://bgm.tv/subject/265" target="_blank" rel="nofollow external noopener noreferrer" class="l">带文字的链接</a> <a href="https://bgm.tv/subject/265" target="_blank" rel="nofollow external noopener noreferrer" class="l">https://bgm.tv/subject/265</a> https://bgm.tv/subject/265<br />
<a href="https://lain.bgm.tv/img/smiles/tv/15.gif" target="_blank" rel="nofollow external noopener noreferrer" class="l">https://lain.bgm.tv/img/smiles/tv/15.gif</a><div class="quote"><q>引用一段</q></div><div class="codeHighlight"><pre>code&nbsp;&nbsp;两个空格</pre></div><br />
<p style="text-align: center;">居中</p> <p style="text-align: right;">靠右</p><br />
表情 <img src="/img/smiles/tv/15.gif" smileid="54" class="smile" alt="(bgm38)" /> <img src="/img/smiles/tv/01.gif" smileid="40" class="smile" alt="(bgm24)" /><br />
字面的 <span style="font-weight:bold;"> 和 &lt; &gt; &amp; 符号<br />
第二行"""

        const val SELF_BODY = """<span style="font-weight:bold;">粗体</span> <span style="font-style:italic">斜体</span> <span style="text-decoration: underline;">下划线</span> <span style="text-decoration: line-through;">删除线</span> <span class="text_mask" style="background-color:#555;color:#555;border:1px solid #555;"><span class="inner">遮罩</span></span><br>
<span style="font-size:25px; line-height:25px;">大字</span> <span style="font-size:10px; line-height:10px;">小字</span> <span style="color: red;">红字</span><br>
<a href="https://bgm.tv/subject/265" target="_blank" rel="nofollow external noopener noreferrer" class="l">带文字的链接</a> <a href="https://bgm.tv/subject/265" target="_blank" rel="nofollow external noopener noreferrer" class="l">https://bgm.tv/subject/265</a> https://bgm.tv/subject/265<br>
<a href="https://lain.bgm.tv/img/smiles/tv/15.gif" target="_blank" rel="nofollow external noopener noreferrer" class="l">https://lain.bgm.tv/img/smiles/tv/15.gif</a><div class="quote"><q>引用一段</q></div><div class="codeHighlight"><pre>code&nbsp;&nbsp;两个空格</pre></div><br>
<p style="text-align: center;">居中</p> <p style="text-align: right;">靠右</p><br>
表情 <img src="/img/smiles/tv/15.gif" smileid="54" class="smile" alt="(bgm38)"> <img src="/img/smiles/tv/01.gif" smileid="40" class="smile" alt="(bgm24)"><br>
字面的 <span style="font-weight:bold;"> 和 &lt; &gt; &amp; 符号<br>
第二行</span>"""
    }
}
