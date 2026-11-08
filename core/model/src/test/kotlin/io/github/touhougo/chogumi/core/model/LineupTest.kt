package io.github.touhougo.chogumi.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineupTest {

    private fun actor(id: Int, name: String) = RelatedPersonBo(id = id, name = name, type = 1)

    private fun character(id: Int, role: CastRole?, vararg actors: RelatedPersonBo) =
        RelatedCharacterBo(id = id, name = "角色$id", summary = "", type = 1, role = role, actors = actors.toList())

    private fun staff(id: Int, name: String, relation: String) =
        RelatedPersonBo(id = id, name = name, type = 3, relation = relation)

    @Test
    fun `声优只数主角与配角且按人去重`() {
        val lineup = subjectLineupOf(
            SubjectType.ANIME,
            characters = listOf(
                character(1, CastRole.MAIN, actor(10, "花泽香菜")),
                character(2, CastRole.SUPPORTING, actor(10, "花泽香菜"), actor(11, "早见沙织")),
                character(3, CastRole.GUEST, actor(12, "客串")),
                character(4, null, actor(13, "没有定位")),
            ),
            persons = emptyList(),
        )!!

        assertEquals(listOf("花泽香菜", "早见沙织"), lineup.voiceActors.map { it.name })
    }

    @Test
    fun `制作公司取规格表里的职位`() {
        val lineup = subjectLineupOf(
            SubjectType.ANIME,
            characters = emptyList(),
            persons = listOf(staff(1, "京都动画", "动画制作"), staff(2, "导演甲", "导演"), staff(1, "京都动画", "动画制作")),
        )!!

        assertEquals(listOf("京都动画"), lineup.studios.map { it.name })
    }

    @Test
    fun `不算阵容的类型没有阵容`() {
        assertNull(subjectLineupOf(SubjectType.BOOK, listOf(character(1, CastRole.MAIN, actor(10, "甲"))), emptyList()))
        assertNull(subjectLineupOf(SubjectType.REAL, listOf(character(1, CastRole.MAIN, actor(10, "甲"))), emptyList()))
    }
}
