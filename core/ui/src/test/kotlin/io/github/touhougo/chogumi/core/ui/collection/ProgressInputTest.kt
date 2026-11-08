package io.github.touhougo.chogumi.core.ui.collection

import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeType
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressInputTest {

    @Test
    fun `话卷数 零到总数之间的整数都通过 含零与等于总数`() {
        assertEquals(ProgressInput.Valid(5), parseCountInput("5", total = 12))
        assertEquals(ProgressInput.Valid(12), parseCountInput("12", total = 12))
        assertEquals(ProgressInput.Valid(0), parseCountInput("0", total = 12))
        assertEquals(ProgressInput.Valid(3), parseCountInput(" 3 ", total = 12))
    }

    @Test
    fun `话卷数 超出总数给出总数而不是静默截断 总数未知时不设上限`() {
        assertEquals(ProgressInput.OverLimit("12"), parseCountInput("15", total = 12))
        assertEquals(ProgressInput.Valid(100), parseCountInput("100", total = 0))
    }

    @Test
    fun `空与全空白是空`() {
        assertEquals(ProgressInput.Empty, parseCountInput("", total = 12))
        assertEquals(ProgressInput.Empty, parseEpisodeInput("   ", mains(1..12), reach = 12))
    }

    @Test
    fun `话卷数 字母 小数 负数都不算数`() {
        listOf("abc", "2.5", "-1").forEach { assertEquals(ProgressInput.NotANumber, parseCountInput(it, total = 12), it) }
    }

    private fun mains(sorts: IntRange): List<EpisodeBo> = sorts.map { sort ->
        EpisodeBo(id = 1000 + sort, subjectId = 8, type = EpisodeType.NORMAL, name = "ep$sort", nameCn = "", sort = sort.toDouble(), airdate = "")
    }

    @Test
    fun `动画输集号 落到那一集正片的格 0 是一集都没看`() {
        val seasonTwo = mains(202..252)
        assertEquals(ProgressInput.Valid(5), parseEpisodeInput("206", seasonTwo, reach = 51))
        assertEquals(ProgressInput.Valid(0), parseEpisodeInput("0", seasonTwo, reach = 51))
    }

    @Test
    fun `有 sort 为零的正片时输 0 是那一集 还没播出时是一集都没看`() {
        assertEquals(ProgressInput.Valid(1), parseEpisodeInput("0", mains(0..2), reach = 3))
        assertEquals(ProgressInput.Valid(0), parseEpisodeInput("0", mains(0..2), reach = 0))
    }

    @Test
    fun `动画输了本季里的第几集或不存在的小数集号 说没有这一集`() {
        assertEquals(ProgressInput.NoSuchEpisode, parseEpisodeInput("5", mains(202..252), reach = 51))
        assertEquals(ProgressInput.NoSuchEpisode, parseEpisodeInput("3.5", mains(1..12), reach = 12))
    }

    @Test
    fun `动画还没播出的集不能填 给出能到的最后一集`() {
        assertEquals(ProgressInput.OverLimit("5"), parseEpisodeInput("8", mains(1..12), reach = 5))
        assertEquals(ProgressInput.OverLimit("0"), parseEpisodeInput("1", mains(1..12), reach = 0))
        assertEquals(ProgressInput.NotANumber, parseEpisodeInput("-1", mains(1..12), reach = 12))
    }
}
