package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 表情该走哪条取图路径。
 *
 * ## 三条路径
 *
 * 1. **内置**（12 个 reaction）→ `drawable`，断网可用
 * 2. **按需**（其余六家族）→ `SmileyAssetStore` 下载后本地文件
 * 3. **不可解析**（越界 code）→ 退回原文
 *
 * ## 为什么这个判定要单独可测
 *
 * 它决定用户看到什么。判错的表现是静默的：该出图的显示成文本、
 * 或该显示的变成空白。三处渲染点（时间线正文、BBCode 正文、
 * reaction 行）共用这一份判定，故必须只有一处真源。
 */
class SmileySourceTest {

    /** 12 个内置 reaction 走内置路径，且带上资源 ID。 */
    @Test
    fun `内置code走drawable路径`() {
        BundledSmileyResources.map.keys.forEach { code ->
            val source = smileySourceOf(code)
            assertEquals(SmileySource.Bundled(code), source, "$code 应走内置")
        }
    }

    /**
     * 非内置但合法的 code 走**按需路径**，并带上 Catalog 给的相对路径。
     *
     * 相对路径由 Catalog 提供，本层不自己拼——绝对 URL 只在一处拼接。
     */
    @Test
    fun `非内置的合法code走按需路径`() {
        assertEquals(SmileySource.OnDemand("bgm01", "bgm/01.png"), smileySourceOf("bgm01"))
        assertEquals(SmileySource.OnDemand("musume_01", "musume/musume_01.gif"), smileySourceOf("musume_01"))
        assertEquals(SmileySource.OnDemand("blake_05", "blake/blake_05.gif"), smileySourceOf("blake_05"))
        assertEquals(SmileySource.OnDemand("bgm201", "tv_vs/bgm_201.png"), smileySourceOf("bgm201"))
    }

    /**
     * 隐藏表情（服务端 HIDDEN_REACTIONS，如 53）**能解析**，故走按需路径而不是原文。
     *
     * 它们不在选择器里，但正文里可能出现 —— 我们认识，只是用户点不到。
     */
    @Test
    fun `隐藏表情走按需路径而非原文`() {
        val code = SmileyCatalog.codeOfReaction(53)!!
        val source = smileySourceOf(code)
        assertTrue(source is SmileySource.OnDemand, "$code 应走按需，实际：$source")
    }

    /** 越界 code 返回 null，调用方据此显示原文。 */
    @Test
    fun `越界code没有来源`() {
        assertNull(smileySourceOf("bgm999"))
        assertNull(smileySourceOf("bgm0"))
        assertNull(smileySourceOf("不是表情"))
    }

    /**
     * 按需路径必须**原样来自 Catalog**，本层不另立映射。
     *
     * 只对非内置的 code 断言 —— `bgm38` 是内置的，走 `Bundled`，本就没有
     * 相对路径（不需要下载）。
     */
    @Test
    fun `按需路径与Catalog一致`() {
        listOf("bgm01", "musume_07", "blake_03", "bgm205").forEach { code ->
            val expected = SmileyCatalog.entryOf(code)
            assertNotNull(expected, "$code 应可解析")
            val source = smileySourceOf(code)
            assertEquals(
                SmileySource.OnDemand(code, expected.remotePath),
                source,
                "$code 的按需来源应原样来自 Catalog",
            )
        }
    }
}
