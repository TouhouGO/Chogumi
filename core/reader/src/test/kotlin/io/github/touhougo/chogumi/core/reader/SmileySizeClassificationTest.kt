package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 契约测试：表情必须按**官方页面给的 CSS 类**区分小/大两类渲染，不得一刀切。
 *
 * 真源（bgm.tv 线上 HTML 的 img class + 官方 `bangumi.min.css`）：
 *
 * | 家族 | 原生尺寸 | 官方 CSS 类 |
 * |---|---|---|
 * | CLASSIC `bgm01-23` | 20px | `.smile`（pixelated） |
 * | TV `bgm24-123` | 21px | `.smile` |
 * | TV `bgm124/125` | 42px | `.smile` + `width="21"`（素材大，显示仍是小档） |
 * | TV_VS `bgm200-238` | 21px | `.smile` |
 * | TV_500 `bgm500-529` | 21px | `.smile` |
 * | MUSUME | 240px | `.smile-musume` |
 * | BLAKE | 240px | `.smile-blake` |
 *
 * 官方对小表情设 `image-rendering:pixelated`（保持像素硬边），
 * 对大动态表情设 `image-rendering:auto` 且 `max-width:55px`。
 * 一刀切 20dp 的话，会把 240px 的动态 GIF 压成 20dp，等于抹掉了动态表情。
 */
class SmileySizeClassificationTest {

    @Test
    fun `小表情家族必须归类为小尺寸`() {
        val smallCodes = listOf(
            "bgm01", "bgm23",        // CLASSIC
            "bgm24", "bgm123",       // TV 常规
            "bgm124", "bgm125",      // TV 末尾两个：素材 42px，官方仍按小表情显示
            "bgm200", "bgm238",      // TV_VS
            "bgm500", "bgm529",      // TV_500
        )
        smallCodes.forEach { code ->
            assertEquals(
                SmileyCatalog.Scale.SMALL,
                SmileyCatalog.scaleOf(code),
                "$code 必须归类为小表情（官方 `.smile`，显示 20-21px）",
            )
        }
    }

    @Test
    fun `大动态表情家族必须归类为大尺寸`() {
        val largeCodes = listOf(
            "musume_01", "musume_118",
            "blake_01", "blake_118",
        )
        largeCodes.forEach { code ->
            assertEquals(
                SmileyCatalog.Scale.LARGE,
                SmileyCatalog.scaleOf(code),
                "$code 必须归类为大表情（官方 `.smile-dynamic`）",
            )
        }
    }

    @Test
    fun `不可解析的表情没有尺寸分类`() {
        listOf("bgm999", "musume_999", "notasmiley").forEach { code ->
            assertEquals(null, SmileyCatalog.scaleOf(code), "$code 不可解析，不应有尺寸分类")
        }
    }

    @Test
    fun `大小两档尺寸必须有可区分的实际差值`() {
        assertTrue(SmileySizes.emOf("musume_15").value > SmileySizes.emOf("bgm38").value * 2, "行内大表情必须显著大于小表情")
    }

    @Test
    fun `尺寸查询按 code 返回对应档位`() {
        assertEquals(1.5f, SmileySizes.emOf("bgm38").value, "小表情行内 1.5em：正文字号下约为原生 20-21px")
        assertEquals(3.4f, SmileySizes.emOf("musume_15").value)
        assertEquals(1.5f, SmileySizes.emOf("bgm999").value, "不可解析按小尺寸兜底")
    }
}
