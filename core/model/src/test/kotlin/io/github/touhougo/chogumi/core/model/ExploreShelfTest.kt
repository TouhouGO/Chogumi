package io.github.touhougo.chogumi.core.model

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探索页入口、默认布局与「布局 → 货架」的守卫测试。
 *
 * 1. 每个入口、默认布局里的货架、每个方块候选的条件经 [normalizeSearchCriteria] 都是 Valid；
 * 2. 题材词与方块候选里的词，没有一个被 [isExplicitAdultTag] 判为成人向；
 * 3. 季度换算（[seasonFor] / [previousSeasonFor]）的边界日期写死期望值；
 * 4. 公司方块与 [exploreCompaniesFor] 完全一致；
 * 5. [exploreShelvesFor] 按布局出货架：隐藏的、这个分类用不了的、目录里没有的、一个方块都没有的都不出现，
 *    改名、改排序、存下来的方块键、画法回退各按规则。
 */
class ExploreShelfTest {

    /** 五个具体类型（「全部」与 UNKNOWN 没有探索页）。 */
    private val types = SubjectType.entries.filter { it.concreteOrNull() != null }

    private val today = LocalDate.of(2026, 9, 30)

    private fun defaultShelves(type: SubjectType) = exploreShelvesFor(type, today, defaultShelfLayout(type))

    // —— 1. 条件合法

    @Test
    fun `每个入口、默认货架与方块候选的条件都能规范化`() {
        for (type in types) {
            for (entry in exploreEntriesFor(type, today)) {
                val criteria = when (entry) {
                    is ExploreEntry.Search -> entry.criteria
                    is ExploreEntry.OpenFilters -> entry.criteria
                    is ExploreEntry.Calendar -> continue
                }
                assertTrue(
                    "$type 的入口 ${entry.title} 条件不合法：$criteria",
                    normalizeSearchCriteria(criteria) is SearchValidation.Valid,
                )
            }
            val candidates = (TileSet.entries.map { TileShelfSource.Fixed(it) } + TileShelfSource.Custom("任意"))
                .flatMap { tileCandidates(type, it) }
                .map { it.criteria }
            for (criteria in workCriteriaOf(defaultShelves(type)) + candidates) {
                assertTrue(
                    "$type 的货架或方块条件不合法：$criteria",
                    normalizeSearchCriteria(criteria) is SearchValidation.Valid,
                )
            }
        }
    }

    // —— 2. 成人向词守卫

    @Test
    fun `题材词与方块候选里的词都不是成人向`() {
        for (type in types) {
            for (word in themeWordsFor(type)) {
                assertFalse("$type 的题材词「$word」被误判为成人向", isExplicitAdultTag(word))
            }
            for (set in TileSet.entries) {
                for (tile in tileCandidates(type, TileShelfSource.Fixed(set))) {
                    // 年代方块没有词
                    val word = when (val label = tile.label) {
                        is TileLabel.Word -> label.word
                        is TileLabel.AllOf -> label.word
                        is TileLabel.Decade, is TileLabel.Preset -> continue
                    }
                    assertFalse("$type 的方块「$word」被误判为成人向", isExplicitAdultTag(word))
                }
            }
        }
    }

    // —— 3. 季度换算边界

    @Test
    fun `季度换算的三个边界`() {
        // 正好提前两周进入下一季；早一天仍在本季。
        assertEquals(YearMonth.of(2026, 7), seasonFor(LocalDate.of(2026, 9, 16)))
        assertEquals(YearMonth.of(2026, 10), seasonFor(LocalDate.of(2026, 9, 17)))
        assertEquals(YearMonth.of(2026, 10), seasonFor(LocalDate.of(2026, 12, 17)))
        assertEquals(YearMonth.of(2027, 1), seasonFor(LocalDate.of(2026, 12, 18)))
        // 9-30 + 14 天 = 10-14，仍在 10 月季度；上一季是 7 月
        assertEquals(2026, seasonFor(LocalDate.of(2026, 9, 30)).year)
        assertEquals(10, seasonFor(LocalDate.of(2026, 9, 30)).monthValue)
        assertEquals(2026, previousSeasonFor(LocalDate.of(2026, 9, 30)).year)
        assertEquals(7, previousSeasonFor(LocalDate.of(2026, 9, 30)).monthValue)

        // 12-20 + 14 天 = 次年 1-3，已跨年落进 1 月季度；上一季是 10 月
        assertEquals(2027, seasonFor(LocalDate.of(2026, 12, 20)).year)
        assertEquals(1, seasonFor(LocalDate.of(2026, 12, 20)).monthValue)
        assertEquals(2026, previousSeasonFor(LocalDate.of(2026, 12, 20)).year)
        assertEquals(10, previousSeasonFor(LocalDate.of(2026, 12, 20)).monthValue)

        // 1 月本身：季度就是 1 月，上一季回到上一年 10 月
        assertEquals(2027, seasonFor(LocalDate.of(2027, 1, 10)).year)
        assertEquals(1, seasonFor(LocalDate.of(2027, 1, 10)).monthValue)
        assertEquals(2026, previousSeasonFor(LocalDate.of(2027, 1, 10)).year)
        assertEquals(10, previousSeasonFor(LocalDate.of(2027, 1, 10)).monthValue)
    }

    @Test
    fun `动画的入口与番剧回顾货架用季度首月`() {
        val day = LocalDate.of(2026, 12, 20)
        val entries = exploreEntriesFor(SubjectType.ANIME, day)
        val newSeason = entries.filterIsInstance<ExploreEntry.Search>()
            .first { it.title == ExploreTitle.NewSeason(1) }
        assertEquals(2027, (newSeason.criteria.filters.airtime as SearchAirtime).startYear)

        val catchUp = exploreShelvesFor(SubjectType.ANIME, day, defaultShelfLayout(SubjectType.ANIME))
            .filterIsInstance<ExploreShelf.Works>()
            .first { it.title == ExploreTitle.CatchUpSeason(10) }
        val airtime = (catchUp.content as WorkShelfContent.Search).criteria.filters.airtime as SearchAirtime
        assertEquals(2026, airtime.startYear)
        assertEquals(10, airtime.month)
    }

    // —— 4. 公司方块

    @Test
    fun `公司方块与公司名单完全一致`() {
        for (type in types) {
            val expected = exploreCompaniesFor(type)
            val shelf = defaultShelves(type).firstOrNull { it.title is ExploreTitle.CompanyBrowse }
            if (expected.isEmpty()) {
                assertTrue("$type 没有公司名单，却出了公司方块货架", shelf == null)
                assertTrue("$type 没有公司名单，候选池却不空", tileCandidates(type, TileShelfSource.Fixed(TileSet.COMPANY)).isEmpty())
                continue
            }
            val tiles = (shelf as ExploreShelf.Tiles).tiles
            assertEquals("$type 的公司方块个数与名单不一致", expected.size, tiles.size)
            tiles.forEachIndexed { index, tile ->
                assertEquals("$type 第 $index 个公司方块与名单顺序不一致", TileLabel.Word(expected[index].name), tile.label)
                assertEquals(
                    "$type 第 $index 个公司方块的条件必须由 toCriteria 统一构造",
                    expected[index].toCriteria(type),
                    tile.criteria,
                )
            }
            assertEquals("公司货架默认画标志", TileDrawing.LOGO, shelf.drawing)
        }
    }

    // —— 默认布局

    @Test
    fun `每个具体类型的默认货架都有近期热门与排行榜且布局项全部出现`() {
        for (type in types) {
            val shelves = defaultShelves(type)
            assertEquals(ExploreShelf.Trending(SearchCriteria(subjectType = type, sort = SearchSort.TRENDS)), shelves.first())
            assertTrue("$type 的默认布局没有排行榜", shelves.any { it.title == ExploreTitle.Ranking })
            assertEquals(
                "$type 的默认布局与画出来的货架对不上（有货架被跳过了）",
                defaultShelfLayout(type).size + 1,
                shelves.size,
            )
        }
    }

    @Test
    fun `默认布局里货架 id 不重复，也不撞近期热门`() {
        for (type in types) {
            val ids = defaultShelves(type).map { it.id }
            assertEquals("$type 的货架 id 有重复：$ids", ids.toSet().size, ids.size)
            assertEquals(TRENDING_SHELF_ID, ids.first())
        }
    }

    @Test
    fun `同一类型的默认作品货架没有两条条件相同`() {
        for (type in types) {
            val normalized = workCriteriaOf(defaultShelves(type))
                .map { (normalizeSearchCriteria(it) as SearchValidation.Valid).value }
            val duplicates = normalized.groupBy { it }.filterValues { it.size > 1 }
            assertTrue("$type 出现条件重复的作品货架：${duplicates.keys}", duplicates.isEmpty())
        }
    }

    @Test
    fun `每组方块候选里没有两块条件相同或键相同`() {
        for (type in types) {
            for (source in TileSet.entries.map { TileShelfSource.Fixed(it) } + TileShelfSource.Custom("任意")) {
                val tiles = tileCandidates(type, source)
                val keys = tiles.map { it.label.key }
                assertEquals("$type $source 的方块键有重复：$keys", keys.toSet().size, keys.size)
                val criteria = tiles.map { (normalizeSearchCriteria(it.criteria) as SearchValidation.Valid).value }
                assertEquals("$type $source 有两块条件相同", criteria.toSet().size, criteria.size)
            }
        }
    }

    @Test
    fun `只有动画有今日放送与番剧回顾`() {
        for (type in types) {
            val titles = defaultShelves(type).map { it.title }
            val anime = type == SubjectType.ANIME
            assertEquals("$type 今日放送", anime, ExploreTitle.TodayOnAir in titles)
            assertEquals("$type 番剧回顾", anime, titles.any { it is ExploreTitle.CatchUpSeason })
        }
    }

    // —— 5. 布局 → 货架

    @Test
    fun `隐藏的货架不画`() {
        val layout = listOf(
            fixedWorkShelfEntry(FixedWorkShelf.RANKING).copy(hidden = true),
            tileSetShelfEntry(TileSet.DECADE),
        )
        val shelves = exploreShelvesFor(SubjectType.ANIME, today, layout)
        assertEquals(listOf(TRENDING_SHELF_ID, "tiles:DECADE"), shelves.map { it.id })
    }

    @Test
    fun `分类用不了的固定货架、目录里没有的预设、没有方块的方块货架都跳过`() {
        val layout = listOf(
            fixedWorkShelfEntry(FixedWorkShelf.TODAY_ON_AIR),
            WorkShelfEntry("preset:不存在", WorkShelfSource.Preset("不存在", SearchSort.RANK, name = null), WorkShelfStyle.POSTER_ROW),
            tileSetShelfEntry(TileSet.COMPANY).copy(id = "tiles:empty", tileKeys = emptyList()),
            tileSetShelfEntry(TileSet.REGION),
            TileShelfEntry("custom:empty", TileShelfSource.Custom("空的"), tileKeys = null, drawing = TileDrawing.COLOR),
        )
        // 游戏：没有今日放送、没有地区一组；公司货架的方块全删了
        assertEquals(listOf(TRENDING_SHELF_ID), exploreShelvesFor(SubjectType.GAME, today, layout).map { it.id })
    }

    @Test
    fun `预设货架按存下来的名字与排序，词跟着目录`() {
        val preset = checkNotNull(shelfPresetOf(SubjectType.ANIME, "奇幻+战斗"))
        val entry = WorkShelfEntry(
            id = "preset:奇幻+战斗",
            source = WorkShelfSource.Preset(preset.id, SearchSort.RANK, name = "打打杀杀"),
            style = WorkShelfStyle.DECK,
        )
        val shelf = exploreShelvesFor(SubjectType.ANIME, today, listOf(entry)).last() as ExploreShelf.Works
        assertEquals(ExploreTitle.Named("打打杀杀"), shelf.title)
        assertEquals(WorkShelfContent.Search(preset.criteria.copy(sort = SearchSort.RANK)), shelf.content)
        assertEquals(WorkShelfStyle.DECK, shelf.style)

        val unnamed = entry.copy(source = WorkShelfSource.Preset(preset.id, SearchSort.COLLECTS, name = null))
        assertEquals(
            ExploreTitle.Named("奇幻战斗"),
            exploreShelvesFor(SubjectType.ANIME, today, listOf(unnamed)).last().title,
        )
    }

    @Test
    fun `自己建的作品货架用存下来的整份条件与名字`() {
        val criteria = SearchCriteria(subjectType = SubjectType.BOOK, sort = SearchSort.RANK)
            .pickWord("漫画", WordPick.ADD)
            .let { it.copy(filters = it.filters.copy(airtime = SearchAirtime(2010, 2019))) }
        val entry = WorkShelfEntry("custom:1", WorkShelfSource.Custom(criteria, "十年代漫画"), WorkShelfStyle.TIMELINE)
        val shelf = exploreShelvesFor(SubjectType.BOOK, today, listOf(entry)).last() as ExploreShelf.Works
        assertEquals(ExploreShelf.Works("custom:1", ExploreTitle.Named("十年代漫画"), WorkShelfContent.Search(criteria), WorkShelfStyle.TIMELINE), shelf)
    }

    @Test
    fun `方块货架按存下来的键与顺序，候选池里没有的键跳过`() {
        val entry = tileSetShelfEntry(TileSet.THEME).copy(tileKeys = listOf("word:热血", "word:不存在", "word:恋爱", "word:热血"))
        val shelf = exploreShelvesFor(SubjectType.ANIME, today, listOf(entry)).last() as ExploreShelf.Tiles
        assertEquals(listOf(TileLabel.Word("热血"), TileLabel.Word("恋爱")), shelf.tiles.map { it.label })
    }

    @Test
    fun `年代货架不管存的顺序，始终从新到旧`() {
        val entry = tileSetShelfEntry(TileSet.DECADE).copy(tileKeys = listOf("decade:1990", "decade:2020", "decade:2000"))
        val shelf = exploreShelvesFor(SubjectType.ANIME, today, listOf(entry)).last() as ExploreShelf.Tiles
        assertEquals(listOf(2020, 2000, 1990), shelf.tiles.map { (it.label as TileLabel.Decade).startYear })
    }

    @Test
    fun `题材默认是候选池的前十个`() {
        for (type in types) {
            val candidates = tileCandidates(type, TileShelfSource.Fixed(TileSet.THEME))
            val shelf = defaultShelves(type).filterIsInstance<ExploreShelf.Tiles>().firstOrNull { it.title == ExploreTitle.ThemeBrowse }
                ?: continue
            assertEquals("$type 的题材默认方块", candidates.take(10), shelf.tiles)
        }
    }

    @Test
    fun `自己建的方块货架从预设目录里挑，一条预设一块`() {
        val preset = checkNotNull(shelfPresetOf(SubjectType.GAME, "Nintendo Switch+RPG"))
        val entry = TileShelfEntry(
            id = "custom:tiles",
            source = TileShelfSource.Custom("常玩的"),
            tileKeys = listOf(TileLabel.Preset(preset.id, preset.name).key, "word:RPG"),
            drawing = TileDrawing.LOGO,
        )
        val shelf = exploreShelvesFor(SubjectType.GAME, today, listOf(entry)).last() as ExploreShelf.Tiles
        assertEquals(ExploreTitle.Named("常玩的"), shelf.title)
        // 「word:RPG」是玩法一组的键，不在预设目录里
        assertEquals(listOf(ExploreTile(TileLabel.Preset(preset.id, preset.name), preset.name, preset.criteria)), shelf.tiles)
        // 自己建的用不了标志，画成色块
        assertEquals(TileDrawing.COLOR, shelf.drawing)
    }

    @Test
    fun `标志只给公司与平台，刻度尺只给年代`() {
        val base = listOf(TileDrawing.COLOR, TileDrawing.STACK, TileDrawing.COVER)
        for (set in TileSet.entries) {
            val expected = base + listOfNotNull(
                TileDrawing.LOGO.takeIf { set == TileSet.COMPANY || set == TileSet.PLATFORM },
                TileDrawing.RULER.takeIf { set == TileSet.DECADE },
            )
            assertEquals("$set 的画法", expected, tileDrawingsFor(TileShelfSource.Fixed(set)))
            assertTrue("$set 的默认画法必须是它能用的", set.defaultDrawing in tileDrawingsFor(TileShelfSource.Fixed(set)))
        }
        assertEquals(base, tileDrawingsFor(TileShelfSource.Custom("任意")))
    }

    @Test
    fun `平台方块一个平台一块，带它的图形与平台词`() {
        val tiles = tileCandidates(SubjectType.GAME, TileShelfSource.Fixed(TileSet.PLATFORM))
        assertEquals(GamePlatform.entries.map { TileLogo.Platform(it) }, tiles.map { it.logo })
        assertEquals(GamePlatform.entries.map { TileLabel.Word(it.word) }, tiles.map { it.label })
        assertTrue("只有游戏有平台方块", tileCandidates(SubjectType.BOOK, TileShelfSource.Fixed(TileSet.PLATFORM)).isEmpty())
    }

    @Test
    fun `词方块按收藏数取作品，副标题是同一个词的预设货架名`() {
        val genre = tileCandidates(SubjectType.GAME, TileShelfSource.Fixed(TileSet.GENRE))
        assertTrue("词方块按收藏数排", genre.all { it.criteria.sort == SearchSort.COLLECTS })
        val subtitles = genre.associate { it.label to it.subtitle }
        assertEquals("角色扮演", subtitles[TileLabel.Word("RPG")])
        assertEquals("格斗", subtitles[TileLabel.Word("FTG")])
        assertEquals("预设名与词一样时没有副标题", null, subtitles[TileLabel.Word("Roguelike")])
        val region = tileCandidates(SubjectType.REAL, TileShelfSource.Fixed(TileSet.REGION)).single { it.label == TileLabel.Word("英国") }
        assertEquals("没有这个词的预设时没有副标题", null, region.subtitle)
    }

    @Test
    fun `玩法默认显示前十二块大类，细分的留在加方块里`() {
        val source = TileShelfSource.Fixed(TileSet.GENRE)
        val shown = defaultTileKeys(SubjectType.GAME, source)
        val all = tileCandidates(SubjectType.GAME, source).map { it.label.key }

        assertEquals(12, shown.size)
        assertEquals(all.take(12), shown)
        assertTrue(listOf("SIM", "RAC", "FPS", "SPG").all { TileLabel.Word(it).key in shown })
        assertTrue(listOf("ARPG", "MUG", "VN").all { TileLabel.Word(it).key in all && TileLabel.Word(it).key !in shown })
    }

    @Test
    fun `每一组预设方块默认都有图`() {
        for (set in TileSet.entries) {
            assertTrue("$set 默认画成了没有图的色块", set.defaultDrawing.needs != TileNeed.NOTHING)
        }
    }

    @Test
    fun `没有探索页的分类没有货架`() {
        assertTrue(defaultShelfLayout(SubjectType.UNKNOWN).isEmpty())
        assertTrue(exploreShelvesFor(SubjectType.UNKNOWN, today, defaultShelfLayout(SubjectType.ANIME)).isEmpty())
    }

    // —— 方块的大字（big）：年代缩写、缩写表、公司缩写

    /**
     * 年代方块的大字必须是两位加 s。**2000 年代是「00s」**，`2000 % 100` 直接拼会得到「0s」，
     * 这条就是钉死它（补足两位，不是特判 2000）。同时核对大字、名字、年份范围三者同源。
     */
    @Test
    fun `年代方块的大字补齐两位且与名字年份范围一致`() {
        val sixDecades = listOf(2020 to "20s", 2010 to "10s", 2000 to "00s", 1990 to "90s", 1980 to "80s", 1970 to "70s")
        val fiveDecades = sixDecades.dropLast(1)
        for (type in types) {
            val expected = if (type == SubjectType.GAME || type == SubjectType.MUSIC) fiveDecades else sixDecades
            val tiles = tilesOf(type, ExploreTitle.DecadeBrowse)
            assertEquals("$type 的年代方块个数不对", expected.size, tiles.size)
            assertEquals("$type 的年代大字序列不对", expected.map { it.second }, tiles.map { it.big })
            tiles.forEachIndexed { index, tile ->
                val (start, big) = expected[index]
                assertEquals("$type 的「$big」名字不对", TileLabel.Decade(start), tile.label)
                val airtime = tile.criteria.filters.airtime
                assertEquals("$type 的「$big」起始年不对", start, airtime?.startYear)
                assertEquals("$type 的「$big」结束年不对", start + 9, airtime?.endYear)
            }
        }
    }

    /** 平台方块：放不下整名的两个用缩写，表里没有的词大字就是它本身。 */
    @Test
    fun `平台方块的长名字用缩写大字`() {
        val bigByLabel = tilesOf(SubjectType.GAME, ExploreTitle.PlatformBrowse).associate { it.label to it.big }
        assertEquals("NS", bigByLabel[TileLabel.Word("Nintendo Switch")])
        assertEquals("XSX", bigByLabel[TileLabel.Word("Xbox Series X/S")])
        assertEquals("PS5", bigByLabel[TileLabel.Word("PS5")])
        assertEquals("PC", bigByLabel[TileLabel.Word("PC")])
        assertEquals("Android", bigByLabel[TileLabel.Word("Android")])
    }

    /** 公司方块的大字：名字里的字母数字取前两个。 */
    @Test
    fun `公司方块的大字是名字里的前两个字母数字`() {
        val bigByLabel = tilesOf(SubjectType.ANIME, ExploreTitle.CompanyBrowse(SubjectType.ANIME)).associate { it.label to it.big }
        assertEquals("SU", bigByLabel[TileLabel.Word("SUNRISE")])
        assertEquals("BO", bigByLabel[TileLabel.Word("BONES")])
        assertEquals("京都", bigByLabel[TileLabel.Word("京都动画")])
        assertEquals("MA", bigByLabel[TileLabel.Word("MAPPA")])
        assertEquals("uf", bigByLabel[TileLabel.Word("ufotable")])
        assertEquals("A1", bigByLabel[TileLabel.Word("A-1 Pictures")])
        assertEquals("PA", bigByLabel[TileLabel.Word("P.A.WORKS")])
        assertEquals("JC", bigByLabel[TileLabel.Word("J.C.STAFF")])
        assertEquals("Br", bigByLabel[TileLabel.Word("Brain's Base")])
        assertEquals("8b", bigByLabel[TileLabel.Word("8bit")])
    }

    // —— 年表：按年分组

    /** 年份从早到晚；同一年里保持原来的先后（热度顺序），没有日期、日期认不出年份的不上年表。 */
    @Test
    fun `年表按年从早到晚分组，年内保持原顺序，没有年份的不上`() {
        fun work(id: Int, date: String?) = SubjectBo(id, SubjectType.ANIME, name = "作品$id", nameCn = "", date = date)
        val subjects = listOf(
            work(1, "2019-04-05"),
            work(2, "2008-10-02"),
            work(3, null),
            work(4, "2019-01-10"),
            work(5, "未定"),
            work(6, "2008年"),
        )
        val years = timelineYearsOf(subjects)
        assertEquals(listOf(2008, 2019), years.map { it.year })
        assertEquals(listOf(listOf(2, 6), listOf(1, 4)), years.map { year -> year.works.map { it.id } })
        assertTrue(timelineYearsOf(listOf(work(7, null))).isEmpty())
    }

    /** 某一组方块的全部候选（默认就是整组的那几组用它看默认方块）。 */
    private fun tilesOf(type: SubjectType, title: ExploreTitle): List<ExploreTile> =
        TileSet.entries.single { it.title(type) == title }.let { tileCandidates(type, TileShelfSource.Fixed(it)) }

    /** 作品货架里的搜索条件（今日放送没有条件；近期热门也算）。 */
    private fun workCriteriaOf(shelves: List<ExploreShelf>): List<SearchCriteria> = shelves.mapNotNull { shelf ->
        when (shelf) {
            is ExploreShelf.Trending -> shelf.criteria
            is ExploreShelf.Works -> (shelf.content as? WorkShelfContent.Search)?.criteria
            is ExploreShelf.Tiles -> null
        }
    }
}
