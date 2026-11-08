package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectRelationTest {

    private fun relation(id: Int, relationId: Int, type: SubjectType = SubjectType.ANIME, order: Int = 0) =
        SubjectRelationBo(id = id, type = type, name = "s$id", nameCn = "", relationId = relationId, order = order, score = 0.0)

    /** 对面条目正是 ID 所在那张表的类型时的归类（1 与 2~99 取动画）。 */
    private fun kindOnOwnSide(relationId: Int): SubjectRelationKind = relationKindOf(
        relationId,
        when (relationId) {
            in 1002..1099 -> SubjectType.BOOK
            in 3001..3099 -> SubjectType.MUSIC
            in 4002..4099 -> SubjectType.GAME
            else -> SubjectType.ANIME
        },
    )

    @Test
    fun `同一语义跨条目类型的关系 ID 归入同一类`() {
        // 前传：动画/三次元 2、书籍 1005、游戏 4002
        assertEquals(setOf(SubjectRelationKind.PREQUEL), listOf(2, 1005, 4002).map(::kindOnOwnSide).toSet())
        // 其他：各类型 99/1099/3099/4099
        assertEquals(setOf(SubjectRelationKind.OTHER), listOf(99, 1099, 3099, 4099).map(::kindOnOwnSide).toSet())
    }

    @Test
    fun `关系 ID 不在对面条目类型的表里时按对面类型显示，不读成对面的关系名`() {
        // 音乐条目的关联里，游戏带 3001：当前条目是那部游戏的原声集，游戏不是原声集
        val group = groupSubjectRelations(listOf(relation(1, 3001, SubjectType.GAME))).single()
        assertEquals(SubjectRelationKind.ADAPTATION, group.kind)
        assertEquals(SubjectType.GAME, group.adaptationType)
        // 动画、游戏的关联里带对方那张表 ID 的书籍同理（3001 之外的段）
        assertEquals(SubjectRelationKind.ADAPTATION, relationKindOf(11, SubjectType.BOOK))
        assertEquals(SubjectRelationKind.ADAPTATION, relationKindOf(4006, SubjectType.BOOK))
        // 书籍的单行本 tab 只收对面是书籍的 1003：动画那一边的 1003 说的是当前这本书
        assertEquals(listOf(1), subjectVolumes(listOf(relation(1, 1003, SubjectType.BOOK), relation(2, 1003, SubjectType.ANIME))).map { it.id })
    }

    @Test
    fun `关系 ID 在对面条目类型的表里时照旧是对面的关系名`() {
        // 游戏的关联里，音乐条目带 3001：对面是原声集
        assertEquals(SubjectRelationKind.OST, relationKindOf(3001, SubjectType.MUSIC))
        assertEquals(SubjectRelationKind.SEQUEL, relationKindOf(3, SubjectType.REAL))
        assertEquals(SubjectRelationKind.ADAPTATION, relationKindOf(1, SubjectType.BOOK))
    }

    @Test
    fun `未知关系 ID 归入其他、未知对面类型只凭 ID 归类，不抛错也不丢条目`() {
        assertEquals(SubjectRelationKind.OTHER, relationKindOf(13, SubjectType.ANIME))
        assertEquals(SubjectRelationKind.OTHER, relationKindOf(5000, SubjectType.ANIME))
        assertEquals(SubjectRelationKind.OST, relationKindOf(3001, SubjectType.UNKNOWN))
        assertEquals(1, groupSubjectRelations(listOf(relation(1, 5000))).single().items.size)
    }

    @Test
    fun `分组按权重组排序：主线、衍生、音乐与周边、客串与其他，与服务端 ID 升序无关`() {
        // 按服务端返回顺序（关系 ID 升序）给出：改编 1、前传 2、续集 3、番外 6、角色出演 7、其他 99、画集 1004、原声集 3001
        val groups = groupSubjectRelations(
            listOf(
                relation(1, 1), relation(2, 2), relation(3, 3), relation(4, 6),
                relation(5, 7), relation(6, 99), relation(7, 1004, SubjectType.BOOK), relation(8, 3001, SubjectType.MUSIC),
            ),
        )
        assertEquals(
            listOf(
                SubjectRelationKind.ADAPTATION,
                SubjectRelationKind.PREQUEL,
                SubjectRelationKind.SEQUEL,
                SubjectRelationKind.SIDE_STORY,
                SubjectRelationKind.OST,
                SubjectRelationKind.ALBUM,
                SubjectRelationKind.CHARACTER,
                SubjectRelationKind.OTHER,
            ),
            groups.map { it.kind },
        )
    }

    @Test
    fun `关联按权重分成四组`() {
        fun weights(vararg ids: Int) = ids.map { kindOnOwnSide(it).weight }.toSet()
        // 主线：改编 / 前传 / 续集 / 主线故事 / 系列 / 总集篇 / 全集
        assertEquals(setOf(SubjectRelationWeight.MAIN), weights(1, 2, 3, 12, 1002, 4, 5, 1005, 1006, 1008, 4002, 4003, 4012))
        // 衍生：番外篇 / 不同演绎 / 相同世界观 / 衍生 / 单行本 / 不同版本
        assertEquals(setOf(SubjectRelationWeight.DERIVATIVE), weights(6, 1007, 4006, 10, 1015, 4010, 8, 1012, 4008, 11, 1003, 1010, 4016))
        // 音乐与周边：原声集 / 片头曲 / 片尾曲 / 插入歌 / 角色歌 / 印象曲 / 广播剧 / 画集
        assertEquals(setOf(SubjectRelationWeight.MUSIC_AND_MERCH), weights(3001, 3003, 3004, 3005, 3002, 3006, 3007, 1004))
        // 客串与其他：角色出演、不同世界观、联动、游戏的版本类、其他与未知 ID
        assertEquals(
            setOf(SubjectRelationWeight.OTHER),
            weights(7, 1011, 4007, 9, 1013, 4009, 14, 1014, 4014, 4015, 4017, 4018, 4019, 99, 1099, 3099, 4099, 5000),
        )
    }

    @Test
    fun `权重组的声明顺序即显示顺序`() {
        assertEquals(
            listOf(
                SubjectRelationWeight.MAIN,
                SubjectRelationWeight.DERIVATIVE,
                SubjectRelationWeight.MUSIC_AND_MERCH,
                SubjectRelationWeight.OTHER,
            ),
            SubjectRelationKind.entries.map { it.weight }.distinct(),
        )
        assertEquals(
            listOf(
                SubjectRelationKind.ADAPTATION, SubjectRelationKind.PREQUEL, SubjectRelationKind.SEQUEL,
                SubjectRelationKind.PARENT_STORY, SubjectRelationKind.SERIES, SubjectRelationKind.SUMMARY,
                SubjectRelationKind.FULL_STORY,
            ),
            SubjectRelationKind.entries.filter { it.weight == SubjectRelationWeight.MAIN },
        )
    }

    @Test
    fun `改编按对面条目类型拆组，其他关系不拆`() {
        val groups = groupSubjectRelations(
            listOf(
                relation(1, 1, SubjectType.GAME),
                relation(2, 1, SubjectType.BOOK),
                relation(3, 3, SubjectType.ANIME),
                relation(4, 4003, SubjectType.GAME),
            ),
        )
        val adaptations = groups.filter { it.kind == SubjectRelationKind.ADAPTATION }
        assertEquals(listOf(SubjectType.BOOK, SubjectType.GAME), adaptations.map { it.adaptationType })
        val sequel = groups.single { it.kind == SubjectRelationKind.SEQUEL }
        assertEquals(null, sequel.adaptationType)
        assertEquals(listOf(3, 4), sequel.items.map { it.id })
    }

    @Test
    fun `组内按 order 升序，相同 order 保持服务端顺序`() {
        val group = groupSubjectRelations(
            listOf(relation(1, 3, order = 2), relation(2, 3, order = 1), relation(3, 3, order = 1)),
        ).single()
        assertEquals(listOf(2, 3, 1), group.items.map { it.id })
    }
}
