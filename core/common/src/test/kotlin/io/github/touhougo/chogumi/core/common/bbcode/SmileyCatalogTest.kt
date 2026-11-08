package io.github.touhougo.chogumi.core.common.bbcode

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SmileyCatalog] 的映射表。
 *
 * ## 这些数字是实测的，不是推断的
 *
 * 每条的期望值都来自对 `https://lain.bgm.tv/img/smiles/` 的逐号 HTTP 探测，
 * 以及 `https://bgm.tv/group/topic/1` 真实 HTML 里 `alt="(bgmNN)"` 与
 * `src` 的对应。
 *
 * 数字钉死在测试里，是因为公式很容易推错：把 tv 家族的文件数上限当成 code 上限，
 * 产出的 URL 就整体错位，而按公式写的断言照样能过。
 */
class SmileyCatalogTest {

    @Test
    fun `BBCode 解析器不自己认表情前缀_经 detectBare 判`() {
        val parser = RepoFiles.read("core/common/src/main/kotlin/io/github/touhougo/chogumi/core/common/bbcode/BBCodeParser.kt")
        for (prefix in listOf("\"bgm", "\"musume", "\"blake")) assertFalse(prefix in parser, "解析器里出现了表情前缀 $prefix")
        assertTrue("SmileyCatalog.detectBare(" in parser)
    }

    // ---- 正向：code -> 路径 ----

    @Test
    fun `经典家族改用bgm目录且code与文件名同号`() {
        assertEquals("bgm/01.png", SmileyCatalog.entryOf("bgm01")?.remotePath)
        assertEquals("bgm/20.png", SmileyCatalog.entryOf("bgm20")?.remotePath)
    }

    /**
     * 经典家族的扩展名有两个例外：`11` 与 `23` 是 gif。
     *
     * 实测：`bgm/11.gif` 200、同号 `bgm/11.png` 404。
     */
    @Test
    fun `经典家族11与23是gif而其余是png`() {
        assertEquals("bgm/11.gif", SmileyCatalog.entryOf("bgm11")?.remotePath)
        assertEquals("bgm/23.gif", SmileyCatalog.entryOf("bgm23")?.remotePath)
        // 邻居作对照，确认不是整段都变
        assertEquals("bgm/10.png", SmileyCatalog.entryOf("bgm10")?.remotePath)
        assertEquals("bgm/22.png", SmileyCatalog.entryOf("bgm22")?.remotePath)
    }

    /**
     * 电视家族的核心公式：**code 减 23 才是文件名**。
     *
     * 最容易错的地方 —— 拿 code 当文件名直接拼的话，
     * `bgm38` 会产出 `tv/38.gif`（实际是 `tv/15.gif`）。
     */
    @Test
    fun `电视家族文件名是code减23`() {
        assertEquals("tv/01.gif", SmileyCatalog.entryOf("bgm24")?.remotePath)
        assertEquals("tv/15.gif", SmileyCatalog.entryOf("bgm38")?.remotePath)
        assertEquals("tv/57.gif", SmileyCatalog.entryOf("bgm80")?.remotePath)
        assertEquals("tv/82.gif", SmileyCatalog.entryOf("bgm105")?.remotePath)
        assertEquals("tv/102.gif", SmileyCatalog.entryOf("bgm125")?.remotePath)
    }

    @Test
    fun `tv_vs家族全是png`() {
        assertEquals("tv_vs/bgm_200.png", SmileyCatalog.entryOf("bgm200")?.remotePath)
        assertEquals("tv_vs/bgm_238.png", SmileyCatalog.entryOf("bgm238")?.remotePath)
    }

    /**
     * tv_500 家族的扩展名**不统一**，必须查表。
     *
     * 实测 11 个 gif（500/501/505/515-519/521-523）+ 19 个 png，扩展名猜错会 404。
     */
    @Test
    fun `tv_500家族的扩展名逐个查表`() {
        // gif 组
        listOf(500, 501, 505, 515, 516, 517, 518, 519, 521, 522, 523).forEach { id ->
            assertEquals("tv_500/bgm_$id.gif", SmileyCatalog.entryOf("bgm$id")?.remotePath, "bgm$id 应为 gif")
        }
        // png 组
        listOf(502, 503, 504, 506, 520, 524, 529).forEach { id ->
            assertEquals("tv_500/bgm_$id.png", SmileyCatalog.entryOf("bgm$id")?.remotePath, "bgm$id 应为 png")
        }
    }

    @Test
    fun `musume与blake家族的路径带前缀名`() {
        assertEquals("musume/musume_01.gif", SmileyCatalog.entryOf("musume_01")?.remotePath)
        assertEquals("blake/blake_01.gif", SmileyCatalog.entryOf("blake_01")?.remotePath)
    }

    // ---- 边界 ----

    /**
     * 两家族的分界：`bgm23` 属经典、`bgm24` 属电视。
     *
     * 相邻两个 code 落在**不同目录、不同扩展名规则**下，是最容易写错的一处。
     */
    @Test
    fun `经典与电视家族的分界在23与24之间`() {
        assertEquals(SmileyCatalog.Family.CLASSIC, SmileyCatalog.entryOf("bgm23")?.family)
        assertEquals(SmileyCatalog.Family.TV, SmileyCatalog.entryOf("bgm24")?.family)
        assertEquals("bgm/23.gif", SmileyCatalog.entryOf("bgm23")?.remotePath)
        assertEquals("tv/01.gif", SmileyCatalog.entryOf("bgm24")?.remotePath)
    }

    @Test
    fun `各家族的端点`() {
        assertNotNull(SmileyCatalog.entryOf("bgm01"), "经典下界")
        assertNotNull(SmileyCatalog.entryOf("bgm125"), "电视上界")
        assertNotNull(SmileyCatalog.entryOf("bgm200"), "tv_vs 下界")
        assertNotNull(SmileyCatalog.entryOf("bgm238"), "tv_vs 上界")
        assertNotNull(SmileyCatalog.entryOf("bgm500"), "tv_500 下界")
        assertNotNull(SmileyCatalog.entryOf("bgm529"), "tv_500 上界")
        assertNotNull(SmileyCatalog.entryOf("musume_118"), "musume 上界")
        assertNotNull(SmileyCatalog.entryOf("blake_118"), "blake 上界")
    }

    /**
     * 越界与不存在的 code 一律解析为 null。
     *
     * `bgm0` 与 `bgm126` 在**任何**家族范围之外；`bgm103` 则是反例——
     * 它在电视家族内（容易误判成越界），见下一条。
     */
    @Test
    fun `范围外的code解析为null`() {
        assertNull(SmileyCatalog.entryOf("bgm0"))
        assertNull(SmileyCatalog.entryOf("bgm126"))
        assertNull(SmileyCatalog.entryOf("bgm199"))
        assertNull(SmileyCatalog.entryOf("bgm239"))
        assertNull(SmileyCatalog.entryOf("bgm499"))
        assertNull(SmileyCatalog.entryOf("bgm530"))
        assertNull(SmileyCatalog.entryOf("musume_119"))
        assertNull(SmileyCatalog.entryOf("blake_119"))
    }

    /**
     * blake 家族**无缺号**，97 与 98 都存在。
     *
     * 与 musume 相邻但规则不同 —— musume 缺 97/98，blake 不缺。
     * 这条是"两个家族不能共用同一套范围判断"的防线。
     */
    @Test
    fun `blake无缺号97与98都存在`() {
        assertNotNull(SmileyCatalog.entryOf("blake_97"))
        assertNotNull(SmileyCatalog.entryOf("blake_98"))
        assertEquals("blake/blake_97.gif", SmileyCatalog.entryOf("blake_97")?.remotePath)
    }

    /**
     * `bgm103` **不是**越界 —— 这是最典型的误判。
     *
     * 误判的来源是把 tv 家族的**文件数上限 102** 当成了 **code 上限**，
     * 于是认为 `bgm103` 越界。实际 code 上限是 125，`bgm103` -> `tv/80.gif`。
     */
    @Test
    fun `bgm103在范围内而不是越界`() {
        assertNotNull(SmileyCatalog.entryOf("bgm103"), "102 是文件数上限，不是 code 上限")
        assertEquals("tv/80.gif", SmileyCatalog.entryOf("bgm103")?.remotePath)
    }

    /**
     * musume 家族缺 97、98（实测），这两个必须解析为 null。
     *
     * 这是"本地素材存在 ≠ 可解析"的反面：范围不是连续区间，不能只用上下界判断。
     */
    @Test
    fun `musume缺97与98`() {
        assertNull(SmileyCatalog.entryOf("musume_97"), "实测 musume_97 不存在")
        assertNull(SmileyCatalog.entryOf("musume_98"), "实测 musume_98 不存在")
        assertNotNull(SmileyCatalog.entryOf("musume_96"))
        assertNotNull(SmileyCatalog.entryOf("musume_99"))
    }

    // ---- 绝对 URL ----

    @Test
    fun `remoteUrl拼接出完整地址`() {
        assertEquals("https://lain.bgm.tv/img/smiles/tv/15.gif", SmileyCatalog.remoteUrl("bgm38"))
        assertEquals("https://lain.bgm.tv/img/smiles/bgm/01.png", SmileyCatalog.remoteUrl("bgm01"))
    }

    @Test
    fun `不可解析的code没有remoteUrl`() {
        assertNull(SmileyCatalog.remoteUrl("bgm0"))
        assertNull(SmileyCatalog.remoteUrl("不是表情"))
    }

    // ---- 反向：URL -> code ----

    /**
     * 反向识别是正向的逆运算，六家族都要支持。
     *
     * 正文里表情可能以 `[img]/img/smiles/tv/15.gif[/img]` 出现，
     * 必须与 `(bgm38)` 归一到同一个 code。
     */
    @Test
    fun `反向识别六家族`() {
        assertEquals("bgm38", SmileyCatalog.codeFromImageUrl("/img/smiles/tv/15.gif"))
        assertEquals("bgm01", SmileyCatalog.codeFromImageUrl("/img/smiles/bgm/01.png"))
        assertEquals("bgm11", SmileyCatalog.codeFromImageUrl("/img/smiles/bgm/11.gif"))
        assertEquals("bgm201", SmileyCatalog.codeFromImageUrl("/img/smiles/tv_vs/bgm_201.png"))
        assertEquals("bgm501", SmileyCatalog.codeFromImageUrl("/img/smiles/tv_500/bgm_501.gif"))
        assertEquals("musume_01", SmileyCatalog.codeFromImageUrl("/img/smiles/musume/musume_01.gif"))
        assertEquals("blake_01", SmileyCatalog.codeFromImageUrl("/img/smiles/blake/blake_01.gif"))
    }

    @Test
    fun `反向识别接受绝对URL与带查询串`() {
        assertEquals("bgm38", SmileyCatalog.codeFromImageUrl("https://lain.bgm.tv/img/smiles/tv/15.gif"))
        assertEquals("bgm38", SmileyCatalog.codeFromImageUrl("https://lain.bgm.tv/img/smiles/tv/15.gif?v=2"))
    }

    @Test
    fun `反向识别对非表情URL返回null`() {
        assertNull(SmileyCatalog.codeFromImageUrl("https://lain.bgm.tv/pic/photo/l/d6/c4/x.jpg"))
        assertNull(SmileyCatalog.codeFromImageUrl("/img/smiles/tv/999.gif"), "999+23 超出 code 上界")
        assertNull(SmileyCatalog.codeFromImageUrl(""))
    }

    /**
     * 正反向往返一致：对一个 code 取其 URL，再反推回同一 code。
     *
     * 这条覆盖六家族，是"正向与反向不会各自漂移"的整体防线。
     */
    @Test
    fun `正反向往返一致`() {
        listOf(
            "bgm01", "bgm11", "bgm23", "bgm24", "bgm38", "bgm103", "bgm125",
            "bgm200", "bgm238", "bgm500", "bgm501", "bgm529",
            "musume_01", "musume_118", "blake_01", "blake_118",
        ).forEach { code ->
            val url = SmileyCatalog.remoteUrl(code)
            assertNotNull(url, "$code 应有 remoteUrl")
            assertEquals(code, SmileyCatalog.codeFromImageUrl(url), "$code 往返后应一致：$url")
        }
    }

    // ---- reaction ----

    /**
     * 12 个可选 reaction 逐条：value -> code -> 路径。
     *
     * 全部经线上实测验证：每个 code 减 23 后都落在 `tv/01..102` 内且文件存在。
     */
    @Test
    fun `12个reaction按服务端顺序逐条映射到电视家族`() {
        val expected = mapOf(
            0 to "bgm67", 79 to "bgm63", 54 to "bgm38", 140 to "bgm124",
            62 to "bgm46", 122 to "bgm106", 104 to "bgm88", 80 to "bgm64",
            141 to "bgm125", 88 to "bgm72", 85 to "bgm69", 90 to "bgm74",
        )
        assertEquals(expected.keys.toList(), COMMON_REACTIONS, "可选集就是这 12 个，顺序照服务端（选择器按它排）")
        expected.forEach { (value, code) ->
            assertEquals(code, SmileyCatalog.codeOfReaction(value), "value=$value 应映射到 $code")
            val entry = SmileyCatalog.entryOf(code)
            assertNotNull(entry, "$code 应可解析")
            assertEquals(SmileyCatalog.Family.TV, entry.family, "$code 应属电视家族")
            assertNotNull(SmileyCatalog.remoteUrl(code), "$code 应有地址")
        }
    }

    /**
     * 隐藏表情：能解析，但**不在**可选择集里。
     *
     * 服务端 `HIDDEN_REACTIONS` 定义的 10 个。它们不会出现在点赞选择器里，
     * 但正文里可能出现，故解析必须支持。
     */
    @Test
    fun `隐藏表情可解析但不在可选集`() {
        HIDDEN_REACTIONS.forEach { value ->
            val code = SmileyCatalog.codeOfReaction(value)
            assertNotNull(code, "隐藏 value=$value 应能映射到 code")
            assertNotNull(SmileyCatalog.remoteUrl(code), "$code 应有地址")
            assertTrue(value !in COMMON_REACTIONS, "隐藏表情不许出现在可选集：$value")
        }
    }

    @Test
    fun `未知reaction值返回null`() {
        assertNull(SmileyCatalog.codeOfReaction(1), "1 不是合法值")
        assertNull(SmileyCatalog.codeOfReaction(999))
    }

    // ---- 识别 ----

    /** 只认三种前缀：`bgm`、`musume_`、`blake_`。 */
    @Test
    fun `识别只认三种前缀且大小写不敏感`() {
        assertEquals("bgm38", SmileyCatalog.detect("(bgm38)"))
        assertEquals("bgm38", SmileyCatalog.detect("bgm38"))
        assertEquals("bgm38", SmileyCatalog.detect("(BGM38)"))
        assertEquals("musume_01", SmileyCatalog.detect("(musume_01)"))
        assertEquals("blake_01", SmileyCatalog.detect("(blake_01)"))
    }

    @Test
    fun `不是表情的输入返回null`() {
        assertNull(SmileyCatalog.detect("普通的括号"))
        assertNull(SmileyCatalog.detect("bgm"), "前缀后必须有数字")
        assertNull(SmileyCatalog.detect("tv_500"), "tv_500 不是前缀，只存在于 URL 反向识别")
        assertNull(SmileyCatalog.detect(""))
    }

    /**
     * 识别只管**语法**，不管数值是否落在真实范围内。
     *
     * `(bgm999)` 语法合法故被识别成 code，但解析不出地址 ——
     * 界面据此显示原文，而不是崩溃或显示空白。
     */
    @Test
    fun `语法合法但数值越界时能识别却解析不出地址`() {
        assertEquals("bgm999", SmileyCatalog.detect("(bgm999)"))
        assertNull(SmileyCatalog.entryOf("bgm999"))
        assertNull(SmileyCatalog.remoteUrl("bgm999"))
    }

    @Test
    fun `表情面板逐家族列出的每个code都解析得出地址且归回本家族`() {
        val sizes = SmileyCatalog.Family.entries.associateWith { SmileyCatalog.codesOf(it).size }
        assertEquals(
            mapOf(
                SmileyCatalog.Family.TV to 102,
                SmileyCatalog.Family.TV_VS to 39,
                SmileyCatalog.Family.TV_500 to 30,
                SmileyCatalog.Family.CLASSIC to 23,
                SmileyCatalog.Family.MUSUME to 116,
                SmileyCatalog.Family.BLAKE to 118,
                SmileyCatalog.Family.KAOMOJI to 16,
            ),
            sizes,
        )
        SmileyCatalog.Family.entries.forEach { family ->
            SmileyCatalog.codesOf(family).forEach { code -> assertEquals(family, SmileyCatalog.entryOf(code)?.family, code) }
        }
        assertEquals("bgm01", SmileyCatalog.codesOf(SmileyCatalog.Family.CLASSIC).first())
        SmileyCatalog.Family.entries.forEach { family ->
            val codes = SmileyCatalog.codesOf(family)
            assertEquals(codes.size, codes.toSet().size, "$family 有重复的 code")
        }
    }

    @Test
    fun `家族按 next 站表情面板的页签顺序排`() {
        assertEquals(
            listOf(
                SmileyCatalog.Family.TV,
                SmileyCatalog.Family.TV_VS,
                SmileyCatalog.Family.TV_500,
                SmileyCatalog.Family.CLASSIC,
                SmileyCatalog.Family.MUSUME,
                SmileyCatalog.Family.BLAKE,
                SmileyCatalog.Family.KAOMOJI,
            ),
            SmileyCatalog.Family.entries,
        )
    }

    @Test
    fun `musume 与 blake 按含义排_blake 多出的两个在垃圾桶之后钱之前`() {
        val musume = SmileyCatalog.codesOf(SmileyCatalog.Family.MUSUME)
        val blake = SmileyCatalog.codesOf(SmileyCatalog.Family.BLAKE)
        assertEquals(listOf("musume_06", "musume_07", "musume_08"), musume.take(3))
        assertEquals(listOf("musume_01", "musume_02", "musume_03", "musume_04", "musume_05"), musume.takeLast(5))
        // 情绪反应接着 100、106、108、118，再进动作道具
        assertEquals(listOf("musume_42", "musume_100", "musume_106", "musume_108", "musume_118", "musume_43"), musume.subList(36, 42))
        val trash = blake.indexOf("blake_117")
        assertEquals(listOf("blake_117", "blake_97", "blake_98", "blake_77"), blake.subList(trash, trash + 4))
        assertEquals(blake.filterNot { it == "blake_97" || it == "blake_98" }.map { it.removePrefix("blake_") }, musume.map { it.removePrefix("musume_") })
        assertEquals((1..118).toSet(), blake.map { it.removePrefix("blake_").toInt() }.toSet())
    }

    @Test
    fun `musume 与 blake 同号同名_其余家族没有名字`() {
        assertEquals("叹号", SmileyCatalog.nameOf("musume_06"))
        assertEquals("叹号", SmileyCatalog.nameOf("blake_06"))
        assertEquals("Bits", SmileyCatalog.nameOf("blake_01"))
        assertEquals("得分(0分)", SmileyCatalog.nameOf("blake_97"))
        assertEquals("主意", SmileyCatalog.nameOf("musume_118"))
        assertNull(SmileyCatalog.nameOf("musume_97"))
        assertNull(SmileyCatalog.nameOf("bgm38"))
        assertNull(SmileyCatalog.nameOf("=w="))
    }

    @Test
    fun `颜文字按原文精确识别_图在 smiles 根目录`() {
        // 实测：bgm.tv/group/topic/87 回复 443850 的原文 (=w=)，网页画成 smiles/2.gif
        assertEquals("=w=", SmileyCatalog.detect("(=w=)"))
        assertEquals("https://lain.bgm.tv/img/smiles/2.gif", SmileyCatalog.remoteUrl("=w="))
        // 区分大小写：=W= 是另一个（7）
        assertEquals("https://lain.bgm.tv/img/smiles/7.gif", SmileyCatalog.remoteUrl("=W="))
        assertEquals("https://lain.bgm.tv/img/smiles/12.gif", SmileyCatalog.remoteUrl("= ='"))
        assertEquals("https://lain.bgm.tv/img/smiles/16.gif", SmileyCatalog.remoteUrl("LOL"))
        assertNull(SmileyCatalog.detect("(lol)"))
        assertNull(SmileyCatalog.detect("(=x=)"))
        assertEquals(SmileyCatalog.Family.KAOMOJI, SmileyCatalog.entryOf(":P")?.family)
        assertEquals(SmileyCatalog.Scale.SMALL, SmileyCatalog.scaleOf("TAT"))
    }

    @Test
    fun `颜文字的图片地址反推回原文`() {
        assertEquals("=w=", SmileyCatalog.codeFromImageUrl("/img/smiles/2.gif"))
        assertEquals("LOL", SmileyCatalog.codeFromImageUrl("https://lain.bgm.tv/img/smiles/16.gif"))
        assertNull(SmileyCatalog.codeFromImageUrl("/img/smiles/17.gif"))
        assertNull(SmileyCatalog.codeFromImageUrl("/img/smiles/2.png"))
        SmileyCatalog.codesOf(SmileyCatalog.Family.KAOMOJI).forEach { code ->
            assertEquals(code, SmileyCatalog.codeFromImageUrl(SmileyCatalog.remoteUrl(code)!!))
        }
    }

    @Test
    fun `颜文字按素材宽占位_其余是方格`() {
        assertEquals(33f / 21f, SmileyCatalog.widthRatioOf("TAT"))
        assertEquals(1f, SmileyCatalog.widthRatioOf("=.,="))
        assertEquals(1f, SmileyCatalog.widthRatioOf("bgm38"))
        assertEquals(1f, SmileyCatalog.widthRatioOf("musume_06"))
    }

    @Test
    fun `正文里的颜文字解析成表情节点`() {
        // 实测：bgm.tv/group/topic/268 回复 73113 的原文
        val nodes = BBCodeParser.parse("....(S_S)").nodes
        assertEquals(listOf("S_S"), nodes.filterIsInstance<BBCodeNode.Smiley>().map { it.code })
        assertEquals("....", nodes.first().plainText())
        assertTrue(BBCodeParser.parse("(笑)(T_T)").nodes.any { it is BBCodeNode.Smiley && it.code == "T_T" })
    }

    @Test
    fun `表情写法反查贴贴与正查是同一张表`() {
        (COMMON_REACTIONS + HIDDEN_REACTIONS).forEach { value ->
            val code = assertNotNull(SmileyCatalog.codeOfReaction(value))
            assertEquals(value, SmileyCatalog.reactionOfCode(code))
        }
        assertEquals(54, SmileyCatalog.reactionOfCode("(bgm38)"), "带括号也认")
        assertEquals(140, SmileyCatalog.reactionOfCode("BGM124"), "大小写不论")
        assertNull(SmileyCatalog.reactionOfCode("bgm1"), "不是贴贴用的表情")
        assertNull(SmileyCatalog.reactionOfCode("musume_06"))
    }
}

/** 服务端 `lib/like.ts` 的 `HIDDEN_REACTIONS`：不在可选集里，但正文与贴贴里会出现。 */
internal val HIDDEN_REACTIONS = listOf(53, 92, 118, 60, 128, 47, 68, 137, 76, 132)
