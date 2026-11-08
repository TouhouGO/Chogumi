package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.appFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 卡片/列表项上公共评分与排名的显隐 —— 纯函数全表断言。
 *
 * 这两条是 `LocalAppFeatures` 的消费点里**唯一能在 JVM 上直接断言**的部分（组件本身要仪器测试），
 * 所以判定被特意抽成纯函数：关闭评分时评分与排名必须消失，开启评分时逐字保持原样。
 */
class SubjectRatingTextTest {

    private val ratingsOn = appFeatures(ExperienceMode.FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = true)
    private val ratingsOff = appFeatures(ExperienceMode.FULL, showPublicRatings = false, showCoverScores = true, showFriendMarks = true)
    private val coverOff = appFeatures(ExperienceMode.FULL, showPublicRatings = true, showCoverScores = false, showFriendMarks = true)

    private val plain: (Double) -> String = { it.toString() }

    @Test
    fun `开启评分时评分按调用方给的文案渲染`() {
        assertEquals("8.2", subjectRatingText(8.2, ratingsOn, plain))
        assertEquals("★ 8.2", subjectCardRatingSubtitle(8.2, ratingsOn))
    }

    @Test
    fun `关闭评分时评分一律不渲染`() {
        assertNull(subjectRatingText(8.2, ratingsOff, plain))
        assertNull(subjectCardRatingSubtitle(8.2, ratingsOff))
    }

    @Test
    fun `关闭封面评分只藏封面角标，别处的分数照常`() {
        assertNull(subjectCardRatingSubtitle(8.2, coverOff))
        assertEquals("8.2", subjectRatingText(8.2, coverOff, plain))
    }

    @Test
    fun `未评分在两种开关状态下都不渲染`() {
        assertNull(subjectRatingText(0.0, ratingsOn, plain))
        assertNull(subjectRatingText(0.0, ratingsOff, plain))
        assertNull(subjectCardRatingSubtitle(0.0, ratingsOn))
    }

    @Test
    fun `渲染函数只在该显示时被调用`() {
        // 判定与文案分离的关键契约：关闭评分时不得调用 render（否则等于"算了不用"，
        // 且一旦 render 带副作用就会漏出来）。用一个会记录调用的 render 钉住。
        var calls = 0
        val counting: (Double) -> String = { calls++; it.toString() }
        assertNull(subjectRatingText(8.2, ratingsOff, counting))
        assertEquals(0, calls)
        assertEquals("8.2", subjectRatingText(8.2, ratingsOn, counting))
        assertEquals(1, calls)
    }

    @Test
    fun `货架位次大字开着公共评分时就是位次数字`() {
        assertEquals("1", shelfRankNumeralText(1, ratingsOn))
        assertEquals("12", shelfRankNumeralText(12, ratingsOn))
    }

    @Test
    fun `关闭公共评分时货架位次大字一并隐藏`() {
        // 副标题的评分藏了、封面上的位次没藏，等于从另一个口子把排名漏出去
        assertNull(shelfRankNumeralText(1, ratingsOff))
        assertNull(shelfRankNumeralText(12, ratingsOff))
    }

    @Test
    fun `位次为零或负数时不画大字`() {
        assertNull(shelfRankNumeralText(0, ratingsOn))
        assertNull(shelfRankNumeralText(-1, ratingsOn))
    }
}
