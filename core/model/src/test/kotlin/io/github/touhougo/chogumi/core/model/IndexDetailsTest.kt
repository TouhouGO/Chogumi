package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexDetailsTest {

    private fun entry(id: Int, score: Double = 0.0, votes: Int = 0) =
        IndexEntryBo(id = id, targetId = id, name = "s$id", ratingScore = score, ratingTotal = votes)

    private val entries = listOf(
        entry(1, score = 6.0, votes = 500),
        entry(2, score = 9.9, votes = 3),
        entry(3, score = 7.6, votes = 800),
        entry(4, score = 7.6, votes = 900),
    )

    @Test
    fun `收录顺序原样`() {
        assertEquals(listOf(1, 2, 3, 4), sortIndexEntries(entries, IndexEntrySort.ORDER, publicRatings = true).map { it.id })
    }

    @Test
    fun `按评分：人数够的按分降序、同分人数多的在前，人数不足的沉底`() {
        assertEquals(listOf(4, 3, 1, 2), sortIndexEntries(entries, IndexEntrySort.RATING, publicRatings = true).map { it.id })
    }

    @Test
    fun `公共评分关掉：只有收录顺序，请求按评分也按收录顺序`() {
        assertEquals(listOf(IndexEntrySort.ORDER), indexEntrySortOptions(IndexCategory.SUBJECT, publicRatings = false))
        assertEquals(listOf(IndexEntrySort.ORDER, IndexEntrySort.RATING), indexEntrySortOptions(IndexCategory.SUBJECT, publicRatings = true))
        // 角色 / 人物没有评分，只有收录顺序
        assertEquals(listOf(IndexEntrySort.ORDER), indexEntrySortOptions(IndexCategory.CHARACTER, publicRatings = true))
        assertEquals(listOf(1, 2, 3, 4), sortIndexEntries(entries, IndexEntrySort.RATING, publicRatings = false).map { it.id })
    }

    @Test
    fun `副行取 info 的前两段，空段丢掉`() {
        assertEquals(listOf("1话", "1987年2月23日"), subjectInfoHead("1话 / 1987年2月23日 / 导演 / 原作"))
        assertEquals(listOf("2002年4月2日"), subjectInfoHead(" / 2002年4月2日"))
        assertEquals(emptyList<String>(), subjectInfoHead(""))
    }

    @Test
    fun `收录类别与线上的数一一对应_不认识的数为null`() {
        assertEquals((0..6).toList(), IndexCategory.entries.map { it.code })
        IndexCategory.entries.forEach { assertEquals(it, IndexCategory.fromCode(it.code)) }
        assertEquals(null, IndexCategory.fromCode(7))
    }

    @Test
    fun `角色人物与两种话题各收成自己的类别`() {
        assertEquals(IndexCategory.CHARACTER, monoTypeSpec(MonoType.CHARACTER).indexCategory)
        assertEquals(IndexCategory.PERSON, monoTypeSpec(MonoType.PERSON).indexCategory)
        assertEquals(IndexCategory.GROUP_TOPIC, indexCategoryOf(TopicType.GROUP))
        assertEquals(IndexCategory.SUBJECT_TOPIC, indexCategoryOf(TopicType.SUBJECT))
    }
}
