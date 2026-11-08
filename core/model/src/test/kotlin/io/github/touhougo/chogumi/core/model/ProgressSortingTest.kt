package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sortProgress] / [lastActiveAt] / [ProgressOrder] / [progressSortsFor] / [titleOrder] 的纯函数口径。
 *
 * 各档 × 两个方向、平局稳定、没有值的始终最后，都在这里钉死。
 */
class ProgressSortingTest {

    /** 只有排序键不同的最小条目：subject 携带标题与评分，`updatedAt` 是收藏更新时间。 */
    private fun item(
        id: Int,
        updatedAt: Long = 0L,
        title: String = "作品$id",
        rating: Double = 0.0,
        date: String? = null,
    ) = UserCollectionBo(
        subjectId = id,
        subjectType = SubjectType.ANIME,
        type = CollectionType.DOING,
        updatedAt = updatedAt,
        subject = SubjectBo(
            id = id, type = SubjectType.ANIME, name = title, nameCn = title,
            ratingScore = rating, date = date,
        ),
    )

    private val today = java.time.LocalDate.of(2026, 10, 12)

    private fun sorted(
        items: List<UserCollectionBo>,
        sort: ProgressSort,
        reversed: Boolean,
        latestWatchedAt: Map<Int, Long> = emptyMap(),
        episodes: Map<Int, List<EpisodeBo>> = emptyMap(),
    ) = ids(sortProgress(items, ProgressOrder(sort, reversed), today, latestWatchedAt, episodes))

    private fun episode(subjectId: Int, sort: Int, airdate: String) = EpisodeBo(
        id = subjectId * 100 + sort, subjectId = subjectId, type = EpisodeType.NORMAL, name = "", nameCn = "",
        sort = sort.toDouble(), airdate = airdate,
    )

    private fun ids(items: List<UserCollectionBo>) = items.map { it.subjectId }

    // ---------- 最近操作时刻 ----------

    @Test
    fun `最近操作时刻取收藏时间与最后看过时刻的较大者`() {
        assertEquals(500L, lastActiveAt(collectionUpdatedAt = 500L, latestWatchedAt = 300L))
        assertEquals(700L, lastActiveAt(collectionUpdatedAt = 500L, latestWatchedAt = 700L))
    }

    @Test
    fun `最近操作时刻 无逐集记录或为 0 时退回收藏时间`() {
        assertEquals(500L, lastActiveAt(500L, null))
        assertEquals(500L, lastActiveAt(500L, 0L))
        assertEquals(0L, lastActiveAt(0L, null))
    }

    // ---------- 方向判定 ----------

    @Test
    fun `方向判定 各档默认方向与逆序`() {
        // 名称默认升序（A 到 Z），其余默认降序（新到旧 / 高到低）。
        ProgressSort.entries.filter { it != ProgressSort.TITLE }.forEach { sort ->
            assertTrue(ProgressOrder(sort, reversed = false).descending)
            assertFalse(ProgressOrder(sort, reversed = true).descending)
        }
        assertFalse(ProgressOrder(ProgressSort.TITLE, reversed = false).descending)
        assertTrue(ProgressOrder(ProgressSort.TITLE, reversed = true).descending)
    }

    // ---------- 各类型有哪些档 ----------

    @Test
    fun `最近更新只给按集推进的类型与全部`() {
        val all = ProgressSort.entries
        val withoutAired = all - ProgressSort.LATEST_AIRED
        assertEquals(all, progressSortsFor(null))
        assertEquals(all, progressSortsFor(SubjectType.ANIME))
        assertEquals(all, progressSortsFor(SubjectType.REAL))
        assertEquals(withoutAired, progressSortsFor(SubjectType.BOOK))
        assertEquals(withoutAired, progressSortsFor(SubjectType.GAME))
        assertEquals(withoutAired, progressSortsFor(SubjectType.MUSIC))
    }

    @Test
    fun `这一页没有选中的档时按最近操作的默认方向排`() {
        val aired = ProgressOrder(ProgressSort.LATEST_AIRED, reversed = true)
        assertEquals(aired, aired.on(SubjectType.ANIME))
        assertEquals(ProgressOrder(), aired.on(SubjectType.BOOK))
        val title = ProgressOrder(ProgressSort.TITLE, reversed = true)
        assertEquals(title, title.on(SubjectType.BOOK))
    }

    // ---------- 最近操作 ----------

    @Test
    fun `最近操作 顺序是最近的在前 逆序整体反过来`() {
        val items = listOf(item(1, updatedAt = 100L), item(2, updatedAt = 300L), item(3, updatedAt = 200L))
        assertEquals(listOf(2, 3, 1), sorted(items, ProgressSort.RECENT, reversed = false))
        assertEquals(listOf(1, 3, 2), sorted(items, ProgressSort.RECENT, reversed = true))
    }

    @Test
    fun `最近操作 刚标记一集的作品排到最前 即使收藏时间较旧`() {
        val items = listOf(item(1, updatedAt = 999L), item(2, updatedAt = 100L))
        // 作品 2 收藏时间旧，但刚看过一集（逐集时刻更新）
        val watched = mapOf(2 to 5_000L)
        assertEquals(listOf(2, 1), sorted(items, ProgressSort.RECENT, reversed = false, latestWatchedAt = watched))
    }

    // ---------- 最近更新 ----------

    @Test
    fun `最近更新 按最后一集已播出的日子 新到旧`() {
        val items = listOf(item(1), item(2), item(3))
        val episodes = mapOf(
            1 to listOf(episode(1, 1, "2026-09-01"), episode(1, 2, "2026-09-08")),
            // 第 2 集还没播：已播到的是第 1 集
            2 to listOf(episode(2, 1, "2026-10-05"), episode(2, 2, "2026-10-19")),
            3 to listOf(episode(3, 1, "2025-04-01")),
        )
        assertEquals(listOf(2, 1, 3), sorted(items, ProgressSort.LATEST_AIRED, reversed = false, episodes = episodes))
        assertEquals(listOf(3, 1, 2), sorted(items, ProgressSort.LATEST_AIRED, reversed = true, episodes = episodes))
    }

    @Test
    fun `最近更新 没有逐集数据或一集都没播的始终在最后`() {
        val items = listOf(item(1), item(2), item(3), item(4))
        val episodes = mapOf(
            2 to listOf(episode(2, 1, "2026-01-01")),
            3 to listOf(episode(3, 1, "2026-03-01")),
            4 to listOf(episode(4, 1, "2027-01-01")),
        )
        // 1 没有逐集数据，4 一集都没播：两个方向都在最后，彼此按 id
        assertEquals(listOf(3, 2, 1, 4), sorted(items, ProgressSort.LATEST_AIRED, reversed = false, episodes = episodes))
        assertEquals(listOf(2, 3, 1, 4), sorted(items, ProgressSort.LATEST_AIRED, reversed = true, episodes = episodes))
    }

    // ---------- 日期 ----------

    @Test
    fun `日期 新到旧 只到月的日期也能比 没有日期的始终在最后`() {
        val items = listOf(
            item(1, date = "2011-01-07"),
            item(2, date = null),
            item(3, date = "2023-10"),
            item(4, date = "2023-09-29"),
            item(5, date = ""),
        )
        assertEquals(listOf(3, 4, 1, 2, 5), sorted(items, ProgressSort.DATE, reversed = false))
        assertEquals(listOf(1, 4, 3, 2, 5), sorted(items, ProgressSort.DATE, reversed = true))
    }

    // ---------- 名称 ----------

    @Test
    fun `名称 顺序为升序 逆序为降序`() {
        val items = listOf(item(1, title = "B"), item(2, title = "A"), item(3, title = "C"))
        assertEquals(listOf(2, 1, 3), sorted(items, ProgressSort.TITLE, reversed = false))
        assertEquals(listOf(3, 1, 2), sorted(items, ProgressSort.TITLE, reversed = true))
    }

    @Test
    fun `名称 中文按拼音排 不按码点`() {
        // 码点顺序是 葬(U+846C) < 轻(U+8F7B) < 魔(U+9B54)；拼音顺序是 魔 mo < 轻 qing < 葬 zang
        val items = listOf(item(1, title = "葬送的芙莉莲"), item(2, title = "魔法少女小圆"), item(3, title = "轻音少女"))
        assertEquals(listOf(2, 3, 1), sorted(items, ProgressSort.TITLE, reversed = false))
    }

    @Test
    fun `名称 拉丁字母不分大小写`() {
        val items = listOf(item(1, title = "b"), item(2, title = "A"), item(3, title = "C"))
        assertEquals(listOf(2, 1, 3), sorted(items, ProgressSort.TITLE, reversed = false))
    }

    // ---------- 评分 ----------

    @Test
    fun `评分 顺序为高到低 逆序为低到高`() {
        val items = listOf(item(1, rating = 5.0), item(2, rating = 9.0), item(3, rating = 7.0))
        assertEquals(listOf(2, 3, 1), sorted(items, ProgressSort.RATING, reversed = false))
        assertEquals(listOf(1, 3, 2), sorted(items, ProgressSort.RATING, reversed = true))
    }

    @Test
    fun `评分 未评分始终排在最后 逆序也不跑到最前`() {
        val items = listOf(item(1, rating = 0.0), item(2, rating = 5.0), item(3, rating = 0.0), item(4, rating = 9.0))
        // 顺序：已评分 9 > 5，未评分（3、1）恒在最后，未评分内部按 id 升序。
        assertEquals(listOf(4, 2, 1, 3), sorted(items, ProgressSort.RATING, reversed = false))
        // 逆序：已评分 5 < 9，未评分**仍在最后**（不因为「低分」跑到最前）。
        assertEquals(listOf(2, 4, 1, 3), sorted(items, ProgressSort.RATING, reversed = true))
    }

    @Test
    fun `缺少条目资料时日期名称评分三档都排最后`() {
        val items = listOf(
            UserCollectionBo(subjectId = 1, subjectType = SubjectType.ANIME, type = CollectionType.DOING, subject = null),
            item(2, rating = 8.0, date = "2020-01-01"),
        )
        listOf(ProgressSort.DATE, ProgressSort.TITLE, ProgressSort.RATING).forEach { sort ->
            assertEquals(listOf(2, 1), sorted(items, sort, reversed = false))
            assertEquals(listOf(2, 1), sorted(items, sort, reversed = true))
        }
    }

    // ---------- 平局稳定 ----------

    @Test
    fun `平局用 subjectId 升序打破 且不随逆序变化`() {
        val items = listOf(item(5, updatedAt = 100L), item(2, updatedAt = 100L), item(9, updatedAt = 100L))
        assertEquals(listOf(2, 5, 9), sorted(items, ProgressSort.RECENT, reversed = false))
        assertEquals(listOf(2, 5, 9), sorted(items, ProgressSort.RECENT, reversed = true))
    }

    @Test
    fun `同一份数据反复排序结果一致`() {
        val items = listOf(item(3, rating = 7.0), item(1, rating = 0.0), item(2, rating = 7.0))
        val once = sorted(items, ProgressSort.RATING, reversed = false)
        repeat(5) { assertEquals(once, sorted(items, ProgressSort.RATING, reversed = false)) }
        // 作品 2、3 同为 7 分：平局按 id 升序（2 在前），未评分的 1 恒最后。
        assertEquals(listOf(2, 3, 1), once)
    }

    // ---------- 空集合 ----------

    @Test
    fun `空列表任一排序都返回空`() {
        ProgressSort.entries.forEach { sort ->
            assertTrue(sorted(emptyList(), sort, reversed = false).isEmpty())
            assertTrue(sorted(emptyList(), sort, reversed = true).isEmpty())
        }
    }
}
