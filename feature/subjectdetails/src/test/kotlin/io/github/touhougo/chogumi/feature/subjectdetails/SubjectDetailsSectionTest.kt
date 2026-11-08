package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.appFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubjectDetailsSectionTest {

    /** 完整模式、公共评分开。 */
    private val full = appFeatures(ExperienceMode.FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = true)

    /** 公共评分关掉：统计 tab 消失。 */
    private val ratingsOff = appFeatures(ExperienceMode.FULL, showPublicRatings = false, showCoverScores = true, showFriendMarks = true)

    /** 专注模式：社区不可见。 */
    private val focus = appFeatures(ExperienceMode.FOCUS, showPublicRatings = true, showCoverScores = true, showFriendMarks = true)

    /** 已拿到详情、评分人数足够的条目；null 类型表示条目还没到（骨架阶段）。 */
    private fun subjectOf(type: SubjectType?, ratingTotal: Int = 100, fullDetail: Boolean = true) =
        type?.let { SubjectBo(id = 1, type = it, name = "n", nameCn = "", ratingTotal = ratingTotal, hasFullDetail = fullDetail) }

    @Test
    fun `详情状态默认选中概览`() {
        assertEquals(SubjectDetailsSection.OVERVIEW, SubjectDetailsUiState().selectedSection)
    }

    @Test
    fun `第 2 个 tab 按规格表：动画三次元章节、书籍单行本、音乐曲目、游戏与不认识的类型没有`() {
        val second = mapOf(
            SubjectType.ANIME to SubjectDetailsSection.EPISODES,
            SubjectType.REAL to SubjectDetailsSection.EPISODES,
            SubjectType.BOOK to SubjectDetailsSection.VOLUMES,
            SubjectType.MUSIC to SubjectDetailsSection.TRACKS,
        )
        second.forEach { (type, tab) ->
            assertEquals(
                listOf(
                    SubjectDetailsSection.OVERVIEW,
                    tab,
                    SubjectDetailsSection.PEOPLE,
                    SubjectDetailsSection.COMMUNITY,
                    SubjectDetailsSection.STATS,
                ),
                visibleSectionsFor(subjectOf(type), full),
                "$type",
            )
        }
        for (type in listOf(SubjectType.GAME, SubjectType.UNKNOWN)) {
            assertEquals(
                listOf(
                    SubjectDetailsSection.OVERVIEW,
                    SubjectDetailsSection.PEOPLE,
                    SubjectDetailsSection.COMMUNITY,
                    SubjectDetailsSection.STATS,
                ),
                visibleSectionsFor(subjectOf(type), full),
                "$type",
            )
        }
        // 骨架阶段还没拿到类型，按动画画
        assertEquals(visibleSectionsFor(subjectOf(SubjectType.ANIME), full), visibleSectionsFor(null, full))
    }

    @Test
    fun `人物 tab：只在角色与制作人员都确认为空时隐藏`() {
        val anime = subjectOf(SubjectType.ANIME)
        assertFalse(SubjectDetailsSection.PEOPLE in visibleSectionsFor(anime, full, hasPeople = false))
        assertTrue(SubjectDetailsSection.PEOPLE in visibleSectionsFor(anime, full, hasPeople = true))

        val settledEmpty = SubjectDetailsUiState(
            subjectLoad = Load.Ready(checkNotNull(anime)),
            characters = Load.Ready(emptyList()),
            persons = Load.Ready(emptyList()),
        )
        assertFalse(settledEmpty.hasPeople)
        assertTrue(settledEmpty.copy(persons = Load.Pending).hasPeople, "还在加载不算空")
        assertTrue(settledEmpty.copy(characters = Load.Failed(Failure.Offline)).hasPeople, "失败不算空：要留着 tab 给用户重试")
        val oneCharacter = RelatedCharacterBo(id = 1, name = "c", summary = "", type = 1, role = null)
        assertTrue(settledEmpty.copy(characters = Load.Ready(listOf(oneCharacter))).hasPeople)
        val onePerson = RelatedPersonBo(id = 2, name = "p", type = 1)
        assertTrue(settledEmpty.copy(persons = Load.Ready(listOf(onePerson))).hasPeople)
    }

    @Test
    fun `选中人物而人物确认为空时回落到概览`() {
        val state = SubjectDetailsUiState(
            subjectLoad = Load.Ready(checkNotNull(subjectOf(SubjectType.ANIME))),
            requestedSection = SubjectDetailsSection.PEOPLE,
            characters = Load.Ready(emptyList()),
            persons = Load.Ready(emptyList()),
        )
        assertEquals(SubjectDetailsSection.OVERVIEW, state.selectedSection)
        assertEquals(SubjectDetailsSection.PEOPLE, state.copy(persons = Load.Pending).selectedSection)
    }

    @Test
    fun `各区块初值都是等待`() {
        val state = SubjectDetailsUiState()
        assertEquals(listOf(Load.Pending), listOf(state.episodes, state.relations, state.characters, state.persons).distinct())
    }

    @Test
    fun `专注模式：所有类型都只少掉社区 tab`() {
        (SubjectType.entries + listOf(null)).forEach { type ->
            val sections = visibleSectionsFor(subjectOf(type), focus)
            assertFalse(SubjectDetailsSection.COMMUNITY in sections, "$type 专注模式下不得有社区 tab")
            assertEquals(
                visibleSectionsFor(subjectOf(type), full).filter { it != SubjectDetailsSection.COMMUNITY },
                sections,
                "$type 专注模式只应少掉社区 tab",
            )
        }
    }

    @Test
    fun `resolveSection：专注模式停在社区时回落到概览，完整模式原样保留`() {
        val anime = subjectOf(SubjectType.ANIME)
        assertEquals(SubjectDetailsSection.OVERVIEW, resolveSection(SubjectDetailsSection.COMMUNITY, anime, focus))
        assertEquals(SubjectDetailsSection.COMMUNITY, resolveSection(SubjectDetailsSection.COMMUNITY, anime, full))
    }

    @Test
    fun `resolveSection：当前类型不可见的第 2 个 tab 回落到概览`() {
        assertEquals(SubjectDetailsSection.OVERVIEW, resolveSection(SubjectDetailsSection.EPISODES, subjectOf(SubjectType.BOOK), full))
        assertEquals(SubjectDetailsSection.OVERVIEW, resolveSection(SubjectDetailsSection.VOLUMES, subjectOf(SubjectType.ANIME), full))
        assertEquals(SubjectDetailsSection.OVERVIEW, resolveSection(SubjectDetailsSection.TRACKS, subjectOf(SubjectType.GAME), full))
        assertEquals(SubjectDetailsSection.TRACKS, resolveSection(SubjectDetailsSection.TRACKS, subjectOf(SubjectType.MUSIC), full))
    }

    @Test
    fun `统计 tab：评分不足 10 人或公共评分关掉时不出现，选中的统计回落到概览`() {
        val anime = SubjectType.ANIME
        assertTrue(SubjectDetailsSection.STATS in visibleSectionsFor(subjectOf(anime, ratingTotal = 10), full))
        assertFalse(SubjectDetailsSection.STATS in visibleSectionsFor(subjectOf(anime, ratingTotal = 9), full))
        assertFalse(SubjectDetailsSection.STATS in visibleSectionsFor(subjectOf(anime, ratingTotal = 5000), ratingsOff))
        assertEquals(
            SubjectDetailsSection.OVERVIEW,
            resolveSection(SubjectDetailsSection.STATS, subjectOf(anime, ratingTotal = 3), full),
        )
    }

    @Test
    fun `统计 tab：只有列表摘要的条目评分人数不可信，按未知处理（出现）`() {
        val anime = SubjectType.ANIME
        assertTrue(SubjectDetailsSection.STATS in visibleSectionsFor(subjectOf(anime, ratingTotal = 0, fullDetail = false), full))
        assertTrue(SubjectDetailsSection.STATS in visibleSectionsFor(null, full))
    }
}
