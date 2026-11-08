package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 资料表值的显示文本只有一条规则（条目与角色 / 人物、v0 与 p1 共用）。 */
class SubjectInfoboxTextTest {

    @Test
    fun `子键加值写成子键冒号值，首尾空白去掉`() {
        assertEquals("中文：小林", infoboxValueText(" 中文 ", " 小林 "))
    }

    @Test
    fun `只有值就是值`() {
        assertEquals("小林", infoboxValueText(null, "小林"))
        assertEquals("小林", infoboxValueText("  ", "小林"))
    }

    @Test
    fun `值为空的整项丢掉，不留光杆子键`() {
        assertNull(infoboxValueText("中文", "  "))
        assertNull(infoboxValueText(null, ""))
    }
}
