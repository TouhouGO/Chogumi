package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonoFactsTest {

    private fun work(
        id: Int,
        role: CastRole? = null,
        actors: List<MonoPersonRefBo> = emptyList(),
        positions: List<String> = emptyList(),
        type: SubjectType = SubjectType.ANIME,
    ) = MonoWorkBo(id = id, name = "w$id", nameCn = "", image = null, subjectType = type, role = role, actors = actors, positions = positions)

    private fun actor(id: Int) = MonoPersonRefBo(id = id, name = "p$id", avatarUrl = null)

    @Test
    fun `服务端定位值映射：1 主角 2 配角 3 客串 4 闲角 5 旁白，其余未知`() {
        assertEquals(
            listOf(null, CastRole.MAIN, CastRole.SUPPORTING, CastRole.GUEST, CastRole.EXTRA, CastRole.NARRATOR, null),
            (0..6).map(::castRoleOf),
        )
        // 往返一致：筛选参数用的 code 与解析用的是同一份映射
        CastRole.entries.forEach { assertEquals(it, castRoleOf(it.code)) }
    }

    @Test
    fun `角色定位取最主要的一个，没有定位时为 null`() {
        assertEquals(CastRole.MAIN, characterBillingRole(listOf(work(1, CastRole.GUEST), work(2, CastRole.MAIN), work(3, CastRole.SUPPORTING))))
        assertEquals(CastRole.SUPPORTING, characterBillingRole(listOf(work(1, CastRole.GUEST), work(2, CastRole.SUPPORTING))))
        assertNull(characterBillingRole(listOf(work(1))))
        assertNull(characterBillingRole(emptyList()))
    }

    @Test
    fun `声优取出现在最多作品里的那位，次数相同取先出现的，同一作品重复只算一次`() {
        val works = listOf(
            work(1, actors = listOf(actor(7))),
            work(2, actors = listOf(actor(8), actor(8))),
            work(3, actors = listOf(actor(8))),
            work(4, actors = listOf(actor(7))),
        )
        // 7 与 8 都出现在 2 部作品里（8 在作品 2 里重复只算一次）：取先出现的 7
        assertEquals(7, characterVoiceActor(works)?.id)
        assertEquals(8, characterVoiceActor(works + work(5, actors = listOf(actor(8))))?.id)
        assertNull(characterVoiceActor(listOf(work(1))))
    }

    @Test
    fun `三次元作品的出演者是演员不是声优，不参与声优判定`() {
        // 真人剧排在前面、与动画平票：仍取动画的声优
        val works = listOf(
            work(1, actors = listOf(actor(9)), type = SubjectType.REAL),
            work(2, actors = listOf(actor(7))),
        )
        assertEquals(7, characterVoiceActor(works)?.id)
        // 只出现在三次元作品里：没有 CV
        assertNull(characterVoiceActor(listOf(work(1, actors = listOf(actor(9)), type = SubjectType.REAL))))
    }

    @Test
    fun `事实 chip：角色取性别与生日，人物取生日、出身地与事务所，按键名顺序找第一个有值的`() {
        val infobox = listOf(
            FactRow("生日", "9月24日"),
            FactRow("性别", "女"),
            FactRow("出身地", " "),
            FactRow("出生地", "日本·千叶县"),
            FactRow("事务所", "青二プロダクション"),
        )
        assertEquals(
            listOf(MonoFact(MonoFactKind.GENDER, "女"), MonoFact(MonoFactKind.BIRTHDAY, "9月24日")),
            monoFacts(MonoType.CHARACTER, infobox),
        )
        assertEquals(
            listOf(
                MonoFact(MonoFactKind.BIRTHDAY, "9月24日"),
                MonoFact(MonoFactKind.BIRTHPLACE, "日本·千叶县"),
                MonoFact(MonoFactKind.AGENCY, "青二プロダクション"),
            ),
            monoFacts(MonoType.PERSON, infobox),
        )
        assertEquals(emptyList<MonoFact>(), monoFacts(MonoType.PERSON, listOf(FactRow("身高", "160cm"))))
    }

    @Test
    fun `全部资料：简体中文名与标题相同不列，不同时保留`() {
        val infobox = listOf(FactRow("简体中文名", "悠木碧"), FactRow("别名", "悠木 碧"))
        assertEquals(listOf(FactRow("别名", "悠木 碧")), monoInfoRows(infobox, title = "悠木碧"))
        assertEquals(infobox, monoInfoRows(infobox, title = "Yuuki Aoi"))
    }

    @Test
    fun `筛选项：类型与职位两排各带计数，按首次出现顺序，两维互相约束`() {
        val personWorks = listOf(
            work(1, positions = listOf("艺术家"), type = SubjectType.MUSIC),
            work(2, positions = listOf("主演", "主题歌演出"), type = SubjectType.REAL),
            work(3, positions = listOf("艺术家", "作词"), type = SubjectType.MUSIC),
        )
        val all = monoWorkFilterOptions(MonoType.PERSON, personWorks, MonoWorkFilter())
        assertEquals(
            listOf(MonoWorkOption(SubjectType.MUSIC, 2), MonoWorkOption(SubjectType.REAL, 1)),
            all.types,
        )
        assertEquals(
            listOf(
                MonoWorkOption<MonoWorkFacet>(MonoWorkFacet.Position("艺术家"), 2),
                MonoWorkOption(MonoWorkFacet.Position("主演"), 1),
                MonoWorkOption(MonoWorkFacet.Position("主题歌演出"), 1),
                MonoWorkOption(MonoWorkFacet.Position("作词"), 1),
            ),
            all.facets,
        )
        // 选了音乐：职位一项不少，只在音乐作品里数（没在音乐里出现的计 0）；类型计数不受类型自身约束
        val music = monoWorkFilterOptions(MonoType.PERSON, personWorks, MonoWorkFilter(type = SubjectType.MUSIC))
        assertEquals(
            listOf(
                MonoWorkOption<MonoWorkFacet>(MonoWorkFacet.Position("艺术家"), 2),
                MonoWorkOption(MonoWorkFacet.Position("主演"), 0),
                MonoWorkOption(MonoWorkFacet.Position("主题歌演出"), 0),
                MonoWorkOption(MonoWorkFacet.Position("作词"), 1),
            ),
            music.facets,
        )
        assertEquals(all.types, music.types)
        // 选了主演：类型那排仍是两项（不会因为只剩一种而整排消失），音乐计 0
        val lead = monoWorkFilterOptions(MonoType.PERSON, personWorks, MonoWorkFilter(facet = MonoWorkFacet.Position("主演")))
        assertEquals(listOf(MonoWorkOption(SubjectType.MUSIC, 0), MonoWorkOption(SubjectType.REAL, 1)), lead.types)
    }

    @Test
    fun `筛选项：角色的第二维是出场`() {
        val castWorks = listOf(
            work(1, role = CastRole.MAIN, type = SubjectType.BOOK),
            work(2, role = CastRole.GUEST, type = SubjectType.MUSIC),
            work(3, role = CastRole.MAIN, type = SubjectType.BOOK),
        )
        val options = monoWorkFilterOptions(MonoType.CHARACTER, castWorks, MonoWorkFilter())
        assertEquals(listOf(MonoWorkOption(SubjectType.BOOK, 2), MonoWorkOption(SubjectType.MUSIC, 1)), options.types)
        assertEquals(
            listOf(MonoWorkOption<MonoWorkFacet>(MonoWorkFacet.Role(CastRole.MAIN), 2), MonoWorkOption(MonoWorkFacet.Role(CastRole.GUEST), 1)),
            options.facets,
        )
    }

    @Test
    fun `筛选匹配：两维都要满足，null 表示不限`() {
        val w = work(1, positions = listOf("主演", "作词"), type = SubjectType.MUSIC)
        assertEquals(true, MonoWorkFilter().matches(w))
        assertEquals(true, MonoWorkFilter(facet = MonoWorkFacet.Position("作词")).matches(w))
        assertEquals(false, MonoWorkFilter(facet = MonoWorkFacet.Position("作曲")).matches(w))
        assertEquals(true, MonoWorkFilter(type = SubjectType.MUSIC, facet = MonoWorkFacet.Position("作词")).matches(w))
        assertEquals(false, MonoWorkFilter(type = SubjectType.ANIME, facet = MonoWorkFacet.Position("作词")).matches(w))
        assertEquals(true, MonoWorkFilter(facet = MonoWorkFacet.Role(CastRole.MAIN)).matches(work(2, role = CastRole.MAIN)))
        assertEquals(false, MonoWorkFilter(facet = MonoWorkFacet.Role(CastRole.MAIN)).matches(work(2, role = CastRole.GUEST)))
    }

    @Test
    fun `规格表：角色默认按时间，人物默认按评分`() {
        assertEquals(MonoWorkSort.TIME, monoTypeSpec(MonoType.CHARACTER).defaultSort)
        assertEquals(MonoWorkSort.RATING, monoTypeSpec(MonoType.PERSON).defaultSort)
        assertEquals(MonoWorkFacetBy.ROLE, monoTypeSpec(MonoType.CHARACTER).facetBy)
        assertEquals(MonoWorkFacetBy.POSITION, monoTypeSpec(MonoType.PERSON).facetBy)
    }
}
