package io.github.touhougo.chogumi.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 时间线描述片段：批量进度、集号格式化、单集标签。
 *
 * `cat/type → 动词 / 版式` 的映射在 [presentationOf]，由 `TimelinePresentationTest` 钉住；
 * 这里只测带参数的片段文案。
 */
class TimelineActionTextTest {

    /** 套上中文资源的模板（与 values/strings.xml 一致），只测拼装逻辑。 */
    private fun describe(epsTotal: String, epsUpdate: Int?, volsTotal: String, volsUpdate: Int?) = progressBatchDescription(
        epsTotal = epsTotal,
        epsUpdate = epsUpdate,
        volsTotal = volsTotal,
        volsUpdate = volsUpdate,
        separator = "、",
        epsTotalFormat = "章节 %s",
        epsUpdateText = { "$it 话" },
        volsTotalFormat = "卷数 %s",
        volsUpdateText = { "$it 卷" },
    )

    /**
     * 批量进度描述优化：
     * - 有进度更新（epsUpdate / volsUpdate）时直接输出精炼动作，严禁冗余拼接章节总数与卷数总数；
     * - 话数与卷数均有时合并为「X 话、Y 卷」；
     * - 仅在完全没有进度更新时才兜底输出总数。
     */
    @Test
    fun `批量进度描述按资源模板拼装`() {
        assertEquals(
            "5 话、2 卷",
            describe(epsTotal = "12", epsUpdate = 5, volsTotal = "3", volsUpdate = 2),
        )
        assertEquals(
            "62 话、7 卷",
            describe(epsTotal = "62", epsUpdate = 62, volsTotal = "7", volsUpdate = 7),
        )
        assertEquals(
            "5 话、1 卷",
            describe(epsTotal = "62", epsUpdate = 5, volsTotal = "7", volsUpdate = 1),
        )
        assertEquals("章节 12", describe(epsTotal = "12", epsUpdate = null, volsTotal = "", volsUpdate = null))
        assertEquals("卷数 3", describe(epsTotal = "", epsUpdate = null, volsTotal = "3", volsUpdate = null))
        assertEquals("章节 12、卷数 3", describe(epsTotal = "12", epsUpdate = null, volsTotal = "3", volsUpdate = null))
        assertEquals("5 话", describe(epsTotal = "12", epsUpdate = 5, volsTotal = "3", volsUpdate = null))
        assertEquals("2 卷", describe(epsTotal = "12", epsUpdate = null, volsTotal = "3", volsUpdate = 2))
        assertEquals("", describe(epsTotal = "", epsUpdate = null, volsTotal = "", volsUpdate = null))
        assertEquals("2 卷", describe(epsTotal = "??", epsUpdate = 0, volsTotal = "??", volsUpdate = 2))
        assertEquals("1 卷", describe(epsTotal = "??", epsUpdate = 0, volsTotal = "??", volsUpdate = 1))
    }

    /**
     * 单集进度描述是"集号 + 集名"：
     * 集号经 formatEpisodeSort 格式化，整数不带 ".0"。
     */
    @Test
    fun `单集进度描述为集号加集名`() {
        assertEquals("1.5 第一集", episodeLabel(sort = 1.5, name = "第一集"))
        assertEquals("12 第十二集", episodeLabel(sort = 12.0, name = "第十二集"))
    }
}
