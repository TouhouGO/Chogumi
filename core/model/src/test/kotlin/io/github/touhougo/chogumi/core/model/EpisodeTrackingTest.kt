package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.BANGUMI_ZONE
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `assertIs` 的 JUnit4 等价物：先断言是目标类型，再返回它（无 kotlin-test 依赖的模块里用它）。 */
private inline fun <reified T> assertIsInstance(value: Any?, message: String? = null): T {
    assertTrue(message ?: "期望 ${T::class.simpleName}，实际 $value", value is T)
    return value as T
}

/**
 * [EpisodeTracking] 全表单测。
 *
 * 表驱动覆盖：正常、未知播出日、SP 等非正片、集号不从 1 开始（第二季从 13 起）、
 * 书籍、已完结，以及每条边界的双分支（含 / 不含）。
 */
class EpisodeTrackingTest {

    private val today = LocalDate.of(2026, 9, 28)

    private fun ep(
        id: Int,
        sort: Double,
        airdate: String = "",
        type: Int = EpisodeType.NORMAL,
    ) = EpisodeBo(id = id, subjectId = 1, type = type, name = "e$id", nameCn = "", sort = sort, airdate = airdate)

    /** 方便构造 `id -> 状态`；未列出的集即「无记录」。 */
    private fun states(vararg pairs: Pair<Int, EpisodeStatus>): Map<Int, EpisodeStatus> = pairs.toMap()

    // ---------- 下一集 ----------

    @Test
    fun `下一集是看到这里之后的那一集`() {
        val eps = listOf(ep(1, 1.0), ep(2, 2.0), ep(3, 3.0))
        assertEquals(2, EpisodeTracking.nextEpisode(eps, states(1 to EpisodeStatus.DONE))?.id)
    }

    @Test
    fun `下一集在全部无记录时是 sort 最小的正片`() {
        val eps = listOf(ep(3, 3.0), ep(1, 1.0), ep(2, 2.0))
        assertEquals(1, EpisodeTracking.nextEpisode(eps, emptyMap())?.id)
    }

    @Test
    fun `下一集全部看完时为 null`() {
        val eps = listOf(ep(1, 1.0), ep(2, 2.0))
        assertNull(EpisodeTracking.nextEpisode(eps, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE)))
    }

    @Test
    fun `下一集不回头补看到这里之前的空洞`() {
        // 1、3 看过，2 没看：看到这里是 3，下一集是 4，不是 2
        val eps = listOf(ep(1, 1.0), ep(2, 2.0), ep(3, 3.0), ep(4, 4.0))
        assertEquals(4, EpisodeTracking.nextEpisode(eps, states(1 to EpisodeStatus.DONE, 3 to EpisodeStatus.DONE))?.id)
        // 看到这里之前的抛弃同样不算
        assertEquals(
            4,
            EpisodeTracking.nextEpisode(eps, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DROPPED, 3 to EpisodeStatus.DONE))?.id,
        )
    }

    @Test
    fun `看到最后一集就没有下一集 前面有空洞也一样`() {
        val eps = listOf(ep(1, 1.0), ep(2, 2.0), ep(3, 3.0))
        assertNull(EpisodeTracking.nextEpisode(eps, states(1 to EpisodeStatus.DONE, 3 to EpisodeStatus.DONE)))
    }

    @Test
    fun `一集都没看过时 下一集是第一集 想看与抛弃不算看过`() {
        val eps = listOf(ep(1, 1.0), ep(2, 2.0))
        assertEquals(1, EpisodeTracking.nextEpisode(eps, states(1 to EpisodeStatus.WISH))?.id)
        assertEquals(1, EpisodeTracking.nextEpisode(eps, states(1 to EpisodeStatus.DROPPED))?.id)
    }

    @Test
    fun `下一集忽略 SP OP ED 等非正片`() {
        val eps = listOf(
            ep(1, 1.0),
            ep(90, 1.5, type = EpisodeType.SPECIAL),   // SP，sort 在正片之间
            ep(2, 2.0),
            ep(91, 2.5, type = EpisodeType.OP),   // OP
            ep(92, 2.6, type = EpisodeType.ED),   // ED
        )
        // 只看正片：sort 1 未看 -> 1；把 1 标看过 -> 2（绝不落在 SP/OP/ED 上）
        assertEquals(1, EpisodeTracking.nextEpisode(eps, emptyMap())?.id)
        assertEquals(2, EpisodeTracking.nextEpisode(eps, states(1 to EpisodeStatus.DONE))?.id)
    }

    @Test
    fun `下一集在只有非正片时为 null`() {
        val eps = listOf(ep(90, 1.0, type = EpisodeType.SPECIAL), ep(91, 2.0, type = EpisodeType.OP))
        assertNull(EpisodeTracking.nextEpisode(eps, emptyMap()))
    }

    @Test
    fun `下一集在空列表时为 null`() {
        assertNull(EpisodeTracking.nextEpisode(emptyList(), emptyMap()))
    }

    @Test
    fun `集号不从 1 开始时 下一集是看到这里之后的那一集`() {
        // 第二季集号 13..24
        val eps = (13..24).map { ep(it, it.toDouble()) }
        assertEquals(13, EpisodeTracking.nextEpisode(eps, emptyMap())?.id)
        assertEquals(15, EpisodeTracking.nextEpisode(eps, states(13 to EpisodeStatus.DONE, 14 to EpisodeStatus.DONE))?.id)
    }

    @Test
    fun `集号不从 1 开始时全部看完为 null`() {
        val eps = (13..24).map { ep(it, it.toDouble()) }
        assertNull(EpisodeTracking.nextEpisode(eps, (13..24).associate { it to EpisodeStatus.DONE }))
    }

    // ---------- 已播未看集数 ----------

    @Test
    fun `已播未看只数已播出且未看的正片`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-01"),
            ep(2, 2.0, "2026-09-10"),
            ep(3, 3.0, "2026-10-01"),   // 未来
        )
        // 全未看：已播 2 集
        assertEquals(2, EpisodeTracking.airedUnwatchedCount(eps, emptyMap(), today))
        // 看了第 1 集：还剩 1 集
        assertEquals(1, EpisodeTracking.airedUnwatchedCount(eps, states(1 to EpisodeStatus.DONE), today))
        // 两集都看：0
        assertEquals(0, EpisodeTracking.airedUnwatchedCount(eps, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE), today))
    }

    @Test
    fun `今天播出的算已播`() {
        val eps = listOf(ep(1, 1.0, "2026-09-28"))
        assertEquals(1, EpisodeTracking.airedUnwatchedCount(eps, emptyMap(), today))
    }

    @Test
    fun `明天播出的不算已播`() {
        val eps = listOf(ep(1, 1.0, "2026-09-29"))
        assertEquals(0, EpisodeTracking.airedUnwatchedCount(eps, emptyMap(), today))
    }

    @Test
    fun `未知播出日不计入已播未看`() {
        val eps = listOf(ep(1, 1.0, ""), ep(2, 2.0, "not-a-date"), ep(3, 3.0, "2026-02-30"))
        assertEquals(0, EpisodeTracking.airedUnwatchedCount(eps, emptyMap(), today))
    }

    @Test
    fun `已播未看忽略非正片`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-01"),
            ep(90, 1.5, "2026-09-02", type = EpisodeType.SPECIAL),   // SP 已播但不算
        )
        assertEquals(1, EpisodeTracking.airedUnwatchedCount(eps, emptyMap(), today))
    }

    @Test
    fun `已播未看只数看到这里之后的集 前面的空洞不算`() {
        val eps = (1..6).map { ep(it, it.toDouble(), "2026-09-01") }
        // 看到这里是 4：2 抛弃、3 想看在它之前，不算；5、6 已播没看，算 2 集
        val st = states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DROPPED, 3 to EpisodeStatus.WISH, 4 to EpisodeStatus.DONE)
        assertEquals(2, EpisodeTracking.airedUnwatchedCount(eps, st, today))
    }

    @Test
    fun `一集都没看过时 想看与抛弃的已播集也算已播未看`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"))
        assertEquals(1, EpisodeTracking.airedUnwatchedCount(eps, states(1 to EpisodeStatus.WISH), today))
        assertEquals(1, EpisodeTracking.airedUnwatchedCount(eps, states(1 to EpisodeStatus.DROPPED), today))
    }

    @Test
    fun `空章节列表的已播未看为 0`() {
        assertEquals(0, EpisodeTracking.airedUnwatchedCount(emptyList(), emptyMap(), today))
    }

    // ---------- 是否连载中 ----------

    @Test
    fun `存在未来播出日即连载中`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-10-05"))
        assertTrue(EpisodeTracking.isOngoing(eps, today))
    }

    @Test
    fun `今天播出的正片算连载中（今天播最终话仍在连载）`() {
        val eps = listOf(ep(1, 1.0, "2026-09-28"))
        assertTrue(EpisodeTracking.isOngoing(eps, today))
    }

    @Test
    fun `全部已播出即已完结`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-09-08"))
        assertFalse(EpisodeTracking.isOngoing(eps, today))
    }

    @Test
    fun `未知播出日不算连载中`() {
        assertFalse(EpisodeTracking.isOngoing(listOf(ep(1, 1.0, ""), ep(2, 2.0, "bad")), today))
    }

    @Test
    fun `未来播出日的非正片不算连载中`() {
        // SP 未来播出不应把已完结番判成连载中
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(90, 2.0, "2026-10-05", type = EpisodeType.SPECIAL))
        assertFalse(EpisodeTracking.isOngoing(eps, today))
    }

    @Test
    fun `空章节列表不算连载中`() {
        assertFalse(EpisodeTracking.isOngoing(emptyList(), today))
    }

    // ---------- 首页区块归属 ----------

    private fun section(
        type: SubjectType,
        eps: List<EpisodeBo>,
        st: Map<Int, EpisodeStatus> = emptyMap(),
    ) = EpisodeTracking.homeSection(type, eps, st, today)

    @Test
    fun `连载中且有已播未看归待看`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-10-05"))
        assertEquals(HomeSection.TO_WATCH, section(SubjectType.ANIME, eps))
    }

    @Test
    fun `连载中但已追平归仅时间表`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-10-05"))
        assertEquals(HomeSection.SCHEDULE_ONLY, section(SubjectType.ANIME, eps, states(1 to EpisodeStatus.DONE)))
    }

    @Test
    fun `已完结有已播未看正片归待看`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-09-08"))
        assertEquals(HomeSection.TO_WATCH, section(SubjectType.ANIME, eps))
    }

    @Test
    fun `已完结看到最后一集 前面抛弃过一集 归继续不归待看`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-09-08"), ep(3, 3.0, "2026-09-15"))
        assertEquals(
            HomeSection.CONTINUE,
            section(SubjectType.ANIME, eps, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DROPPED, 3 to EpisodeStatus.DONE)),
        )
    }

    @Test
    fun `已完结全部看完归继续`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-09-08"))
        assertEquals(
            HomeSection.CONTINUE,
            section(SubjectType.ANIME, eps, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE)),
        )
    }

    @Test
    fun `已完结的动画与三次元只要有已播未看正片就归待看`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-09-08"))
        listOf(SubjectType.ANIME, SubjectType.REAL).forEach { type ->
            assertEquals(HomeSection.TO_WATCH, section(type, eps, states(1 to EpisodeStatus.DONE)))
        }
    }

    @Test
    fun `书籍归继续`() {
        assertEquals(HomeSection.CONTINUE, section(SubjectType.BOOK, emptyList()))
    }

    @Test
    fun `游戏归也在进行`() {
        assertEquals(HomeSection.IN_PROGRESS, section(SubjectType.GAME, emptyList()))
    }

    @Test
    fun `音乐归也在进行`() {
        assertEquals(HomeSection.IN_PROGRESS, section(SubjectType.MUSIC, emptyList()))
    }

    @Test
    fun `首页区块与待看优先级表`() {
        val priorityToday = LocalDate.of(2026, 9, 29)
        fun millis(date: LocalDate, hour: Int = 0, minute: Int = 0, second: Int = 0) =
            date.atTime(hour, minute, second).atZone(BANGUMI_ZONE).toInstant().toEpochMilli()
        fun episodes(id: Int, airDates: List<LocalDate>) = airDates.mapIndexed { index, date ->
            ep(id * 100 + index + 1, (index + 1).toDouble(), date.toString())
        }
        val past = LocalDate.of(2026, 9, 1)
        data class Case(
            val subjectId: Int,
            val type: SubjectType,
            val episodes: List<EpisodeBo>,
            val states: Map<Int, EpisodeStatus>,
            val lastActiveAt: Long,
            val section: HomeSection,
            val recency: Long? = null,
        )
        val yesterdayWatch = millis(priorityToday.minusDays(1), 23, 59, 59)
        val monthOld = millis(priorityToday.minusMonths(1))
        val weekOld = millis(priorityToday.minusDays(7))
        val cases = listOf(
            Case(
                subjectId = 1,
                type = SubjectType.ANIME,
                episodes = episodes(1, (1..6).map { past.plusDays(it.toLong()) }),
                states = (1..5).associate { 100 + it to EpisodeStatus.DONE },
                lastActiveAt = yesterdayWatch,
                section = HomeSection.TO_WATCH,
                recency = yesterdayWatch,
            ),
            Case(
                subjectId = 2,
                type = SubjectType.ANIME,
                episodes = episodes(2, listOf(past.plusDays(19), priorityToday, priorityToday.plusDays(7))),
                states = mapOf(201 to EpisodeStatus.DONE),
                lastActiveAt = weekOld,
                section = HomeSection.TO_WATCH,
                recency = millis(priorityToday),
            ),
            Case(
                subjectId = 3,
                type = SubjectType.ANIME,
                episodes = episodes(3, (1..5).map { priorityToday.minusDays((5 - it).toLong()) }),
                states = emptyMap(),
                lastActiveAt = monthOld,
                section = HomeSection.TO_WATCH,
                recency = monthOld,
            ),
            Case(
                subjectId = 4,
                type = SubjectType.ANIME,
                episodes = episodes(4, (1..20).map { LocalDate.of(2026, 8, 30).plusDays(it.toLong()) }),
                states = emptyMap(),
                lastActiveAt = millis(priorityToday.minusMonths(3)),
                section = HomeSection.TO_WATCH,
                recency = millis(priorityToday.minusMonths(3)),
            ),
            Case(
                subjectId = 5,
                type = SubjectType.ANIME,
                episodes = episodes(5, listOf(priorityToday.minusDays(1), priorityToday.plusDays(7))),
                states = mapOf(501 to EpisodeStatus.DONE),
                lastActiveAt = weekOld,
                section = HomeSection.SCHEDULE_ONLY,
            ),
            Case(
                subjectId = 6,
                type = SubjectType.ANIME,
                episodes = episodes(6, listOf(priorityToday.minusDays(2), priorityToday.minusDays(1))),
                states = mapOf(601 to EpisodeStatus.DONE, 602 to EpisodeStatus.DONE),
                lastActiveAt = weekOld,
                section = HomeSection.CONTINUE,
            ),
            Case(7, SubjectType.BOOK, emptyList(), emptyMap(), weekOld, HomeSection.CONTINUE),
            Case(8, SubjectType.GAME, emptyList(), emptyMap(), weekOld, HomeSection.IN_PROGRESS),
            Case(14, SubjectType.MUSIC, emptyList(), emptyMap(), weekOld, HomeSection.IN_PROGRESS),
            Case(
                subjectId = 9,
                type = SubjectType.ANIME,
                episodes = episodes(9, listOf(priorityToday.minusDays(1), priorityToday, priorityToday.plusDays(7))),
                states = emptyMap(),
                lastActiveAt = weekOld,
                section = HomeSection.TO_WATCH,
                recency = millis(priorityToday),
            ),
            Case(
                subjectId = 10,
                type = SubjectType.ANIME,
                episodes = episodes(10, listOf(priorityToday.minusDays(1), priorityToday, priorityToday.plusDays(7))),
                states = emptyMap(),
                lastActiveAt = weekOld,
                section = HomeSection.TO_WATCH,
                recency = millis(priorityToday),
            ),
            Case(
                subjectId = 11,
                type = SubjectType.ANIME,
                episodes = episodes(11, listOf(priorityToday.minusDays(14), priorityToday.minusDays(7), priorityToday, priorityToday.plusDays(7))),
                states = emptyMap(),
                lastActiveAt = millis(priorityToday.minusDays(9)),
                section = HomeSection.TO_WATCH,
                recency = millis(priorityToday.minusDays(9)),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                "区块: ${case.subjectId}",
                case.section,
                EpisodeTracking.homeSection(case.type, case.episodes, case.states, priorityToday),
            )
            if (case.section == HomeSection.TO_WATCH) {
                assertEquals(
                    "最近相关时刻: ${case.subjectId}",
                    case.recency,
                    EpisodeTracking.toWatchRecency(case.episodes, case.states, priorityToday, case.lastActiveAt),
                )
            }
        }

        val ranked = EpisodeTracking.rankToWatch(
            cases.filter { it.section == HomeSection.TO_WATCH }.map {
                ToWatchRank(
                    subjectId = it.subjectId,
                    lastActiveAt = it.lastActiveAt,
                    relevantAt = EpisodeTracking.toWatchRecency(it.episodes, it.states, priorityToday, it.lastActiveAt),
                    airedUnwatchedCount = EpisodeTracking.airedUnwatchedCount(it.episodes, it.states, priorityToday),
                )
            },
            priorityToday,
        )
        // 3（一个月前）与 4（三个月前）相关时刻早于「今天 − 10 天」→ 冷却出局，不进首页
        assertEquals(listOf(2, 9, 10, 1, 11), ranked.map { it.subjectId })
        assertEquals(2, EpisodeTracking.airedUnwatchedCount(cases.first { it.subjectId == 9 }.episodes, emptyMap(), priorityToday))
        assertEquals(3, EpisodeTracking.airedUnwatchedCount(cases.first { it.subjectId == 11 }.episodes, emptyMap(), priorityToday))
    }

    @Test
    fun `游戏即使有连载章节也归也在进行`() {
        // 优先级：类型判断在前，不被章节状态抢走
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-10-05"))
        assertEquals(HomeSection.IN_PROGRESS, section(SubjectType.GAME, eps))
    }

    @Test
    fun `三次元按动画同规则推导`() {
        val ongoing = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-10-05"))
        assertEquals(HomeSection.TO_WATCH, section(SubjectType.REAL, ongoing))
        assertEquals(HomeSection.TO_WATCH, section(SubjectType.REAL, listOf(ep(1, 1.0, "2026-09-01"))))
    }

    @Test
    fun `区块归属在 EP 未知播出日时保守归继续`() {
        // 播出日缺失 -> 判不出连载 -> 继续（不会误进待看）
        assertEquals(HomeSection.CONTINUE, section(SubjectType.ANIME, listOf(ep(1, 1.0, ""))))
    }

    @Test
    fun `每个条目只落一个区块 四类互斥`() {
        val ongoing = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-10-05"))
        val finished = listOf(ep(1, 1.0, "2026-09-01"))
        val got = setOf(
            section(SubjectType.ANIME, ongoing),
            section(SubjectType.ANIME, ongoing, states(1 to EpisodeStatus.DONE)),
            section(SubjectType.ANIME, finished, states(1 to EpisodeStatus.DONE)),
            section(SubjectType.GAME, emptyList()),
        )
        assertEquals(
            setOf(HomeSection.TO_WATCH, HomeSection.SCHEDULE_ONLY, HomeSection.CONTINUE, HomeSection.IN_PROGRESS),
            got,
        )
    }

    @Test
    fun `第二季集号从 13 开始 区块归属仍正确`() {
        val eps = (13..24).map { ep(it, it.toDouble(), if (it <= 20) "2026-09-20" else "2026-10-05") }
        // 13..20 已播，21..24 未播；全未看 -> 待看
        assertEquals(HomeSection.TO_WATCH, section(SubjectType.ANIME, eps))
        // 13..20 全看 -> 追平 -> 仅时间表
        assertEquals(HomeSection.SCHEDULE_ONLY, section(SubjectType.ANIME, eps, (13..20).associate { it to EpisodeStatus.DONE }))
    }

    // ---------- 待看：冷却 + 排序（场景表 A–H） ----------

    /** 场景表用的一天（周五）。 */
    private val picksToday = LocalDate.of(2026, 9, 25)

    private fun at(date: LocalDate, hour: Int = 0) =
        date.atTime(hour, 0).atZone(BANGUMI_ZONE).toInstant().toEpochMilli()

    /** 按场景构造一个条目的排序键；`lastActiveAt` 与播出日分别给出。 */
    private fun rankOf(
        subjectId: Int,
        episodes: List<EpisodeBo>,
        states: Map<Int, EpisodeStatus> = emptyMap(),
        lastActiveAt: Long = 0L,
    ) = ToWatchRank(
        subjectId = subjectId,
        lastActiveAt = lastActiveAt,
        relevantAt = EpisodeTracking.toWatchRecency(episodes, states, picksToday, lastActiveAt),
        airedUnwatchedCount = EpisodeTracking.airedUnwatchedCount(episodes, states, picksToday),
    )

    private fun orderedIds(ranks: List<ToWatchRank>) = EpisodeTracking.rankToWatch(ranks, picksToday).map { it.subjectId }

    @Test
    fun `A 周更 今天播新集 上次看是 6 天前`() {
        // 6 集周更，最后（第 6）集今天播；前 5 集看完 → 积压 1，freshAirAt = 今天 0 点
        val eps = (1..6).map { ep(it, it.toDouble(), picksToday.minusDays((6L - it)).toString()) }
        val states = (1..5).associate { it to EpisodeStatus.DONE }
        val caseA = rankOf(1, eps, states, lastActiveAt = at(picksToday.minusDays(6), 12))
        // 对照：一部 6 天前看过、积压大且没有新播出（相关时刻停在 6 天前）→ A 应排在它前面
        val foil = rankOf(2, (1..5).map { ep(100 + it, it.toDouble(), picksToday.minusDays(30).toString()) }, emptyMap(), lastActiveAt = at(picksToday.minusDays(6), 12))
        assertEquals(at(picksToday), caseA.relevantAt)
        assertEquals(listOf(1, 2), orderedIds(listOf(foil, caseA)))
    }

    @Test
    fun `B 补老番 1 小时前连看 积压 12 集`() {
        // 12 集已播全没看：积压 12 > 2 → freshAirAt 不计，只剩 lastActiveAt（今天 11 点，最新）
        val eps = (1..12).map { ep(it, it.toDouble(), picksToday.minusDays(20).toString()) }
        val caseB = rankOf(1, eps, emptyMap(), lastActiveAt = at(picksToday, 11))
        // 对照：一部今天刚播新集、但积压小，相关时刻只有今天 0 点 → B 应排第一
        val fresh = rankOf(2, listOf(ep(201, 1.0, picksToday.toString()), ep(202, 2.0, picksToday.plusDays(7).toString())), emptyMap(), lastActiveAt = at(picksToday.minusDays(3)))
        assertEquals(at(picksToday, 11), caseB.relevantAt)
        assertEquals(12, caseB.airedUnwatchedCount)
        assertEquals(listOf(1, 2), orderedIds(listOf(fresh, caseB)))
    }

    @Test
    fun `四部今天更新且各积压一集 按各自最近一次看的时间排`() {
        // 4 部都是今天播出、积压都 ≤2 → relevantAt 全是今天 0 点，靠第二键 lastActiveAt 分先后
        fun fresh(id: Int, activeHour: Int): ToWatchRank {
            val eps = listOf(ep(id * 10 + 1, 1.0, picksToday.toString()), ep(id * 10 + 2, 2.0, picksToday.plusDays(7).toString()))
            return rankOf(id, eps, emptyMap(), lastActiveAt = at(picksToday.minusDays(1), activeHour))
        }
        val ranks = listOf(fresh(10, 1), fresh(20, 20), fresh(30, 5), fresh(40, 12))
        assertEquals(List(4) { 1 }, ranks.map { it.airedUnwatchedCount })
        assertEquals(List(4) { at(picksToday) }, ranks.map { it.relevantAt })
        assertEquals(listOf(20, 40, 30, 10), orderedIds(ranks))
    }

    @Test
    fun `D 跳了一周 积压 2 集 今天又播一集 仍计 freshAirAt`() {
        // 第 1 集两周前看完；跳更一周，第 2 集一周前播、第 3 集今天播都不看 → 积压恰 2（等于阈值仍计）
        val eps = listOf(
            ep(1, 1.0, picksToday.minusDays(14).toString()),
            ep(2, 2.0, picksToday.minusDays(7).toString()),
            ep(3, 3.0, picksToday.toString()),
            ep(4, 4.0, picksToday.plusDays(7).toString()),
        )
        val caseD = rankOf(1, eps, mapOf(1 to EpisodeStatus.DONE), lastActiveAt = at(picksToday.minusDays(14), 9))
        assertEquals(2, caseD.airedUnwatchedCount)
        assertEquals(at(picksToday), caseD.relevantAt)
        // 若 2 集积压被误判成「不计 fresh」，relevantAt 会退回 14 天前 → 被冷却出局；这里必须仍在
        assertEquals(listOf(1), orderedIds(listOf(caseD)))
    }

    @Test
    fun `E 弃坑 积压 3 集 3 周没碰 出局`() {
        // 3 集全未看（积压 3 > 2）→ freshAirAt 不计；lastActiveAt 3 周前 → 早于冷却线
        val eps = (1..3).map { ep(it, it.toDouble(), picksToday.minusDays(21).toString()) }
        assertTrue(orderedIds(listOf(rankOf(1, eps, emptyMap(), lastActiveAt = at(picksToday.minusDays(21))))).isEmpty())
    }

    @Test
    fun `F 季前加入在看 两周后第一集今天播 仍靠前`() {
        // 加入时间久远（14 天前），但第一集今天播且积压 1 → freshAirAt 覆盖旧的加入时间，排第一
        val eps = listOf(ep(1, 1.0, picksToday.toString()), ep(2, 2.0, picksToday.plusDays(7).toString()))
        val caseF = rankOf(1, eps, emptyMap(), lastActiveAt = at(picksToday.minusDays(14)))
        val foil = rankOf(2, listOf(ep(201, 1.0, picksToday.minusDays(3).toString())), emptyMap(), lastActiveAt = at(picksToday.minusDays(3)))
        assertEquals(at(picksToday), caseF.relevantAt)
        assertEquals(listOf(1, 2), orderedIds(listOf(foil, caseF)))
    }

    @Test
    fun `G 11 天前加入的完结番 一集没看 出局`() {
        // 完结番（无未来播出日），一集没看 → 积压 1 但 freshAirAt 取「最新已播正片」= 11 天前的播出日，
        // 早于冷却线 → 出局（靠冷却，不是靠积压）
        val eps = listOf(ep(1, 1.0, picksToday.minusDays(11).toString()))
        assertTrue(orderedIds(listOf(rankOf(1, eps, emptyMap(), lastActiveAt = at(picksToday.minusDays(11))))).isEmpty())
    }

    @Test
    fun `冷却边界恰为第十天零点仍在 早一毫秒出局`() {
        val cutoff = at(picksToday.minusDays(10))
        // 播出比冷却线更早，相关时刻只由最近操作决定，才能落在毫秒边界上。
        val episodes = listOf(ep(1, 1.0, picksToday.minusDays(20).toString()))
        val exactlyCutoff = rankOf(1, episodes, lastActiveAt = cutoff)
        val oneMillisecondEarly = rankOf(2, episodes, lastActiveAt = cutoff - 1)
        val oneMillisecondLater = rankOf(3, episodes, lastActiveAt = cutoff + 1)

        assertEquals(cutoff, exactlyCutoff.relevantAt)
        assertEquals(cutoff - 1, oneMillisecondEarly.relevantAt)
        assertEquals(cutoff + 1, oneMillisecondLater.relevantAt)
        assertEquals(listOf(3, 1), orderedIds(listOf(oneMillisecondEarly, exactlyCutoff, oneMillisecondLater)))
    }

    @Test
    fun `冷却出局的完结作品仍归待看候选`() {
        // 冷却只作用于 rankToWatch 的入参；区块归属仍由 homeSection 决定（11 天前的完结番仍是待看区块）
        val eps = listOf(ep(1, 1.0, picksToday.minusDays(11).toString()))
        assertEquals(HomeSection.TO_WATCH, section(SubjectType.ANIME, eps))
    }

    // ---------- 看到第 N 集后的目标状态 ----------

    @Test
    fun `看到第 N 集 不晚于 N 的任何状态都变看过`() {
        val eps = (1..5).map { ep(it, it.toDouble()) }
        listOf(null, EpisodeStatus.NONE, EpisodeStatus.WISH, EpisodeStatus.DONE, EpisodeStatus.DROPPED).forEach { status ->
            val current = status?.let { s -> eps.associate { it.id to s } }.orEmpty()
            assertEquals(
                eps.associate { it.id to EpisodeStatus.DONE },
                EpisodeTracking.statesAfterWatchedUpTo(eps, current, EpisodeProgressTarget.Episode(5, 5.0)),
            )
        }
    }

    @Test
    fun `看到第 N 集 晚于 N 的只清看过 想看抛弃保持`() {
        val eps = (5..6).map { ep(it, it.toDouble()) }
        fun sixthAfter(status: EpisodeStatus?) =
            EpisodeTracking.statesAfterWatchedUpTo(eps, status?.let { mapOf(6 to it) }.orEmpty(), EpisodeProgressTarget.Episode(5, 5.0))?.get(6)
        assertEquals(EpisodeStatus.NONE, sixthAfter(EpisodeStatus.DONE))
        assertEquals(EpisodeStatus.WISH, sixthAfter(EpisodeStatus.WISH))
        assertEquals(EpisodeStatus.DROPPED, sixthAfter(EpisodeStatus.DROPPED))
        assertEquals(EpisodeStatus.NONE, sixthAfter(null))
    }

    // ---------- 正片 / 非正片切分 ----------

    @Test
    fun `mainEpisodes 只取正片并按 sort 排序`() {
        val eps = listOf(ep(3, 3.0), ep(90, 1.5, type = EpisodeType.SPECIAL), ep(1, 1.0), ep(91, 2.5, type = EpisodeType.OP))
        assertEquals(listOf(1, 3), EpisodeTracking.mainEpisodes(eps).map { it.id })
        assertEquals(listOf(90, 91), EpisodeTracking.specialEpisodes(eps).map { it.id })
    }

    // ---------- 章节方格 ----------

    /** 这里的行与连段断言都按每行 5 格写（真实列数由 core:ui 按屏宽给出）。 */
    private val columns = 5

    /**
     * 网格行：统一走 [EpisodeTracking.episodeGrid]（界面唯一的入口），
     * 不另开绕过分段入口的取行函数。
     */
    private fun gridRows(
        episodes: List<EpisodeBo>,
        st: Map<Int, EpisodeStatus> = emptyMap(),
        columns: Int = this.columns,
        selectedRange: Int = 0,
    ): List<EpisodeRow> =
        EpisodeTracking.episodeGrid(episodes, st, today, selectedRange, columns).rows

    /** 把网格铺平成 `id -> 种类`，便于表驱动断言。 */
    private fun kinds(
        episodes: List<EpisodeBo>,
        st: Map<Int, EpisodeStatus> = emptyMap(),
    ): Map<Int, EpisodeCellKind> = gridRows(episodes, st)
        .flatMap { it.cells }
        .associate { it.episode.id to it.kind }

    /** 把网格铺平成 `(行号 to (起, 止))` 列表。 */
    private fun bands(
        episodes: List<EpisodeBo>,
        st: Map<Int, EpisodeStatus> = emptyMap(),
        columns: Int = this.columns,
    ): List<Pair<Int, EpisodeBand>> = gridRows(episodes, st, columns)
        .flatMapIndexed { index, row -> row.bands.map { index to it } }

    @Test
    fun `全未看时第一集是下一集 其余已播是已播出`() {
        val eps = (1..3).map { ep(it, it.toDouble(), "2026-09-01") }
        val got = kinds(eps)
        assertEquals(EpisodeCellKind.NEXT, got[1])
        assertEquals(EpisodeCellKind.AIRED, got[2])
        assertEquals(EpisodeCellKind.AIRED, got[3])
    }

    @Test
    fun `全看完时最后一集是看到这里 其余是看过 没有下一集`() {
        val eps = (1..3).map { ep(it, it.toDouble(), "2026-09-01") }
        val got = kinds(eps, (1..3).associate { it to EpisodeStatus.DONE })
        assertEquals(EpisodeCellKind.WATCHED, got[1])
        assertEquals(EpisodeCellKind.WATCHED, got[2])
        assertEquals(EpisodeCellKind.CURRENT, got[3])
    }

    @Test
    fun `稀疏记录 3 5 7 各自成段 看到这里是 7 下一集是 8`() {
        val eps = (1..10).map { ep(it, it.toDouble(), "2026-09-01") }
        val st = states(3 to EpisodeStatus.DONE, 5 to EpisodeStatus.DONE, 7 to EpisodeStatus.DONE)
        val got = kinds(eps, st)
        assertEquals(EpisodeCellKind.WATCHED, got[3])
        assertEquals(EpisodeCellKind.WATCHED, got[5])
        assertEquals(EpisodeCellKind.CURRENT, got[7])
        // 下一集在看到这里之后；1、2、4、6 是它之前的空洞，画成已播未看
        assertEquals(EpisodeCellKind.NEXT, got[8])
        assertEquals(EpisodeCellKind.AIRED, got[1])
        // 每行 5 列：第 0 行 = 1..5，第 1 行 = 6..10
        assertEquals(listOf(0 to EpisodeBand(2, 2), 0 to EpisodeBand(4, 4)), bands(eps, st).filter { it.first == 0 })
        assertEquals(listOf(1 to EpisodeBand(1, 1)), bands(eps, st).filter { it.first == 1 })
    }

    @Test
    fun `网页上标的想看与抛弃照样画出来 下一集与未播出优先`() {
        val eps = (1..6).map { ep(it, it.toDouble(), if (it <= 5) "2026-09-01" else "2026-10-20") }
        // 看到这里是 1；2 想看但正是下一集；3 想看；4 抛弃；6 未播出且想看
        val st = states(
            1 to EpisodeStatus.DONE,
            2 to EpisodeStatus.WISH,
            3 to EpisodeStatus.WISH,
            4 to EpisodeStatus.DROPPED,
            6 to EpisodeStatus.WISH,
        )
        val got = kinds(eps, st)
        assertEquals(EpisodeCellKind.NEXT, got[2])
        assertEquals(EpisodeCellKind.WISH, got[3])
        assertEquals(EpisodeCellKind.DROPPED, got[4])
        assertEquals(EpisodeCellKind.AIRED, got[5])
        assertEquals(EpisodeCellKind.UNAIRED, got[6])
    }

    @Test
    fun `今天播出且没看过的格子标小点 一个月内要播的写播出日`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-28"), // 今天，看过
            ep(2, 2.0, "2026-09-28"), // 今天，没看
            ep(3, 3.0, "2026-10-28"), // 30 天后
            ep(4, 4.0, "2026-10-29"), // 31 天后
            ep(5, 5.0, "2026-10-30"), // 32 天后
        )
        val cells = gridRows(eps, states(1 to EpisodeStatus.DONE)).flatMap { it.cells }.associateBy { it.episode.id }
        assertFalse("看过的不标", cells.getValue(1).airsToday)
        assertTrue(cells.getValue(2).airsToday)
        assertEquals(LocalDate.of(2026, 10, 28), cells.getValue(3).upcomingAirdate)
        assertEquals(LocalDate.of(2026, 10, 29), cells.getValue(4).upcomingAirdate)
        assertNull("再远的不写日期", cells.getValue(5).upcomingAirdate)
        assertNull("已播的不写日期", cells.getValue(2).upcomingAirdate)
        assertEquals("播出日照样带着，点了提示用", LocalDate.of(2026, 10, 30), cells.getValue(5).airdate)
        assertFalse("未播出的不能标", cells.getValue(5).markable)
        assertTrue(cells.getValue(2).markable)
    }

    @Test
    fun `分段导航条 每段看过几集`() {
        val eps = (1..250).map { ep(it, it.toDouble(), "2026-09-01") }
        // 第 130 集网页上标了想看：不算看过
        val states = (1..120).associateWith { EpisodeStatus.DONE } + (130 to EpisodeStatus.WISH)
        val grid = EpisodeTracking.episodeGrid(eps, states, today, selectedRange = 0, columns = columns)
        assertEquals(listOf(100, 20, 0), grid.rangeWatched)
    }

    @Test
    fun `连续看过合成一段`() {
        val eps = (1..10).map { ep(it, it.toDouble(), "2026-09-01") }
        val st = states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE, 3 to EpisodeStatus.DONE)
        // 第 0 行 1..5：看过 1、2、3 -> 一段 0..2；第 1 行无看过
        assertEquals(listOf(0 to EpisodeBand(0, 2)), bands(eps, st))
    }

    @Test
    fun `连段不跨行 每行各自成段`() {
        val eps = (1..10).map { ep(it, it.toDouble(), "2026-09-01") }
        val st = (1..7).associate { it to EpisodeStatus.DONE }
        // 第 0 行 1..5 全看过 -> 0..4；第 1 行 6、7 看过 -> 0..1
        assertEquals(listOf(0 to EpisodeBand(0, 4), 1 to EpisodeBand(0, 1)), bands(eps, st))
    }

    @Test
    fun `未知播出日按已播出显示`() {
        val eps = listOf(ep(1, 1.0, ""), ep(2, 2.0, "bad"), ep(3, 3.0, "2026-02-30"))
        val got = kinds(eps)
        // 未知播出日不是 UNAIRED（无从判断），可点；1 是下一集故 NEXT，其余 AIRED
        assertEquals(EpisodeCellKind.NEXT, got[1])
        assertEquals(EpisodeCellKind.AIRED, got[2])
        assertEquals(EpisodeCellKind.AIRED, got[3])
        assertFalse(got.containsValue(EpisodeCellKind.UNAIRED))
    }

    @Test
    fun `未播出只看已知且晚于今天的播出日`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-01"),   // 已播
            ep(2, 2.0, "2026-09-28"),   // 今天 -> 已播
            ep(3, 3.0, "2026-09-29"),   // 明天 -> 未播出
        )
        val got = kinds(eps)
        assertEquals(EpisodeCellKind.AIRED, got[2])
        assertEquals(EpisodeCellKind.UNAIRED, got[3])
    }

    @Test
    fun `第二季集号从 13 起 行与连段都按顺序切`() {
        val eps = (13..24).map { ep(it, it.toDouble(), "2026-09-01") }
        val rows = gridRows(eps, (13..20).associate { it to EpisodeStatus.DONE })
        // 12 集 / 5 列 = 3 行（5、5、2）
        assertEquals(listOf(5, 5, 2), rows.map { it.cells.size })
        assertEquals(listOf(13, 14, 15, 16, 17), rows[0].cells.map { it.episode.id })
        assertEquals(EpisodeCellKind.CURRENT, rows[1].cells.first { it.episode.id == 20 }.kind)
        // 第 2 行（21..22）没有看过 -> 无连段
        assertTrue(rows[2].bands.isEmpty())
    }

    @Test
    fun `一百集按每行五列切满二十行`() {
        val eps = (1..100).map { ep(it, it.toDouble(), "2026-09-01") }
        val rows = gridRows(eps, (1..100).associate { it to EpisodeStatus.DONE })
        assertEquals(20, rows.size)
        assertTrue(rows.all { it.cells.size == 5 })
        assertEquals(EpisodeCellKind.CURRENT, rows.last().cells.last().kind)
    }

    @Test
    fun `非正片不进网格`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(90, 1.5, "2026-09-01", type = EpisodeType.SPECIAL), ep(2, 2.0, "2026-09-01"))
        val rows = gridRows(eps)
        assertEquals(listOf(1, 2), rows.single().cells.map { it.episode.id })
    }

    @Test
    fun `空章节列表没有行`() {
        assertTrue(gridRows(emptyList()).isEmpty())
        assertTrue(gridRows(listOf(ep(90, 1.0, type = EpisodeType.SPECIAL))).isEmpty())
    }

    // ---------- EP 按钮状态 ----------

    @Test
    fun `EP 按钮 没有正片是空态`() {
        assertEquals(EpButtonState.Empty, EpisodeTracking.epButtonState(emptyList(), emptyMap(), today))
        assertEquals(EpButtonState.Empty, EpisodeTracking.epButtonState(listOf(ep(90, 1.0, type = EpisodeType.SPECIAL)), emptyMap(), today))
    }

    @Test
    fun `EP 按钮 全部看完是完结态`() {
        val eps = (1..3).map { ep(it, it.toDouble(), "2026-09-01") }
        assertEquals(EpButtonState.Finished, EpisodeTracking.epButtonState(eps, (1..3).associate { it to EpisodeStatus.DONE }, today))
    }

    @Test
    fun `EP 按钮 下一集已播出或播出日未知是可点态`() {
        val aired = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-09-10"))
        val next = EpisodeTracking.epButtonState(aired, emptyMap(), today)
        val nextState = assertIsInstance<EpButtonState.Next>(next)
        assertEquals(1, nextState.episode.id)

        // 未知播出日：不受播出日约束，直接可点
        val unknown = listOf(ep(1, 1.0, ""))
        val unknownNext = EpisodeTracking.epButtonState(unknown, emptyMap(), today)
        val unknownState = assertIsInstance<EpButtonState.Next>(unknownNext)
        assertEquals(1, unknownState.episode.id)
    }

    @Test
    fun `EP 按钮 下一集今天播出算可点 明天起才是等待态`() {
        // 「今天播出」= 已播出、可标记（用户当天看完最新一集就要能标上）
        val todayEp = listOf(ep(1, 1.0, "2026-09-28"))
        assertIsInstance<EpButtonState.Next>(EpisodeTracking.epButtonState(todayEp, emptyMap(), today))

        val cases = listOf(
            "2026-09-29" to 1L,   // 明天
            "2026-10-05" to 7L,   // 7 天后
        )
        cases.forEach { (airdate, days) ->
            val waiting = EpisodeTracking.epButtonState(listOf(ep(1, 1.0, airdate)), emptyMap(), today)
            val waitingState = assertIsInstance<EpButtonState.Waiting>(waiting, "播出日 $airdate 应为等待态")
            assertEquals(1, waitingState.episode.id)
            assertEquals(days, waitingState.daysUntil)
        }
    }

    @Test
    fun `EP 按钮只有未来集进入等待态且天数等于日期差`() {
        (-3..3).forEach { offset ->
            val airdate = today.plusDays(offset.toLong()).toString()
            val state = EpisodeTracking.epButtonState(listOf(ep(1, 1.0, airdate)), emptyMap(), today)
            if (offset > 0) {
                val waiting = assertIsInstance<EpButtonState.Waiting>(state, "$airdate 应进入等待态")
                assertEquals(offset.toLong(), waiting.daysUntil)
            } else {
                assertIsInstance<EpButtonState.Next>(state, "$airdate 应可标记")
            }
        }
    }

    @Test
    fun `EP 按钮 看完最新一集后下一集是未来的集才进等待态`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-10-05"))
        // 看完第 1 集：下一集是第 2 集，未来 -> 等待 7 天
        val waiting = EpisodeTracking.epButtonState(eps, states(1 to EpisodeStatus.DONE), today)
        val finishedWaiting = assertIsInstance<EpButtonState.Waiting>(waiting)
        assertEquals(2, finishedWaiting.episode.id)
        assertEquals(7L, finishedWaiting.daysUntil)
    }

    @Test
    fun `EP 按钮 等待态带上一集到这一集的间隔 倒计时环按它填满`() {
        // 周更：第 2 集 9-28 已播并看过，第 3 集 10-05 播出——间隔 7 天、还剩 7 天，环是空的
        val weekly = listOf(ep(1, 1.0, "2026-09-21"), ep(2, 2.0, "2026-09-28"), ep(3, 3.0, "2026-10-05"))
        val justAired = assertIsInstance<EpButtonState.Waiting>(
            EpisodeTracking.epButtonState(weekly, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE), today),
        )
        assertEquals(7L, justAired.intervalDays)
        assertEquals(0f, justAired.elapsedFraction)

        // 第 2 集 9-24 播出、第 3 集 9-30 播出：间隔 6 天、还剩 2 天，环满三分之二
        val nearly = listOf(ep(1, 1.0, "2026-09-17"), ep(2, 2.0, "2026-09-24"), ep(3, 3.0, "2026-09-30"))
        val soon = assertIsInstance<EpButtonState.Waiting>(
            EpisodeTracking.epButtonState(nearly, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE), today),
        )
        assertEquals(6L, soon.intervalDays)
        assertEquals(4f / 6f, soon.elapsedFraction, 1e-6f)
    }

    @Test
    fun `EP 按钮 第一集或上一集播出日未知时没有间隔 环只画轨道`() {
        val first = assertIsInstance<EpButtonState.Waiting>(
            EpisodeTracking.epButtonState(listOf(ep(1, 1.0, "2026-10-05")), emptyMap(), today),
        )
        assertEquals(null, first.intervalDays)
        assertEquals(0f, first.elapsedFraction)

        val unknownPrevious = assertIsInstance<EpButtonState.Waiting>(
            EpisodeTracking.epButtonState(listOf(ep(1, 1.0, ""), ep(2, 2.0, "2026-10-05")), states(1 to EpisodeStatus.DONE), today),
        )
        assertEquals(null, unknownPrevious.intervalDays)
    }

    // ---------- 播出状态（唯一判定） ----------

    @Test
    fun `今天播出算已播出 明天及以后算未播出 缺失算未知`() {
        assertEquals(AirState.AIRED, EpisodeTracking.airState(ep(1, 1.0, "2026-09-27"), today))  // 昨天
        assertEquals(AirState.AIRED, EpisodeTracking.airState(ep(2, 2.0, "2026-09-28"), today))  // 今天
        assertEquals(AirState.UNAIRED, EpisodeTracking.airState(ep(3, 3.0, "2026-09-29"), today)) // 明天
        assertEquals(AirState.UNKNOWN, EpisodeTracking.airState(ep(4, 4.0, ""), today))
        assertEquals(AirState.UNKNOWN, EpisodeTracking.airState(ep(5, 5.0, "2026-02-30"), today))
        assertEquals(AirState.UNKNOWN, EpisodeTracking.airState(ep(6, 6.0, "bad"), today))
    }

    @Test
    fun `未知播出日 各调用方处理不同且都写明了`() {
        val eps = listOf(ep(1, 1.0, ""))
        // 首页计数：保守不计入
        assertEquals(0, EpisodeTracking.airedUnwatchedCount(eps, emptyMap(), today))
        // 网格：当已播出（不是 UNAIRED）
        val kind = gridRows(eps).single().cells.single().kind
        assertEquals(EpisodeCellKind.NEXT, kind) // 1 是下一集，但**不是** UNAIRED
        // 按钮：可点（不是等待态）
        assertIsInstance<EpButtonState.Next>(EpisodeTracking.epButtonState(eps, emptyMap(), today))
        // 连载判定：不算连载
        assertFalse(EpisodeTracking.isOngoing(eps, today))
    }

    // ---------- 分段（>100 集） ----------

    private fun longSeries(count: Int) = (1..count).map { ep(it, it.toDouble(), "2026-09-01") }

    @Test
    fun `分段 超过一百集才分段 否则只有一段`() {
        assertEquals(1, EpisodeTracking.episodeRanges(longSeries(100)).size)
        assertEquals(2, EpisodeTracking.episodeRanges(longSeries(101)).size)
        assertEquals(12, EpisodeTracking.episodeRanges(longSeries(1140)).size)
        assertFalse("骨架预留导航条与真实分段同一判定", EpisodeTracking.splitsIntoRanges(100))
        assertTrue(EpisodeTracking.splitsIntoRanges(101))
    }

    @Test
    fun `默认段落在下一集所在段 全看完落最后一段`() {
        val mains = longSeries(250)
        val ranges = EpisodeTracking.episodeRanges(mains)
        assertEquals(0, EpisodeTracking.defaultRangeIndex(ranges, emptyMap()))
        // 看到 150 → 下一集是 151 → 第二段（下标 1）
        assertEquals(1, EpisodeTracking.defaultRangeIndex(ranges, (1..150).associate { it to EpisodeStatus.DONE }))
        // 全看完 → 最后一段
        assertEquals(2, EpisodeTracking.defaultRangeIndex(ranges, (1..250).associate { it to EpisodeStatus.DONE }))
    }

    /**
     * 核心回归：**CURRENT 必须按完整正片列表算**，不能按传入的某一段算。
     *
     * 海贼王看到 1090 集时，1–100 段里没有任何集是「看到这里」——
     * 若按段算，第 100 集会变成 CURRENT，用户点它就走退一集，把进度从 1090 退到 1089。
     */
    @Test
    fun `超过一百集时 第一段没有看到这里 但当前段有`() {
        val mains = longSeries(1140)
        val states = (1..1090).associate { it to EpisodeStatus.DONE }
        val firstKinds = EpisodeTracking.episodeGrid(mains, states, today, selectedRange = 0, columns = columns)
            .rows.flatMap { it.cells }.map { it.kind }
        assertFalse("1–100 段不得出现「看到这里」", firstKinds.contains(EpisodeCellKind.CURRENT))
        assertTrue("1–100 段应全部是看过", firstKinds.all { it == EpisodeCellKind.WATCHED })

        // 当前段（1001–1100）：第 1090 集是「看到这里」
        val currentCells = EpisodeTracking.episodeGrid(mains, states, today, selectedRange = 10, columns = columns)
            .rows.flatMap { it.cells }
        val current = currentCells.filter { it.kind == EpisodeCellKind.CURRENT }
        assertEquals(1, current.size)
        assertEquals(1090, current.single().episode.sort.toInt())

        // 下一集（1091）也必须是 NEXT，同样按全局算
        assertEquals(
            EpisodeCellKind.NEXT,
            currentCells.single { it.episode.sort.toInt() == 1091 }.kind,
        )
    }

    // ---------- 退一集 ----------

    @Test
    fun `退一集 退到下一集之前那一集`() {
        val eps = (1..5).map { ep(it, it.toDouble(), "2026-09-01") }
        // 看到 3（看到这里是 3，下一集是 4）→ 退一集落到 2
        assertEquals(2, EpisodeTracking.revertTarget(eps, (1..3).associate { it to EpisodeStatus.DONE })?.id)
    }

    @Test
    fun `退一集 退到看到这里的前一集 稀疏记录也一样`() {
        val eps = (1..10).map { ep(it, it.toDouble(), "2026-09-01") }
        // 标过 3、5、7：看到这里是 7 → 退到第 6 集
        assertEquals(6, EpisodeTracking.revertTarget(eps, states(3 to EpisodeStatus.DONE, 5 to EpisodeStatus.DONE, 7 to EpisodeStatus.DONE))?.id)
        // 标过 1、2、5：看到这里是 5 → 退到第 4 集
        assertEquals(4, EpisodeTracking.revertTarget(eps, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE, 5 to EpisodeStatus.DONE))?.id)
    }

    @Test
    fun `退一集 第一集或空列表时清空`() {
        val eps = (1..3).map { ep(it, it.toDouble(), "2026-09-01") }
        assertNull(EpisodeTracking.revertTarget(eps, states(1 to EpisodeStatus.DONE)))
        assertNull(EpisodeTracking.revertTarget(emptyList(), emptyMap()))
        // 全看完：看到这里是第 3 集 → 退到第 2 集
        assertEquals(2, EpisodeTracking.revertTarget(eps, (1..3).associate { it to EpisodeStatus.DONE })?.id)
    }

    // ---------- 点一格的意图 ----------

    @Test
    fun `点一格 往前标几集 超过十五集才要确认`() {
        val eps = (1..40).map { ep(it, it.toDouble(), "2026-09-01") }
        val st = (1..5).associateWith { EpisodeStatus.DONE }
        val near = EpisodeTracking.tapOf(eps, st, eps[19]) // 第 20 集：标上 6–20
        assertTap(EpisodeProgressTarget.Episode(20, 20.0), forward = true, filled = 15, cleared = 0, completes = false, tap = near)
        assertFalse("15 集以内一点即成", near.needsConfirm)
        val far = EpisodeTracking.tapOf(eps, st, eps[20]) // 第 21 集：标上 16 集
        assertEquals(16, far.filled)
        assertTrue(far.needsConfirm)
    }

    @Test
    fun `点一格 往回退 点看到这里是退一集`() {
        val eps = (1..40).map { ep(it, it.toDouble(), "2026-09-01") }
        val st = (1..30).associateWith { EpisodeStatus.DONE }
        val back = EpisodeTracking.tapOf(eps, st, eps[9]) // 第 10 集：取消 11–30
        assertTap(EpisodeProgressTarget.Episode(10, 10.0), forward = false, filled = 0, cleared = 20, completes = false, tap = back)
        assertTrue(back.needsConfirm)
        val revert = EpisodeTracking.tapOf(eps, st, eps[29]) // 点看到这里（第 30 集）：退到第 29 集
        assertTap(EpisodeProgressTarget.Episode(29, 29.0), forward = false, filled = 0, cleared = 1, completes = false, tap = revert)
        assertFalse(revert.needsConfirm)
        val clear = EpisodeTracking.tapOf(eps, states(1 to EpisodeStatus.DONE), eps[0]) // 只看了第 1 集，点它：清空
        assertTap(EpisodeProgressTarget.Empty, forward = false, filled = 0, cleared = 1, completes = false, tap = clear)
    }

    @Test
    fun `点一格 sort 为零的真实正片 再点会清空而不是留在原处`() {
        val eps = listOf(
            ep(1, 0.0, "2026-09-01"),
            ep(2, 1.0, "2026-09-08"),
        )

        val clear = EpisodeTracking.tapOf(eps, states(1 to EpisodeStatus.DONE), eps[0])
        assertEquals(EpisodeProgressTarget.Empty, clear.target)
        assertEquals(1, clear.cleared)
    }

    @Test
    fun `点一格 sort 为零的真实正片 首次点击把它标上看过`() {
        val eps = listOf(
            ep(1, 0.0, "2026-09-01"),
            ep(2, 1.0, "2026-09-08"),
        )

        val tap = EpisodeTracking.tapOf(eps, emptyMap(), eps[0])
        assertEquals(EpisodeProgressTarget.Episode(eps[0].id, eps[0].sort), tap.target)
        assertEquals(1, tap.filled)
    }

    @Test
    fun `按锚点写状态 sort 为零的真实正片会被标上看过 显式空锚点才会清空`() {
        val eps = listOf(
            ep(1, 0.0, "2026-09-01"),
            ep(2, 1.0, "2026-09-08"),
        )

        assertEquals(
            mapOf(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.NONE),
            EpisodeTracking.statesAfterWatchedUpTo(eps, emptyMap(), EpisodeProgressTarget.Episode(eps[0].id, eps[0].sort)),
        )
        assertEquals(
            mapOf(1 to EpisodeStatus.NONE, 2 to EpisodeStatus.NONE),
            EpisodeTracking.statesAfterWatchedUpTo(eps, states(1 to EpisodeStatus.DONE), EpisodeProgressTarget.Empty),
        )
    }

    @Test
    fun `目标集不在正片列表时没有结果 不把已看正片当成空进度清空`() {
        val episodes = listOf(ep(10, 1.0, "2026-10-01"), ep(20, 2.0, "2026-10-08"))
        val watched = states(10 to EpisodeStatus.DONE)

        assertNull("同 sort 的正片还在也不按 sort 猜", EpisodeTracking.statesAfterWatchedUpTo(episodes, watched, EpisodeProgressTarget.Episode(999, 1.0)))
        assertNull(EpisodeTracking.statesAfterWatchedUpTo(episodes, watched, EpisodeProgressTarget.Episode(999, 99.0)))
    }

    @Test
    fun `重复 sort 的正片按正片列表位置定位 不按数值猜前后`() {
        val eps = listOf(
            ep(1, 1.0),
            ep(2, 1.0),
            ep(3, 2.0),
        )

        assertEquals(
            mapOf(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE, 3 to EpisodeStatus.NONE),
            EpisodeTracking.statesAfterWatchedUpTo(eps, emptyMap(), EpisodeProgressTarget.Episode(2, 1.0)),
        )
        assertEquals(
            mapOf(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.NONE, 3 to EpisodeStatus.NONE),
            EpisodeTracking.statesAfterWatchedUpTo(eps, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE), EpisodeProgressTarget.Episode(1, 1.0)),
        )
    }

    @Test
    fun `点一格 改动集数与写入同口径 空洞补上的也算`() {
        val eps = (1..10).map { ep(it, it.toDouble(), "2026-09-01") }
        // 看过 1、5，2 想看：看到第 6 集要把 2、3、4、6 标成看过
        val st = states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.WISH, 5 to EpisodeStatus.DONE)
        assertTap(EpisodeProgressTarget.Episode(6, 6.0), forward = true, filled = 4, cleared = 0, completes = false, tap = EpisodeTracking.tapOf(eps, st, eps[5]))
    }

    @Test
    fun `点一格 跳着看过时往回退 退到的那一集之前补上 合计超过十五集也要确认`() {
        val eps = (1..40).map { ep(it, it.toDouble(), "2026-09-01") }
        // 看过 1–3 和 30：看到这里是 30，点它退到 29——4–29 补上、30 取消
        val st = ((1..3) + 30).associateWith { EpisodeStatus.DONE }
        val revert = EpisodeTracking.tapOf(eps, st, eps[29])
        assertTap(EpisodeProgressTarget.Episode(29, 29.0), forward = false, filled = 26, cleared = 1, completes = false, tap = revert)
        assertTrue(revert.needsConfirm)
    }

    @Test
    fun `点一格 看完的那一下 completes`() {
        val eps = (1..12).map { ep(it, it.toDouble(), "2026-09-01") }
        val all = (1..12).associateWith { EpisodeStatus.DONE }
        assertTrue("看到 11 集时点第 12 集", EpisodeTracking.tapOf(eps, (1..11).associateWith { EpisodeStatus.DONE }, eps[11]).completes)
        assertTrue(
            "跳着看过、网页上标了想看的那一集也一并标上",
            EpisodeTracking.tapOf(eps, (1..5).associateWith { EpisodeStatus.DONE } + (8 to EpisodeStatus.WISH), eps[11]).completes,
        )
        assertFalse("早已看完：点看到这里是退一集", EpisodeTracking.tapOf(eps, all, eps[11]).completes)
        assertFalse("往回退", EpisodeTracking.tapOf(eps, (1..11).associateWith { EpisodeStatus.DONE }, eps[5]).completes)
        assertFalse("没到最后一集", EpisodeTracking.tapOf(eps, (1..5).associateWith { EpisodeStatus.DONE }, eps[10]).completes)
    }

    @Test
    fun `点一格的逐集变化 看过与否会变的才算 想看与抛弃在看到的范围外原样留着`() {
        val eps = (3..8).map { ep(it, it.toDouble()) }
        // 3 没记录、4 想看、5 看过、6 抛弃、7 看过、8 没记录：点第 5 集是看到第 5 集
        val st = states(4 to EpisodeStatus.WISH, 5 to EpisodeStatus.DONE, 6 to EpisodeStatus.DROPPED, 7 to EpisodeStatus.DONE)
        assertEquals(
            mapOf(3 to WatchChange.FILL, 4 to WatchChange.FILL, 7 to WatchChange.CLEAR),
            EpisodeTracking.tapOf(eps, st, eps[2]).changes,
        )
    }

    @Test
    fun `点分段第一集的看到这里 退到上一段的最后一集 逐集变化按全部正片算`() {
        val eps = (1..150).map { ep(it, it.toDouble(), "2026-09-01") }
        val st = (1..101).associateWith { EpisodeStatus.DONE }
        val revert = EpisodeTracking.tapOf(eps, st, eps[100])
        assertTap(EpisodeProgressTarget.Episode(100, 100.0), forward = false, filled = 0, cleared = 1, completes = false, tap = revert)
        assertEquals(mapOf(101 to WatchChange.CLEAR), revert.changes)
    }

    private fun assertTap(target: EpisodeProgressTarget, forward: Boolean, filled: Int, cleared: Int, completes: Boolean, tap: EpisodeTap) {
        assertEquals(target, tap.target)
        assertEquals(forward, tap.forward)
        assertEquals(filled, tap.filled)
        assertEquals(cleared, tap.cleared)
        assertEquals(completes, tap.completes)
    }

    // ---------- 章节 tab 顶上那一行 ----------

    @Test
    fun `摘要 看到第几集 连载中带已播集数`() {
        val eps = (1..12).map { ep(it, it.toDouble(), "2026-09-%02d".format(it * 2)) } // 9 月 2 日到 24 日播完
        val ongoing = (1..12).map { ep(it, it.toDouble(), if (it <= 7) "2026-09-01" else "2026-10-%02d".format(it)) }
        val summary = EpisodeTracking.episodeSummary(ongoing, (1..5).associateWith { EpisodeStatus.DONE }, today)
        assertEquals(5, summary.current?.id)
        assertEquals(12, summary.total)
        assertEquals(EpisodeAiring.Ongoing(aired = 7), summary.airing)
        assertFalse(summary.allWatched)
        assertEquals(EpisodeAiring.Finished, EpisodeTracking.episodeSummary(eps, emptyMap(), today).airing)
    }

    @Test
    fun `摘要 播出日不全且没有要播的写不出播出 全部看完才算看完`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, ""))
        val none = EpisodeTracking.episodeSummary(eps, emptyMap(), today)
        assertEquals(EpisodeAiring.Unknown, none.airing)
        assertNull(none.current)
        assertTrue(EpisodeTracking.episodeSummary(eps, states(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.DONE), today).allWatched)
        assertFalse("没有正片不算看完", EpisodeTracking.episodeSummary(emptyList(), emptyMap(), today).allWatched)
    }

    @Test
    fun `看完正片只看看到的是不是最后一集 之前的空洞不算 一集都没看不算`() {
        val eps = listOf(ep(1, 1.0), ep(2, 2.0), ep(3, 3.0), ep(9, 0.5, type = EpisodeType.SPECIAL))
        assertTrue("看到最后一集、第 2 集空着也算看完", EpisodeTracking.watchedAllMains(eps, eps[2]))
        assertTrue(EpisodeTracking.episodeSummary(eps, states(1 to EpisodeStatus.DONE, 3 to EpisodeStatus.DONE), today).allWatched)
        assertFalse(EpisodeTracking.watchedAllMains(eps, eps[1]))
        assertFalse("一集都没看", EpisodeTracking.watchedAllMains(eps, null))
        assertFalse("特别篇不是正片的最后一集", EpisodeTracking.watchedAllMains(eps, eps[3]))
    }

    // ---------- 集号格式化与类型标签 ----------

    @Test
    fun `集号格式化 整数不带小数点 小数保留`() {
        assertEquals("12", formatEpisodeSort(12.0))
        assertEquals("12.5", formatEpisodeSort(12.5))
        assertEquals("1", formatEpisodeSort(1.0))
        assertEquals("1090", formatEpisodeSort(1090.0))
    }

    @Test
    fun `集类型取值收齐接口的七个 正片只有本篇`() {
        // 依据 tools/openapi/generated/p1.bundled.json 的 EpisodeType 枚举
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), EpisodeType.ALL)
        assertTrue(EpisodeType.isKnown(EpisodeType.NORMAL))
        assertTrue(EpisodeType.isKnown(EpisodeType.PRE))
        assertTrue(EpisodeType.isKnown(EpisodeType.MAD))
        assertTrue(EpisodeType.isKnown(EpisodeType.OTHER))
        // 只有 0 是正片；4/5/6 都不参与进度
        assertTrue(EpisodeType.isMain(EpisodeType.NORMAL))
        listOf(EpisodeType.SPECIAL, EpisodeType.OP, EpisodeType.ED, EpisodeType.PRE, EpisodeType.MAD, EpisodeType.OTHER)
            .forEach { assertFalse("type=$it 不该算正片", EpisodeType.isMain(it)) }
    }

    @Test
    fun `接口没给的集类型不算已知`() {
        // 脏数据（99）不回落成 SP：未知类型不冒充特别篇
        assertFalse(EpisodeType.isKnown(99))
        assertFalse(EpisodeType.isKnown(-1))
    }

    @Test
    fun `预告 MAD 其他不算正片 不进网格 但进特别篇列表`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-01"),
            ep(90, 1.0, "2026-09-01", type = EpisodeType.PRE),
            ep(91, 1.0, "2026-09-01", type = EpisodeType.MAD),
            ep(92, 1.0, "2026-09-01", type = EpisodeType.OTHER),
        )
        assertEquals(listOf(1), EpisodeTracking.mainEpisodes(eps).map { it.id })
        assertEquals(listOf(90, 91, 92), EpisodeTracking.specialEpisodes(eps).map { it.id })
        // 4/5/6 与 1/2/3 一样不进网格
        assertEquals(listOf(1), gridRows(eps).single().cells.map { it.episode.id })
    }

    // ---------- 特别篇：分组、格子样子、单集切换 ----------

    @Test
    fun `特别篇按类型分组 组序按接口类型 组内按集号 未知类型不列`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-01"),
            ep(31, 2.0, "2026-09-01", type = EpisodeType.ED),
            ep(21, 2.0, "2026-09-01", type = EpisodeType.SPECIAL),
            ep(20, 1.0, "2026-09-01", type = EpisodeType.SPECIAL),
            ep(30, 1.0, "2026-09-01", type = EpisodeType.OP),
            ep(99, 1.0, "2026-09-01", type = 99),
        )
        val groups = EpisodeTracking.specialGroups(eps)
        assertEquals(listOf(EpisodeType.SPECIAL, EpisodeType.OP, EpisodeType.ED), groups.map { it.first })
        assertEquals(listOf(20, 21), groups.first().second.map { it.id })
        assertTrue("正片与未知类型不进特别篇", groups.none { (_, list) -> list.any { it.id == 99 || it.id == 1 } })
    }

    @Test
    fun `章节方格有格子可画 正片或已知类型的特别篇都算 一集没有或只有未知类型不算`() {
        assertTrue(EpisodeTracking.hasTiles(listOf(ep(1, 1.0))))
        assertTrue("只有特别篇也有格子", EpisodeTracking.hasTiles(listOf(ep(20, 1.0, type = EpisodeType.SPECIAL))))
        assertFalse(EpisodeTracking.hasTiles(emptyList()))
        assertFalse("未知类型不画", EpisodeTracking.hasTiles(listOf(ep(99, 1.0, type = 99))))
    }

    @Test
    fun `特别篇格子 看过优先 其次按播出日 网页上标的想看照样显示`() {
        val watched = ep(20, 1.0, "2026-12-01", type = EpisodeType.SPECIAL)
        val future = ep(21, 2.0, "2026-12-01", type = EpisodeType.SPECIAL)
        val aired = ep(22, 3.0, "2026-09-28", type = EpisodeType.SPECIAL)
        val unknown = ep(23, 4.0, "", type = EpisodeType.SPECIAL)
        val states = mapOf(20 to EpisodeStatus.DONE, 22 to EpisodeStatus.WISH)
        assertEquals(EpisodeCellKind.WATCHED, EpisodeTracking.specialCell(watched, states, today).kind)
        assertEquals(EpisodeCellKind.UNAIRED, EpisodeTracking.specialCell(future, states, today).kind)
        assertEquals("今天播出算已播，想看照样显示", EpisodeCellKind.WISH, EpisodeTracking.specialCell(aired, states, today).kind)
        assertTrue("今天播出、没看过：标小点", EpisodeTracking.specialCell(aired, states, today).airsToday)
        assertEquals("播出日未知按已播", EpisodeCellKind.AIRED, EpisodeTracking.specialCell(unknown, states, today).kind)
    }

    @Test
    fun `点特别篇 看过与无记录之间切换`() {
        assertEquals(EpisodeStatus.NONE, EpisodeTracking.specialToggleTarget(EpisodeStatus.DONE))
        assertEquals(EpisodeStatus.DONE, EpisodeTracking.specialToggleTarget(null))
        assertEquals(EpisodeStatus.DONE, EpisodeTracking.specialToggleTarget(EpisodeStatus.NONE))
        assertEquals(EpisodeStatus.DONE, EpisodeTracking.specialToggleTarget(EpisodeStatus.WISH))
        assertEquals(EpisodeStatus.DONE, EpisodeTracking.specialToggleTarget(EpisodeStatus.DROPPED))
    }

    // ---------- 下一个未来播出正片与时间表分组 ----------

    @Test
    fun `下一个未来播出正片忽略过去集未知集与SP`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-20"),
            ep(89, 1.2, ""),
            ep(90, 1.5, "2026-09-29", type = EpisodeType.SPECIAL),
            ep(2, 2.0, "2026-09-28"),
            ep(3, 3.0, "2026-10-05"),
        )
        val next = EpisodeTracking.nextUpcomingEpisode(eps, today)
        assertEquals(2, next?.id)
        assertEquals(today, EpisodeTracking.nextUpcomingAirdate(eps, today))
    }

    @Test
    fun `今天没有播出时取下一集正片的播出日`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-20"),
            ep(2, 2.0, "2026-09-29"),
            ep(3, 3.0, "2026-10-05"),
        )
        val next = EpisodeTracking.nextUpcomingEpisode(eps, today)
        assertEquals(2, next?.id)
        assertEquals(java.time.LocalDate.parse("2026-09-29"), EpisodeTracking.nextUpcomingAirdate(eps, today))
    }

    @Test
    fun `全部正片已播出时下一个未来播出正片为null`() {
        val eps = listOf(ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-09-20"))
        assertNull(EpisodeTracking.nextUpcomingEpisode(eps, today))
        assertNull(EpisodeTracking.nextUpcomingAirdate(eps, today))
    }

    // ---------- 完整性判定 ----------

    @Test
    fun `完整性判定返回正片数达到总集数且总数不超过72判为完整`() {
        val eps = (1..24).map { ep(it, it.toDouble(), "2026-09-01") }
        assertTrue(EpisodeTracking.isEpisodeListComplete(SubjectType.ANIME, 24, eps))
    }

    @Test
    fun `完整性判定总集数未知判为不完整走回退`() {
        val eps = (1..12).map { ep(it, it.toDouble(), "2026-09-01") }
        assertFalse(EpisodeTracking.isEpisodeListComplete(SubjectType.ANIME, 0, eps))
        assertFalse(EpisodeTracking.isEpisodeListComplete(SubjectType.REAL, -1, eps))
    }

    @Test
    fun `完整性判定返回总数超过72截断判为不完整走回退`() {
        val longEps = (1..73).map { ep(it, it.toDouble(), "2026-09-01") }
        assertFalse(EpisodeTracking.isEpisodeListComplete(SubjectType.ANIME, 73, longEps))
        assertFalse(EpisodeTracking.isEpisodeListComplete(SubjectType.ANIME, 100, longEps))
    }

    @Test
    fun `完整性判定返回正片数不足总集数判为不完整走回退`() {
        val partial = (1..6).map { ep(it, it.toDouble(), "2026-09-01") }
        assertFalse(EpisodeTracking.isEpisodeListComplete(SubjectType.ANIME, 12, partial))
    }

    @Test
    fun `完整性判定书籍游戏音乐不用章节恒为完整不触发回退`() {
        for (type in listOf(SubjectType.BOOK, SubjectType.GAME, SubjectType.MUSIC)) {
            assertTrue(EpisodeTracking.isEpisodeListComplete(type, 0, emptyList()))
        }
    }

    @Test
    fun `完整性判定截断窗口恰含全部正片且不超过72判为完整`() {
        // 正片 12 集 + 大量 SP：服务端按 sort 取窗口，窗口含全部正片即可——进度判定只看正片
        val window = (1..12).map { ep(it, it.toDouble()) } + (100..150).map { ep(it, (it - 99).toDouble(), type = EpisodeType.SPECIAL) }
        assertTrue(EpisodeTracking.isEpisodeListComplete(SubjectType.ANIME, 12, window))
    }

    @Test
    fun `逐集推进的类型只有动画与三次元`() {
        assertTrue(EpisodeTracking.tracksEpisodes(SubjectType.ANIME))
        assertTrue(EpisodeTracking.tracksEpisodes(SubjectType.REAL))
        for (type in listOf(SubjectType.BOOK, SubjectType.GAME, SubjectType.MUSIC, SubjectType.UNKNOWN)) {
            assertFalse(EpisodeTracking.tracksEpisodes(type))
        }
    }

    // ---------- 已播到 ----------

    @Test
    fun `已播到取已播出正片里sort最大的一集`() {
        val eps = listOf(
            ep(1, 1.0, "2026-09-01"), ep(2, 2.0, "2026-09-28"), ep(3, 3.0, "2026-10-05"),
            ep(9, 2.5, "2026-09-10", type = EpisodeType.SPECIAL), ep(4, 4.0, ""),
        )
        assertEquals(2, EpisodeTracking.lastAiredEpisode(eps, today)?.id)
        assertNull(EpisodeTracking.lastAiredEpisode(listOf(ep(3, 3.0, "2026-10-05")), today))
        assertNull(EpisodeTracking.lastAiredEpisode(emptyList(), today))
    }

    // ---------- 书籍单位与EP按钮状态 ----------

    @Test
    fun `书籍单位判定vol大于0且ep为0用卷否则用话`() {
        assertEquals(BookUnit.VOL, bookUnit(epStatus = 0, volStatus = 5))
        assertEquals(BookUnit.EP, bookUnit(epStatus = 12, volStatus = 0))
        assertEquals(BookUnit.EP, bookUnit(epStatus = 0, volStatus = 0))
        assertEquals(BookUnit.EP, bookUnit(epStatus = 12, volStatus = 3))
    }

    @Test
    fun `书籍EP按钮状态产生BookNext未完结与Finished完结态`() {
        fun book(ep: Int, vol: Int, totalEps: Int = 0, totalVols: Int = 0) = EpisodeTracking.epButtonState(
            SubjectType.BOOK, emptyList(), emptyMap(), today, ep, vol, totalEps, totalVols,
        )
        assertEquals(EpButtonState.BookNext(10, BookUnit.EP), book(10, 0, totalEps = 12))
        assertEquals(EpButtonState.Finished, book(12, 0, totalEps = 12))
        assertEquals(EpButtonState.BookNext(3, BookUnit.VOL), book(0, 3, totalVols = 5))
        assertEquals(EpButtonState.Finished, book(0, 5, totalVols = 5))
    }

    @Test
    fun `书籍指定单位时不走自动判定：话数非 0 也按卷计与前进`() {
        // 自动判定在这组数上会选「话」（epStatus > 0），指定卷后计数与按钮都只看卷
        assertEquals(BookUnit.EP, bookUnit(epStatus = 30, volStatus = 3))
        assertEquals(
            ProgressCount.Known(BookUnit.VOL, current = 3, total = 14),
            EpisodeTracking.progressCount(
                SubjectType.BOOK, null, null, epStatus = 30, volStatus = 3, totalEps = 120, totalVols = 14,
                bookUnitOverride = BookUnit.VOL,
                collectionType = CollectionType.DOING,
            ),
        )
        assertEquals(
            EpButtonState.BookNext(3, BookUnit.VOL),
            EpisodeTracking.epButtonState(SubjectType.BOOK, emptyList(), emptyMap(), today, 30, 3, 120, 14, BookUnit.VOL),
        )
        assertEquals(
            EpButtonState.Finished,
            EpisodeTracking.epButtonState(SubjectType.BOOK, emptyList(), emptyMap(), today, 30, 14, 120, 14, BookUnit.VOL),
        )
        // 指定话也照办（卷数非 0、话数为 0 时自动判定会选卷）
        assertEquals(
            EpButtonState.BookNext(0, BookUnit.EP),
            EpisodeTracking.epButtonState(SubjectType.BOOK, emptyList(), emptyMap(), today, 0, 3, 120, 14, BookUnit.EP),
        )
    }

    @Test
    fun `进度计数六大场景覆盖`() {
        // ① 正片+特典混合（EVA 17 正片 + 3 特典，总共 26 正片 + 4 特典）：特典一律不计入分子与分母
        val evaMainEpisodes = (1..26).map { ep(it, it.toDouble(), type = EpisodeType.NORMAL) }
        val evaSpecialEpisodes = (27..30).map { ep(it, (it - 26).toDouble(), type = EpisodeType.SPECIAL) }
        val evaAllEpisodes = evaMainEpisodes + evaSpecialEpisodes
        val evaWatchStates = ((1..17) + listOf(27, 28, 29)).associateWith { EpisodeStatus.DONE }
        val evaCount = EpisodeTracking.progressCount(
            subjectType = SubjectType.ANIME,
            episodes = evaAllEpisodes,
            watchStates = evaWatchStates,
            collectionType = CollectionType.DOING,
        )
        assertEquals(ProgressCount.Known(unit = null, current = 17, total = 26), evaCount)

        // ② 分季条目（第 13–24 集，看过 8 集）
        val s2Episodes = (13..24).map { ep(it, it.toDouble(), type = EpisodeType.NORMAL) }
        val s2WatchStates = (13..20).associateWith { EpisodeStatus.DONE }
        val s2Count = EpisodeTracking.progressCount(
            subjectType = SubjectType.ANIME,
            episodes = s2Episodes,
            watchStates = s2WatchStates,
            collectionType = CollectionType.DOING,
        )
        assertEquals(ProgressCount.Known(unit = null, current = 8, total = 12), s2Count)

        // ③ 逐集表为 null（章节未返回或逐集状态未到）：返回 Loading
        assertEquals(
            ProgressCount.Loading,
            EpisodeTracking.progressCount(SubjectType.ANIME, episodes = null, watchStates = null, collectionType = CollectionType.DOING),
        )
        assertEquals(
            ProgressCount.Loading,
            EpisodeTracking.progressCount(SubjectType.REAL, episodes = null, watchStates = null, collectionType = CollectionType.DOING),
        )
        assertEquals(
            ProgressCount.Loading,
            EpisodeTracking.progressCount(SubjectType.ANIME, episodes = evaAllEpisodes, watchStates = null, collectionType = CollectionType.DOING),
        )
        assertEquals(
            ProgressCount.Loading,
            EpisodeTracking.progressCount(SubjectType.ANIME, episodes = null, watchStates = evaWatchStates, collectionType = CollectionType.DOING),
        )

        // ③″ 在看、逐集表没到位而这一轮请求失败了：不再等，回落到收藏记录的 epStatus / totalEps
        assertEquals(
            ProgressCount.Known(unit = null, current = 5, total = 12),
            EpisodeTracking.progressCount(
                SubjectType.ANIME, episodes = evaAllEpisodes, watchStates = null, epStatus = 5, totalEps = 12,
                collectionType = CollectionType.DOING, loadFailed = true,
            ),
        )
        // 失败过、逐集表后来到齐了：照常按逐集数
        assertEquals(
            ProgressCount.Known(unit = null, current = 17, total = 26),
            EpisodeTracking.progressCount(
                SubjectType.ANIME, evaAllEpisodes, evaWatchStates, epStatus = 3,
                collectionType = CollectionType.DOING, loadFailed = true,
            ),
        )

        // ③′ 不拉逐集表的收藏状态（想看/看过/搁置/抛弃）：逐集表为 null 时取收藏记录的 epStatus，不停在 Loading
        listOf(CollectionType.WISH, CollectionType.DONE, CollectionType.ON_HOLD, CollectionType.DROPPED).forEach { type ->
            assertEquals(
                ProgressCount.Known(unit = null, current = 5, total = 12),
                EpisodeTracking.progressCount(
                    SubjectType.ANIME, episodes = null, watchStates = null, epStatus = 5, totalEps = 12, collectionType = type,
                ),
            )
        }
        // 逐集表已在手（如统计用本地缓存）时仍按逐集数，不论收藏状态
        assertEquals(
            ProgressCount.Known(unit = null, current = 17, total = 26),
            EpisodeTracking.progressCount(
                SubjectType.ANIME, evaAllEpisodes, evaWatchStates, epStatus = 3, collectionType = CollectionType.DONE,
            ),
        )

        // ④ 逐集表为空列表（已返回但确实零集）：返回 Known(0, 0)
        assertEquals(
            ProgressCount.Known(unit = null, current = 0, total = 0),
            EpisodeTracking.progressCount(SubjectType.ANIME, episodes = emptyList(), watchStates = emptyMap(), collectionType = CollectionType.DOING),
        )

        // ⑤ 书籍话/卷：按 bookUnit 判定，用卷或用话
        assertEquals(
            ProgressCount.Known(BookUnit.EP, current = 10, total = 12),
            EpisodeTracking.progressCount(SubjectType.BOOK, null, null, epStatus = 10, volStatus = 0, totalEps = 12, totalVols = 3, collectionType = CollectionType.DOING),
        )
        assertEquals(
            ProgressCount.Known(BookUnit.VOL, current = 2, total = 3),
            EpisodeTracking.progressCount(SubjectType.BOOK, null, null, epStatus = 0, volStatus = 2, totalEps = 12, totalVols = 3, collectionType = CollectionType.DOING),
        )

        // ⑥ 游戏音乐无进度：返回 None，一个像素都不占
        assertEquals(
            ProgressCount.None,
            EpisodeTracking.progressCount(SubjectType.GAME, null, null, collectionType = CollectionType.DOING),
        )
        assertEquals(
            ProgressCount.None,
            EpisodeTracking.progressCount(SubjectType.MUSIC, null, null, collectionType = CollectionType.DOING),
        )
    }

    @Test
    fun `硬性关系验证：按钮上的数字就是那一集在正片里的序号`() {
        val total = 12
        val eps = (1..total).map { ep(it, it.toDouble(), "2026-09-01") }

        // 密集推进：从 0 到 total-1，看到 N 时按钮指向第 N+1 个正片
        for (watchedCount in 0 until total) {
            val states = (1..watchedCount).associateWith { EpisodeStatus.DONE }
            val count = EpisodeTracking.progressCount(SubjectType.ANIME, eps, states, collectionType = CollectionType.DOING)
            assertEquals(ProgressCount.Known(null, watchedCount, total), count)

            val button = EpisodeTracking.epButtonState(eps, states, today) as EpButtonState.Next
            val expectedNextSort = eps[watchedCount].sort
            assertEquals(
                "看过 $watchedCount 集时，按钮必须指向第 ${watchedCount + 1} 个正片的 sort ($expectedNextSort)",
                expectedNextSort,
                button.episode.sort,
                0.0,
            )
            // 按钮上显示的数字是序号，不是 sort。顺序推进时它恰好等于看过数 + 1。
            assertEquals(watchedCount + 1, button.position)
        }

        // 全部看过时，按钮为 Finished
        val allWatchedStates = (1..total).associateWith { EpisodeStatus.DONE }
        val countFinished = EpisodeTracking.progressCount(SubjectType.ANIME, eps, allWatchedStates, collectionType = CollectionType.DOING)
        assertEquals(ProgressCount.Known(null, total, total), countFinished)
        assertEquals(EpButtonState.Finished, EpisodeTracking.epButtonState(eps, allWatchedStates, today))

        // 分季条目：第 78–85 集（8 集），看过 6 集（78..83）
        val seasonEps = (78..85).map { ep(it, it.toDouble(), "2026-09-01") }
        val seasonStates = (78..83).associateWith { EpisodeStatus.DONE }
        val seasonCount = EpisodeTracking.progressCount(SubjectType.ANIME, seasonEps, seasonStates, collectionType = CollectionType.DOING)
        assertEquals(ProgressCount.Known(null, 6, 8), seasonCount)
        val seasonButton = EpisodeTracking.epButtonState(seasonEps, seasonStates, today) as EpButtonState.Next
        assertEquals(
            "分季条目看过 6 / 8 集，按钮必须是第 7 个正片即 sort 84",
            84.0,
            seasonButton.episode.sort,
            0.0,
        )
        // 按钮上显示 7（本条目第 7 集），不是系列绝对集号 84——与行里「看过 6 / 8 集」同一个坐标系。
        assertEquals(7, seasonButton.position)

        // EVA：17 正片 + 3 特典看过，按钮必须指向第 18 个正片（sort = 18.0）
        val evaMain = (1..26).map { ep(it, it.toDouble(), "2026-09-01") }
        val evaSpecial = (27..30).map { ep(it, (it - 26).toDouble(), type = EpisodeType.SPECIAL) }
        val evaAll = evaMain + evaSpecial
        val evaStates = ((1..17) + listOf(27, 28, 29)).associateWith { EpisodeStatus.DONE }
        val evaProg = EpisodeTracking.progressCount(SubjectType.ANIME, evaAll, evaStates, collectionType = CollectionType.DOING)
        assertEquals(ProgressCount.Known(null, 17, 26), evaProg)
        val evaButton = EpisodeTracking.epButtonState(evaAll, evaStates, today) as EpButtonState.Next
        assertEquals(
            "EVA 看过 17 / 26 集，按钮必须是第 18 个正片",
            18.0,
            evaButton.episode.sort,
            0.0,
        )
        assertEquals(18, evaButton.position)
    }

    @Test
    fun `跳着看时_按钮从看到这里往后走_序号不等于看过数加1`() {
        // 看了 1–17 和 20：看过 18 集，看到这里是第 20 集，下一集是第 21 集。
        val eps = (1..26).map { ep(it, it.toDouble(), "2026-09-01") }
        val states = ((1..17) + listOf(20)).associateWith { EpisodeStatus.DONE }

        val count = EpisodeTracking.progressCount(SubjectType.ANIME, eps, states, collectionType = CollectionType.DOING) as ProgressCount.Known
        assertEquals(18, count.current)

        val button = EpisodeTracking.epButtonState(eps, states, today) as EpButtonState.Next
        // 按钮指向看到这里之后的那一集，序号 21；「看过数 + 1」会算成 19，是错的。
        assertEquals(21.0, button.episode.sort, 0.0)
        assertEquals(21, button.position)
        assertNotEquals(count.current + 1, button.position)
    }

    @Test
    fun `正片序号只数正片_特典不占位次`() {
        val mains = (1..5).map { ep(it, it.toDouble(), "2026-09-01") }
        val specials = (90..92).map { ep(it, (it - 89).toDouble(), type = EpisodeType.SPECIAL) }
        val all = specials + mains // 故意把特典排在前面，序号也不该受影响
        val sortedMains = EpisodeTracking.mainEpisodes(all)
        assertEquals(1, EpisodeTracking.mainEpisodePosition(sortedMains, mains.first()))
        assertEquals(5, EpisodeTracking.mainEpisodePosition(sortedMains, mains.last()))
    }

    @Test
    fun `条目通用EP按钮重载对于书籍不返回Empty且按书籍口径派生`() {
        val bookState = EpisodeTracking.epButtonState(
            subjectType = SubjectType.BOOK,
            episodes = emptyList(),
            watchStates = emptyMap(),
            today = today,
            epStatus = 3,
            volStatus = 0,
        )
        assertEquals(EpButtonState.BookNext(3, BookUnit.EP), bookState)
    }

    // ---------- 追更时间表按播出日分组 ----------

    @Test
    fun `追更时间表按播出日分组排序正确`() {
        val item1 = "A" to listOf(ep(1, 1.0, "2026-09-29"))
        val item2 = "B" to listOf(ep(1, 1.0, "2026-09-28"))
        val item3 = "C" to listOf(ep(1, 1.0, "2026-09-29"))
        val groups = groupScheduleByDay(listOf(item1, item2, item3), today, { it.second }) { _, _ -> null }
        assertEquals(2, groups.size)
        assertEquals(0L, groups[0].daysUntil)
        assertEquals(listOf(item2), groups[0].items)
        assertEquals(1L, groups[1].daysUntil)
        assertEquals(listOf(item1, item3), groups[1].items)
    }

    @Test
    fun `追更时间表同一天内按播出时刻升序_没有时刻的排最后_其余保留原序`() {
        val a = "A" to listOf(ep(1, 1.0, today.toString()))
        val b = "B" to listOf(ep(1, 1.0, today.toString()))
        val c = "C" to listOf(ep(1, 1.0, today.toString()))
        val d = "D" to listOf(ep(1, 1.0, today.toString()))
        val minutes = mapOf("B" to AiringMinute(22 * 60), "C" to AiringMinute(20 * 60))
        val groups = groupScheduleByDay(listOf(a, b, c, d), today, { it.second }) { item, date ->
            assertEquals(today, date)
            minutes[item.first]
        }
        assertEquals(listOf(c, b, a, d), groups.single().items)
    }

    // ---------- 好友在看动态聚合 ----------

    @Test
    fun `好友在看动态合并过滤只取7天内按作品合并去重好友且按更新时间倒序`() {
        val now = 1_000_000_000_000L
        val day = 86_400_000L
        val sub1 = SubjectBo(id = 101, type = SubjectType.ANIME, name = "Anime 1", nameCn = "动画一")
        val sub2 = SubjectBo(id = 102, type = SubjectType.BOOK, name = "Book 2", nameCn = "书籍二")
        val subOld = SubjectBo(id = 103, type = SubjectType.ANIME, name = "Old", nameCn = "老条目")

        val acts = listOf(
            // sub1: user1 在看 (now - 1 day)
            FriendActivityBo(1, "user1", "用户1", null, sub1, CollectionType.DOING, now - 1 * day),
            // sub1: user2 在看 (now - 2 days)
            FriendActivityBo(2, "user2", "用户2", null, sub1, CollectionType.DOING, now - 2 * day),
            // sub1: user1 重复操作 (now - 3 days) -> 应当被去重
            FriendActivityBo(1, "user1", "用户1", null, sub1, CollectionType.WISH, now - 3 * day),
            // sub2: user3 读过 (now - 0.5 days) -> 最新
            FriendActivityBo(3, "user3", "用户3", null, sub2, CollectionType.DONE, now - day / 2),
            // subOld: 8 天前 -> 应当被过滤
            FriendActivityBo(4, "user4", "用户4", null, subOld, CollectionType.DOING, now - 8 * day),
            // sub2: user5 比「现在」还新 30 秒（服务端时钟略快 / 现在按分钟跳）-> 保留，且是 sub2 最新的一条
            FriendActivityBo(5, "user5", "用户5", null, sub2, CollectionType.DOING, now + 30_000),
        )

        val groups = aggregateFriendsActivities(acts, now)
        assertEquals(2, groups.size)
        // 第一名应当是 sub2（最近更新为 now + 30 秒）
        assertEquals(102, groups[0].subject.id)
        assertEquals(CollectionType.DOING, groups[0].latestCollectionType)
        assertEquals(listOf(5, 3), groups[0].friends.map { it.userId })

        // 第二名是 sub1
        assertEquals(101, groups[1].subject.id)
        assertEquals(CollectionType.DOING, groups[1].latestCollectionType)
        assertEquals(2, groups[1].friends.size)
        assertEquals(listOf(1, 2), groups[1].friends.map { it.userId })
    }

    // ---------- 列表行：EP 槽位与进度文字的类型判定 ----------

    @Test
    fun `列表行带进度的类型 动画三次元书籍有 游戏音乐没有`() {
        assertTrue(EpisodeTracking.hasProgress(SubjectType.ANIME))
        assertTrue(EpisodeTracking.hasProgress(SubjectType.REAL))
        assertTrue(EpisodeTracking.hasProgress(SubjectType.BOOK))
        assertFalse(EpisodeTracking.hasProgress(SubjectType.GAME))
        assertFalse(EpisodeTracking.hasProgress(SubjectType.MUSIC))
    }

    @Test
    fun `EP槽位 带进度的类型且在看才留 其余一个像素都不占`() {
        for (type in listOf(SubjectType.ANIME, SubjectType.REAL, SubjectType.BOOK)) {
            assertTrue("$type 在看", EpisodeTracking.reservesEpSlot(type, CollectionType.DOING))
            for (status in listOf(CollectionType.WISH, CollectionType.DONE, CollectionType.ON_HOLD, CollectionType.DROPPED)) {
                assertFalse("$type $status", EpisodeTracking.reservesEpSlot(type, status))
            }
        }
        for (type in listOf(SubjectType.GAME, SubjectType.MUSIC)) {
            assertFalse("$type 在看", EpisodeTracking.reservesEpSlot(type, CollectionType.DOING))
        }
    }

}
