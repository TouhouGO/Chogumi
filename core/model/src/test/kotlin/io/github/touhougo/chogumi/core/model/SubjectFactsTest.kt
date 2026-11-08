package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectFactsTest {

    private fun subject(infobox: List<SubjectInfoboxBo> = emptyList(), metaTags: List<String> = emptyList(), tags: List<TagBo> = emptyList()) =
        SubjectBo(id = 1, type = SubjectType.ANIME, name = "幼女戦記", nameCn = "幼女战记", infobox = infobox, metaTags = metaTags, tags = tags)

    @Test
    fun `资料白名单不依赖关联职位，空值不列，中文名与显示标题相同不列`() {
        val info = subjectInfoPartition(
            subject(
                listOf(
                    SubjectInfoboxBo("中文名", listOf("幼女战记")),
                    SubjectInfoboxBo("别名", listOf("谭雅战记", "Saga of Tanya the Evil")),
                    SubjectInfoboxBo("话数", listOf("12")),
                    SubjectInfoboxBo("导演", listOf("上村泰")),
                    SubjectInfoboxBo("动画制作", listOf("NUT")),
                    SubjectInfoboxBo("官方网站", listOf(" ")),
                    SubjectInfoboxBo(" ", listOf("空键")),
                    SubjectInfoboxBo("无值", emptyList()),
                ),
            ),
            displayedTitle = "幼女战记",
            linkedStaffPositions = setOf(" 导演 ", "动画制作", "话数"),
        )
        assertEquals(listOf(FactRow("别名", "谭雅战记、Saga of Tanya the Evil"), FactRow("话数", "12")), info.workFacts)
        assertEquals(emptyList<FactRow>(), info.otherStaffFacts)
    }

    @Test
    fun `中文名只与传入的显示标题比较，支持标题回落到原名`() {
        val item = subject(listOf(SubjectInfoboxBo("中文名", listOf("幼女戦記")))).copy(nameCn = "")
        assertEquals(emptyList<FactRow>(), subjectInfoPartition(item, "幼女戦記", emptySet()).workFacts)
        assertEquals(listOf(FactRow("中文名", "幼女戦記")), subjectInfoPartition(item, "另一个译名", emptySet()).workFacts)
    }

    @Test
    fun `没有关联人员的制作职位归其他制作人员，保持原文顺序`() {
        val positions = listOf("设定制作助理", "音响制作担当", "离线剪辑", "2D 设计", "美术监督助理")
        val info = subjectInfoPartition(
            subject(positions.map { SubjectInfoboxBo(it, listOf(" 人名甲 ", "人名乙")) }),
            displayedTitle = "幼女战记",
            linkedStaffPositions = emptySet(),
        )
        assertEquals(emptyList<FactRow>(), info.workFacts)
        assertEquals(positions.map { FactRow(it, "人名甲、人名乙") }, info.otherStaffFacts)
    }

    @Test
    fun `出版社连载杂志厂牌与未知项归人员，关联职位仅去重其他人员`() {
        val info = subjectInfoPartition(
            subject(listOf(
                SubjectInfoboxBo(" 出版社 ", listOf("出版社甲")),
                SubjectInfoboxBo("连载杂志", listOf("杂志甲")),
                SubjectInfoboxBo("厂牌", listOf("公司甲")),
                SubjectInfoboxBo("未知职位", listOf("人名甲")),
                SubjectInfoboxBo("其他出版社", listOf("出版社乙")),
                SubjectInfoboxBo("Website", listOf("未经证实的键")),
                SubjectInfoboxBo("Copyright", listOf("公司乙")),
            )),
            displayedTitle = "幼女战记",
            linkedStaffPositions = setOf(" 连载杂志 ", "未知职位", "Copyright", ""),
        )
        assertEquals(listOf(FactRow("Copyright", "公司乙")), info.workFacts)
        assertEquals(listOf(
            FactRow("出版社", "出版社甲"), FactRow("厂牌", "公司甲"),
            FactRow("其他出版社", "出版社乙"), FactRow("Website", "未经证实的键"),
        ), info.otherStaffFacts)
    }

    @Test
    fun `官方53键全部进入作品资料，不受作品类型或关联职位限制`() {
        val keys = listOf(
            "中文名", "别名", "话数", "放送开始", "放送星期", "官方网站", "在线播放平台", "播放电视台",
            "其他电视台", "播放结束", "链接", "Copyright", "发售日", "开始", "结束", "上映年度", "片长",
            "价格", "页数", "ISBN", "册数", "发售日期", "版本特性", "播放时长", "碟片数量", "平台",
            "游戏类型", "游戏引擎", "游玩人数", "发行日期", "售价", "website", "集数", "季数", "类型",
            "国家/地区", "语言", "每集长", "电视网", "电视台", "频道", "视频制式", "音频制式", "首播国家",
            "首播地区", "台湾名称", "港澳名称", "马新名称", "imdb_id", "tvdb_id", "上映日", "tmdb_id", "尺寸",
        )
        assertEquals(53, keys.size)
        SubjectType.entries.forEach { type ->
            val info = subjectInfoPartition(
                subject(keys.map { SubjectInfoboxBo(" $it ", listOf(" 值 ")) }).copy(type = type),
                displayedTitle = "幼女战记",
                linkedStaffPositions = keys.toSet(),
            )
            assertEquals(keys.map { FactRow(it, "值") }, info.workFacts)
            assertEquals(emptyList<FactRow>(), info.otherStaffFacts)
        }
    }

    @Test
    fun `标签行：形式标签在前，用户标签去掉与形式标签、标题、原名同名的和重复的`() {
        val row = subjectTagRow(
            subject(
                metaTags = listOf("TV", "日本", "TV"),
                tags = listOf(TagBo("战斗", 900), TagBo("TV", 800), TagBo("幼女战记", 500), TagBo("幼女戦記", 400), TagBo("战斗", 10), TagBo("穿越", 300)),
            ),
        )
        assertEquals(
            listOf(
                SubjectTagChip("TV", true), SubjectTagChip("日本", true),
                SubjectTagChip("战斗", false), SubjectTagChip("穿越", false),
            ),
            row,
        )
    }

    private fun ep(id: Int, sort: Double, disc: Int, type: Int = 0) =
        EpisodeBo(id = id, subjectId = 1, type = type, name = "t$id", nameCn = "", sort = sort, disc = disc)

    @Test
    fun `曲目按碟分组：碟号升序、碟内按 sort，非正片不列`() {
        val discs = trackDiscs(listOf(ep(1, 2.0, 2), ep(2, 1.0, 2), ep(3, 1.0, 1), ep(4, 3.0, 1, type = 1)))
        assertEquals(listOf(1, 2), discs.map { it.disc })
        assertEquals(listOf(3), discs[0].tracks.map { it.id })
        assertEquals(listOf(2, 1), discs[1].tracks.map { it.id })
    }

    @Test
    fun `单行本：只取单行本关联，按卷号升序`() {
        fun rel(id: Int, relationId: Int, order: Int) =
            SubjectRelationBo(id = id, type = SubjectType.BOOK, name = "v$id", nameCn = "", relationId = relationId, order = order, score = 0.0)
        val volumes = subjectVolumes(listOf(rel(1, 1003, 3), rel(2, 1, 0), rel(3, 1003, 1), rel(4, 1003, 2)))
        assertEquals(listOf(3, 4, 1), volumes.map { it.id })
    }
}
