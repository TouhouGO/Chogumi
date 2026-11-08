package io.github.touhougo.chogumi.core.model.statistics

import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.LineupName
import io.github.touhougo.chogumi.core.model.SubjectLineup
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 阵容：看过和在看的作品里，每个声优、每家制作公司出现在几部。 */
class LineupCalculatorTest {

    private val kana = LineupName(10, "花泽香菜", null)
    private val saori = LineupName(11, "早见沙织", null)
    private val kyoani = LineupName(20, "京都动画", null)

    @Test
    fun `按出现的部数从多到少_一样多的按名字`() {
        val lineup = lineupOf(
            listOf(collection(1), collection(2), collection(3)),
            mapOf(
                1 to SubjectLineup(listOf(kana, saori), listOf(kyoani)),
                2 to SubjectLineup(listOf(saori), listOf(kyoani)),
                3 to SubjectLineup(listOf(kana), emptyList()),
            ),
        )!!

        assertEquals(listOf("早见沙织" to 2, "花泽香菜" to 2), lineup.voiceActors.map { it.name.name to it.works })
        assertEquals(listOf("京都动画" to 2), lineup.studios.map { it.name.name to it.works })
    }

    @Test
    fun `只数看过和在看_没拉到阵容的不算`() {
        val lineup = lineupOf(
            listOf(collection(1, CollectionType.WISH), collection(2, CollectionType.DROPPED), collection(3, CollectionType.DOING), collection(4)),
            (1..3).associateWith { SubjectLineup(listOf(kana), emptyList()) },
        )!!

        assertEquals(listOf(1), lineup.voiceActors.map { it.works })
    }

    @Test
    fun `声优最多取九位`() {
        val many = (1..12).map { LineupName(it, "声优$it", null) }
        val lineup = lineupOf(listOf(collection(1)), mapOf(1 to SubjectLineup(many, emptyList())))!!

        assertEquals(9, lineup.voiceActors.size)
    }

    @Test
    fun `一个名字都没有时没有阵容`() {
        assertNull(lineupOf(listOf(collection(1)), emptyMap()))
        assertNull(lineupOf(listOf(collection(1)), mapOf(1 to SubjectLineup(emptyList(), emptyList()))))
    }

    private fun collection(id: Int, type: CollectionType = CollectionType.DONE) = UserCollectionBo(
        subjectId = id,
        subjectType = SubjectType.ANIME,
        type = type,
        updatedAt = 1L,
    )
}
