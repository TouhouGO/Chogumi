package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectTypeSpecTest {

    @Test
    fun `第 2 个选项卡与进度单位按规格表`() {
        val table = SubjectType.entries.associateWith { subjectTypeSpec(it).secondTab to subjectTypeSpec(it).progressUnit }
        assertEquals(SubjectSecondTab.EPISODES to ProgressUnit.EPISODE, table[SubjectType.ANIME])
        assertEquals(SubjectSecondTab.VOLUMES to ProgressUnit.VOLUME, table[SubjectType.BOOK])
        assertEquals(null to null, table[SubjectType.GAME])
        assertEquals(SubjectSecondTab.TRACKS to null, table[SubjectType.MUSIC])
        assertEquals(SubjectSecondTab.EPISODES to ProgressUnit.EPISODE, table[SubjectType.REAL])
    }

    @Test
    fun `进度单位到书籍单位：卷固定记卷，话不指定`() {
        assertEquals(BookUnit.VOL, ProgressUnit.VOLUME.bookUnitOverride)
        assertNull(ProgressUnit.EPISODE.bookUnitOverride)
    }

    @Test
    fun `动词：看 读 听 玩，三次元与未知同动画`() {
        assertEquals(
            mapOf(
                SubjectType.UNKNOWN to SubjectVerb.WATCH,
                SubjectType.BOOK to SubjectVerb.READ,
                SubjectType.ANIME to SubjectVerb.WATCH,
                SubjectType.MUSIC to SubjectVerb.LISTEN,
                SubjectType.GAME to SubjectVerb.PLAY,
                SubjectType.REAL to SubjectVerb.WATCH,
            ),
            SubjectType.entries.associateWith { subjectTypeSpec(it).verb },
        )
    }

    @Test
    fun `还没拿到类型按动画排版`() {
        assertEquals(subjectTypeSpec(SubjectType.ANIME), subjectTypeSpec(null))
    }

    @Test
    fun `不认识的类型版式同动画，但没有进度、没有第二个选项卡，也不算阵容`() {
        val unknown = subjectTypeSpec(SubjectType.UNKNOWN)
        assertEquals(subjectTypeSpec(SubjectType.ANIME).copy(secondTab = null, progressUnit = null, studioPosition = null), unknown)
        assertFalse(EpisodeTracking.hasProgress(SubjectType.UNKNOWN))
        assertFalse(EpisodeTracking.tracksEpisodes(SubjectType.UNKNOWN))
        assertFalse(EpisodeTracking.tracksCounts(SubjectType.UNKNOWN))
    }

    @Test
    fun `进度判定都从规格表的进度单位派生`() {
        for (type in SubjectType.entries) {
            val unit = subjectTypeSpec(type).progressUnit
            assertEquals("$type hasProgress", unit != null, EpisodeTracking.hasProgress(type))
            assertEquals("$type tracksEpisodes", unit == ProgressUnit.EPISODE, EpisodeTracking.tracksEpisodes(type))
            assertEquals("$type tracksCounts", unit == ProgressUnit.VOLUME, EpisodeTracking.tracksCounts(type))
        }
        assertTrue(EpisodeTracking.tracksCounts(SubjectType.BOOK))
        assertTrue(EpisodeTracking.tracksEpisodes(SubjectType.REAL))
        assertFalse(EpisodeTracking.hasProgress(SubjectType.GAME))
        assertFalse(EpisodeTracking.hasProgress(SubjectType.MUSIC))
    }

    @Test
    fun `主创顺序按规格表`() {
        assertEquals(
            listOf("原作", "导演", "系列构成", "脚本", "人物设定", "音乐", "动画制作"),
            subjectTypeSpec(SubjectType.ANIME).keyPositions,
        )
        assertEquals(listOf("作者", "插图", "出版社", "连载杂志", "品牌"), subjectTypeSpec(SubjectType.BOOK).keyPositions)
        assertEquals(listOf("开发", "发行", "剧本", "原画", "音乐"), subjectTypeSpec(SubjectType.GAME).keyPositions)
        assertEquals(listOf("艺术家", "作词", "作曲", "编曲", "录音", "发行方"), subjectTypeSpec(SubjectType.MUSIC).keyPositions)
        assertEquals(listOf("导演", "编剧", "主演", "电视台"), subjectTypeSpec(SubjectType.REAL).keyPositions)
    }

    private fun subject(type: SubjectType, platform: String = "", date: String? = null, eps: Int? = null,
        volumes: Int? = null, totalEpisodes: Int? = null, infobox: List<SubjectInfoboxBo> = emptyList()) =
        SubjectBo(id = 1, type = type, name = "n", nameCn = "", platform = platform, date = date, eps = eps,
            volumes = volumes, totalEpisodes = totalEpisodes, infobox = infobox)

    @Test
    fun `动画元信息行：形式 · 年月 · 话数 · 制作公司`() {
        val parts = subjectMetaParts(
            subject(SubjectType.ANIME, platform = "TV", date = "1998-10-23", eps = 26, totalEpisodes = 31,
                infobox = listOf(SubjectInfoboxBo("播放电视台", listOf("WOWOW")), SubjectInfoboxBo("动画制作", listOf("サンライズ")))),
        )
        assertEquals(
            listOf(
                SubjectMetaPart.Text("TV"),
                SubjectMetaPart.Date(SubjectYearMonth(1998, 10)),
                SubjectMetaPart.Count(SubjectCountUnit.EPISODE, 26),
                SubjectMetaPart.InfoboxValue("サンライズ"),
            ),
            parts,
        )
    }

    @Test
    fun `三次元用电视台代替制作，两种键名按顺序取第一个有值的`() {
        val tv = subject(SubjectType.REAL, platform = "日剧", infobox = listOf(
            SubjectInfoboxBo("动画制作", listOf("不该出现")),
            SubjectInfoboxBo("电视台", listOf(" ")),
            SubjectInfoboxBo("播放电视台", listOf("TBS", "WOWOW")),
        ))
        assertEquals(listOf(SubjectMetaPart.Text("日剧"), SubjectMetaPart.InfoboxValue("TBS、WOWOW")), subjectMetaParts(tv))
    }

    @Test
    fun `游戏：类型名 · 平台 · 年月；音乐：类型名 · 年月 · 曲数`() {
        val game = subject(SubjectType.GAME, platform = "游戏", date = "2020-04-10",
            infobox = listOf(SubjectInfoboxBo("平台", listOf("PS4", "PC"))))
        assertEquals(
            listOf(
                SubjectMetaPart.TypeName(SubjectType.GAME),
                SubjectMetaPart.InfoboxValue("PS4、PC"),
                SubjectMetaPart.Date(SubjectYearMonth(2020, 4)),
            ),
            subjectMetaParts(game),
        )
        val music = subject(SubjectType.MUSIC, date = "2026-07-02", eps = 0, totalEpisodes = 12)
        assertEquals(
            listOf(
                SubjectMetaPart.TypeName(SubjectType.MUSIC),
                SubjectMetaPart.Date(SubjectYearMonth(2026, 7)),
                SubjectMetaPart.Count(SubjectCountUnit.TRACK, 12),
            ),
            subjectMetaParts(music),
        )
    }

    @Test
    fun `书籍取卷数与出版社；未知的段跳过不留空位`() {
        val book = subject(SubjectType.BOOK, platform = "漫画", date = null, volumes = 0,
            infobox = listOf(SubjectInfoboxBo("出版社", listOf("集英社"))))
        assertEquals(listOf(SubjectMetaPart.Text("漫画"), SubjectMetaPart.InfoboxValue("集英社")), subjectMetaParts(book))
        val withVolumes = book.copy(volumes = 3, date = "2017年1月")
        assertEquals(
            listOf(
                SubjectMetaPart.Text("漫画"),
                SubjectMetaPart.Date(SubjectYearMonth(2017, 1)),
                SubjectMetaPart.Count(SubjectCountUnit.VOLUME, 3),
                SubjectMetaPart.InfoboxValue("集英社"),
            ),
            subjectMetaParts(withVolumes),
        )
    }

    private fun person(id: Int, position: String) =
        RelatedPersonBo(id = id, name = "p$id", type = 1, relation = position)

    @Test
    fun `职位行：主创按规格表在前，其余按接口首次出现顺序`() {
        val persons = listOf(
            person(1, "音乐监督"),
            person(2, "动画制作"),
            person(3, "原画"),
            person(4, "导演"),
            person(5, "原画"),
            person(6, "原作"),
            person(7, " "),
        )
        val rows = staffPositionRows(persons, SubjectType.ANIME)
        assertEquals(listOf("原作", "导演", "动画制作", "音乐监督", "原画"), rows.map { it.position })
        assertEquals(listOf(3, 5), rows.single { it.position == "原画" }.persons.map { it.id })
    }
}
