package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonoWorkSortTest {

    private data class Work(
        val id: Int,
        override val ratingScore: Double = 0.0,
        override val ratingTotal: Int = 0,
        override val date: SubjectYearMonth? = null,
    ) : WorkSortKey

    @Test
    fun `年月解析：实测见过的写法`() {
        assertEquals(SubjectYearMonth(2025, 12), parseSubjectYearMonth("15话 / 2025年12月10日 / 初見浩一"))
        assertEquals(SubjectYearMonth(2025, 3), parseSubjectYearMonth("2025-03-20 / 角銅博之"))
        assertEquals(SubjectYearMonth(2018, 5), parseSubjectYearMonth("1话 / 2018年5月26日"))
        assertEquals(SubjectYearMonth(2007, null), parseSubjectYearMonth("2007年"))
        assertEquals(SubjectYearMonth(1998, 10), parseSubjectYearMonth("1998-10-23"))
    }

    @Test
    fun `年月解析：不是日期的四位数不算`() {
        assertNull(parseSubjectYearMonth("1000话 / ￥ 2500"))
        assertNull(parseSubjectYearMonth(""))
        // 月份越界：不当日期，继续找后面的
        assertEquals(SubjectYearMonth(2019, 2), parseSubjectYearMonth("2019-13 / 2019-02-01"))
        assertNull(parseSubjectYearMonth("1850年3月"))
    }

    @Test
    fun `按时间：新的在前，未知日期沉底，同月保持输入顺序`() {
        val items = listOf(
            Work(1, date = SubjectYearMonth(2018, 2)),
            Work(2, date = null),
            Work(3, date = SubjectYearMonth(2018, 5)),
            Work(4, date = SubjectYearMonth(2018, 5)),
            Work(5, date = SubjectYearMonth(2018, null)),
        )
        assertEquals(listOf(3, 4, 1, 5, 2), sortMonoWorks(items, MonoWorkSort.TIME, publicRatings = true).map { it.id })
    }

    @Test
    fun `按评分：人数不足 50 的沉底，沉底部分按人数排`() {
        val items = listOf(
            Work(1, ratingScore = 10.0, ratingTotal = 1), // 1 人打 10 分的特典 CD
            Work(2, ratingScore = 7.0, ratingTotal = 500),
            Work(3, ratingScore = 8.0, ratingTotal = WORK_MIN_VOTES),
            Work(4, ratingScore = 6.0, ratingTotal = 49),
            Work(5, ratingScore = 7.0, ratingTotal = 900),
        )
        assertEquals(listOf(3, 5, 2, 4, 1), sortMonoWorks(items, MonoWorkSort.RATING, publicRatings = true).map { it.id })
    }

    @Test
    fun `公共评分关掉时请求按评分也按时间`() {
        val items = listOf(
            Work(1, ratingScore = 9.0, ratingTotal = 1000, date = SubjectYearMonth(2001, 1)),
            Work(2, ratingScore = 5.0, ratingTotal = 1000, date = SubjectYearMonth(2020, 1)),
        )
        assertEquals(listOf(2, 1), sortMonoWorks(items, MonoWorkSort.RATING, publicRatings = false).map { it.id })
    }

    @Test
    fun `默认排序与可选项`() {
        assertEquals(MonoWorkSort.RATING, defaultMonoWorkSort(MonoType.PERSON, publicRatings = true))
        assertEquals(MonoWorkSort.TIME, defaultMonoWorkSort(MonoType.CHARACTER, publicRatings = true))
        assertEquals(MonoWorkSort.TIME, defaultMonoWorkSort(MonoType.PERSON, publicRatings = false))
        assertEquals(listOf(MonoWorkSort.RATING, MonoWorkSort.TIME), monoWorkSortOptions(publicRatings = true))
        assertEquals(listOf(MonoWorkSort.TIME), monoWorkSortOptions(publicRatings = false))
    }

    private fun monoWork(id: Int, year: Int, role: CastRole? = null, score: Double = 0.0, votes: Int = 0) = MonoWorkBo(
        id = id, name = "w$id", nameCn = "", image = null, subjectType = SubjectType.ANIME, role = role,
        ratingScore = score, ratingTotal = votes, date = SubjectYearMonth(year, 1),
    )

    @Test
    fun `详情页作品横排：角色按时间排后主角提前，同定位保持时间序，最多露 10 部`() {
        val works = listOf(
            monoWork(1, 2010, CastRole.GUEST),
            monoWork(2, 2022, CastRole.MAIN),
            monoWork(3, 2020, CastRole.GUEST),
            monoWork(4, 2008, CastRole.MAIN),
        )
        assertEquals(
            listOf(2, 4, 3, 1),
            monoWorksPreview(MonoType.CHARACTER, works, publicRatings = true).map { it.id },
        )
        val many = (1..15).map { monoWork(it, 2000 + it, CastRole.MAIN) }
        assertEquals(MONO_WORKS_PREVIEW_COUNT, monoWorksPreview(MonoType.CHARACTER, many, publicRatings = true).size)
    }

    @Test
    fun `详情页作品横排：人物没有定位，就是默认的按评分排序`() {
        val works = listOf(
            monoWork(1, 2010, score = 7.0, votes = 100),
            monoWork(2, 2011, score = 9.0, votes = 100),
            monoWork(3, 2012, score = 9.9, votes = 1),
        )
        assertEquals(listOf(2, 1, 3), monoWorksPreview(MonoType.PERSON, works, publicRatings = true).map { it.id })
    }
}
