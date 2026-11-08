package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设目录（[shelfPresetsFor]）的守卫测试。
 *
 * 目录的每条词都在服务端实测过（2026-10-23，匿名，p1 搜索、不含 NSFW；游戏的益智、格斗、模拟、休闲、竞速、体育 2026-11-04 同样实测，
 * 按 `meta_tags` 搜）。[MEASURED] 记下当时每条的落点与部数：
 * - 落点（每个词经 [resolveTagInput] 落成维度 `D:<维度>` 还是用户标签 `U`）必须与实测时一致——
 *   维度词典改了、词落到别处时，部数就不再可信，要重测；
 * - 部数必须 ≥ [MIN_WORKS]，空的或太少的货架不进目录；
 * - 目录与记录一一对应：加一条预设必须先实测、记进来。
 */
class ExploreShelfCatalogTest {

    private val types = SubjectType.entries.filter { it.concreteOrNull() != null }

    @Test
    fun `目录与实测记录一一对应`() {
        for (type in types) {
            val measured = MEASURED.filter { it.type == type }.map { it.id }
            assertEquals("$type", measured, shelfPresetsFor(type).map { it.id })
        }
    }

    @Test
    fun `每个词的落点与实测时一致`() {
        for (row in MEASURED) {
            val preset = checkNotNull(shelfPresetOf(row.type, row.id)) { "${row.type} 没有预设「${row.id}」" }
            val landing = preset.words.joinToString("/") { word ->
                when (val resolved = resolveTagInput(row.type, word)) {
                    is TagInputResolution.Dimension -> "D:${resolved.dimension.name}"
                    is TagInputResolution.UserTag -> "U"
                    null -> "空"
                }
            }
            assertEquals("${row.type} 的「${row.id}」落点变了，要重测部数", row.landing, landing)
        }
    }

    @Test
    fun `每条实测都不少于十部`() {
        for (row in MEASURED) {
            assertTrue("${row.type} 的「${row.id}」只有 ${row.works} 部", row.works >= MIN_WORKS)
        }
    }

    @Test
    fun `每条的排序都在这组条件能用的排序里`() {
        for (type in types) {
            for (preset in shelfPresetsFor(type)) {
                assertTrue(
                    "$type 的「${preset.id}」按 ${preset.sort} 排，可这组条件只能用 ${preset.criteria.availableSorts()}",
                    preset.sort in preset.criteria.availableSorts(),
                )
            }
        }
    }

    @Test
    fun `同一分类里 id 与名字都不重复`() {
        for (type in types) {
            val presets = shelfPresetsFor(type)
            val ids = presets.map { it.id }
            assertEquals("$type 的预设 id 有重复：$ids", ids.toSet().size, ids.size)
            val names = presets.map { it.name }
            assertEquals("$type 的预设名字有重复：$names", names.toSet().size, names.size)
        }
    }

    @Test
    fun `条件就是把词逐个 pickWord 进空条件`() {
        for (type in types) {
            for (preset in shelfPresetsFor(type)) {
                val criteria = preset.criteria
                assertEquals("$type 的「${preset.id}」类型", type, criteria.subjectType)
                assertEquals("$type 的「${preset.id}」排序", preset.sort, criteria.sort)
                for (word in preset.words) {
                    assertTrue("$type 的「${preset.id}」没选上「$word」", criteria.isWordSelected(word))
                }
                assertTrue("$type 的「${preset.id}」不该带关键词或时间", criteria.query.isEmpty() && criteria.filters.airtime == null)
            }
        }
    }

    @Test
    fun `没有探索页的分类没有预设`() {
        assertTrue(shelfPresetsFor(SubjectType.UNKNOWN).isEmpty())
    }

    /** 一条实测：分类、预设 id、落点、部数。 */
    private data class Row(val type: SubjectType, val id: String, val landing: String, val works: Int)

    private companion object {
        const val MIN_WORKS = 10

        /** 实测记录，顺序同目录。部数封顶 1000（搜索接口的 total 上限）。 */
        val MEASURED = listOf(
        Row(SubjectType.ANIME, "奇幻", "D:GENRE", 1000),
        Row(SubjectType.ANIME, "异世界", "U", 800),
        Row(SubjectType.ANIME, "奇幻+战斗", "D:GENRE/D:GENRE", 525),
        Row(SubjectType.ANIME, "冒险+奇幻", "D:GENRE/D:GENRE", 179),
        Row(SubjectType.ANIME, "异世界+转生", "U/U", 136),
        Row(SubjectType.ANIME, "魔法少女", "U", 403),
        Row(SubjectType.ANIME, "恋爱", "D:GENRE", 849),
        Row(SubjectType.ANIME, "恋爱+搞笑", "D:GENRE/U", 395),
        Row(SubjectType.ANIME, "后宫", "D:GENRE", 431),
        Row(SubjectType.ANIME, "校园+恋爱", "D:GENRE/D:GENRE", 273),
        Row(SubjectType.ANIME, "女性向", "D:AUDIENCE", 57),
        Row(SubjectType.ANIME, "战斗", "D:GENRE", 1000),
        Row(SubjectType.ANIME, "热血", "U", 1000),
        Row(SubjectType.ANIME, "战斗+热血", "D:GENRE/U", 632),
        Row(SubjectType.ANIME, "机战", "D:GENRE", 302),
        Row(SubjectType.ANIME, "校园", "D:GENRE", 612),
        Row(SubjectType.ANIME, "青春", "U", 714),
        Row(SubjectType.ANIME, "日常", "D:GENRE", 667),
        Row(SubjectType.ANIME, "治愈", "U", 1000),
        Row(SubjectType.ANIME, "日常+治愈", "D:GENRE/U", 377),
        Row(SubjectType.ANIME, "美食", "D:GENRE", 68),
        Row(SubjectType.ANIME, "催泪", "U", 284),
        Row(SubjectType.ANIME, "搞笑", "U", 1000),
        Row(SubjectType.ANIME, "剧场版", "D:CATEGORY", 1000),
        Row(SubjectType.ANIME, "剧场版+原创", "D:CATEGORY/D:SOURCE", 860),
        Row(SubjectType.ANIME, "科幻", "D:GENRE", 979),
        Row(SubjectType.ANIME, "机战+科幻", "D:GENRE/D:GENRE", 132),
        Row(SubjectType.ANIME, "悬疑", "D:GENRE", 217),
        Row(SubjectType.ANIME, "推理", "D:GENRE", 127),
        Row(SubjectType.ANIME, "致郁", "U", 176),
        Row(SubjectType.ANIME, "惊悚", "D:GENRE", 42),
        Row(SubjectType.ANIME, "百合", "D:GENRE", 500),
        Row(SubjectType.ANIME, "运动", "D:GENRE", 210),
        Row(SubjectType.ANIME, "音乐", "D:GENRE", 163),
        Row(SubjectType.ANIME, "偶像", "U", 430),
        Row(SubjectType.ANIME, "乐队", "U", 70),
        Row(SubjectType.ANIME, "中国", "D:REGION", 1000),
        Row(SubjectType.ANIME, "中国+玄幻", "D:REGION/D:GENRE", 283),
        Row(SubjectType.ANIME, "中国+武侠", "D:REGION/D:GENRE", 90),
        Row(SubjectType.BOOK, "漫画+少年", "D:CATEGORY/D:AUDIENCE", 500),
        Row(SubjectType.BOOK, "漫画+热血", "D:CATEGORY/U", 378),
        Row(SubjectType.BOOK, "漫画+战斗", "D:CATEGORY/D:THEME", 386),
        Row(SubjectType.BOOK, "漫画+少女", "D:CATEGORY/D:AUDIENCE", 1000),
        Row(SubjectType.BOOK, "漫画+恋爱", "D:CATEGORY/D:THEME", 1000),
        Row(SubjectType.BOOK, "轻小说", "U", 1000),
        Row(SubjectType.BOOK, "轻小说+转生", "U/U", 217),
        Row(SubjectType.BOOK, "轻小说+奇幻", "U/D:THEME", 78),
        Row(SubjectType.BOOK, "轻小说+恋爱", "U/D:THEME", 83),
        Row(SubjectType.BOOK, "漫画+青年", "D:CATEGORY/D:AUDIENCE", 686),
        Row(SubjectType.BOOK, "漫画+猎奇", "D:CATEGORY/U", 291),
        Row(SubjectType.BOOK, "漫画+奇幻", "D:CATEGORY/D:THEME", 943),
        Row(SubjectType.BOOK, "漫画+科幻", "D:CATEGORY/D:THEME", 332),
        Row(SubjectType.BOOK, "漫画+连载中", "D:CATEGORY/D:PUBLICATION_STATUS", 1000),
        Row(SubjectType.BOOK, "漫画+已完结", "D:CATEGORY/D:PUBLICATION_STATUS", 1000),
        Row(SubjectType.BOOK, "小说+推理", "D:CATEGORY/D:THEME", 502),
        Row(SubjectType.BOOK, "小说+悬疑", "D:CATEGORY/D:THEME", 230),
        Row(SubjectType.BOOK, "小说+科幻", "D:CATEGORY/D:THEME", 655),
        Row(SubjectType.BOOK, "漫画+百合", "D:CATEGORY/D:THEME", 410),
        Row(SubjectType.BOOK, "漫画+GL", "D:CATEGORY/U", 291),
        Row(SubjectType.BOOK, "漫画+搞笑", "D:CATEGORY/D:THEME", 331),
        Row(SubjectType.BOOK, "漫画+治愈", "D:CATEGORY/U", 480),
        Row(SubjectType.BOOK, "漫画+日常", "D:CATEGORY/D:THEME", 277),
        Row(SubjectType.BOOK, "漫画+中国", "D:CATEGORY/D:REGION", 1000),
        Row(SubjectType.BOOK, "画集", "D:CATEGORY", 539),
        Row(SubjectType.GAME, "RPG", "D:GENRE", 1000),
        Row(SubjectType.GAME, "JRPG", "D:GENRE", 82),
        Row(SubjectType.GAME, "Nintendo Switch+RPG", "D:PLATFORM/D:GENRE", 559),
        Row(SubjectType.GAME, "SRPG", "D:GENRE", 604),
        Row(SubjectType.GAME, "ACT", "D:GENRE", 1000),
        Row(SubjectType.GAME, "ARPG", "D:GENRE", 1000),
        Row(SubjectType.GAME, "魂系", "U", 17),
        Row(SubjectType.GAME, "PS5+ACT", "D:PLATFORM/D:GENRE", 291),
        Row(SubjectType.GAME, "AVG", "D:GENRE", 1000),
        Row(SubjectType.GAME, "AVG+悬疑", "D:GENRE/U", 318),
        Row(SubjectType.GAME, "推理", "U", 568),
        Row(SubjectType.GAME, "Galgame", "U", 1000),
        Row(SubjectType.GAME, "纯爱", "U", 442),
        Row(SubjectType.GAME, "百合", "U", 1000),
        Row(SubjectType.GAME, "VN", "D:GENRE", 1000),
        Row(SubjectType.GAME, "独立游戏", "U", 1000),
        Row(SubjectType.GAME, "Roguelike", "D:GENRE", 561),
        Row(SubjectType.GAME, "开放世界", "U", 335),
        Row(SubjectType.GAME, "解谜", "U", 1000),
        Row(SubjectType.GAME, "PUZ", "D:GENRE", 1000),
        Row(SubjectType.GAME, "FTG", "D:GENRE", 662),
        Row(SubjectType.GAME, "FPS", "D:GENRE", 1000),
        Row(SubjectType.GAME, "STG", "D:GENRE", 1000),
        Row(SubjectType.GAME, "TPS", "D:GENRE", 459),
        Row(SubjectType.GAME, "SLG", "D:GENRE", 1000),
        Row(SubjectType.GAME, "战棋", "U", 316),
        Row(SubjectType.GAME, "恐怖", "U", 1000),
        Row(SubjectType.GAME, "MUG", "D:GENRE", 439),
        Row(SubjectType.GAME, "SIM", "D:GENRE", 1000),
        Row(SubjectType.GAME, "休闲", "D:GENRE", 1000),
        Row(SubjectType.GAME, "RAC", "D:GENRE", 1000),
        Row(SubjectType.GAME, "SPG", "D:GENRE", 1000),
        Row(SubjectType.GAME, "国产", "U", 1000),
        Row(SubjectType.MUSIC, "OP", "D:GENRE", 511),
        Row(SubjectType.MUSIC, "ED", "D:GENRE", 542),
        Row(SubjectType.MUSIC, "角色歌", "D:GENRE", 246),
        Row(SubjectType.MUSIC, "OST", "D:GENRE", 1000),
        Row(SubjectType.MUSIC, "OST+动画", "D:GENRE/D:SOURCE", 731),
        Row(SubjectType.MUSIC, "OST+电影", "D:GENRE/D:SOURCE", 127),
        Row(SubjectType.MUSIC, "OST+电视剧", "D:GENRE/D:SOURCE", 126),
        Row(SubjectType.MUSIC, "游戏", "D:SOURCE", 1000),
        Row(SubjectType.MUSIC, "OST+游戏", "D:GENRE/D:SOURCE", 1000),
        Row(SubjectType.MUSIC, "Arrange", "D:GENRE", 163),
        Row(SubjectType.MUSIC, "galgame", "U", 1000),
        Row(SubjectType.MUSIC, "Vocaloid", "D:GENRE", 1000),
        Row(SubjectType.MUSIC, "同人音乐", "D:GENRE", 1000),
        Row(SubjectType.MUSIC, "東方+同人音乐", "U/D:GENRE", 221),
        Row(SubjectType.MUSIC, "Drama", "D:GENRE", 714),
        Row(SubjectType.MUSIC, "Radio", "D:GENRE", 582),
        Row(SubjectType.MUSIC, "摇滚", "U", 440),
        Row(SubjectType.REAL, "电视剧+日本", "D:CATEGORY/D:REGION", 1000),
        Row(SubjectType.REAL, "电视剧+日本+悬疑", "D:CATEGORY/D:REGION/D:THEME", 177),
        Row(SubjectType.REAL, "电视剧+美国", "D:CATEGORY/D:REGION", 1000),
        Row(SubjectType.REAL, "情景喜剧", "U", 108),
        Row(SubjectType.REAL, "电视剧+犯罪", "D:CATEGORY/D:THEME", 1000),
        Row(SubjectType.REAL, "电影+欧美", "D:CATEGORY/D:REGION", 1000),
        Row(SubjectType.REAL, "电影+动作", "D:CATEGORY/D:THEME", 1000),
        Row(SubjectType.REAL, "电影+科幻", "D:CATEGORY/D:THEME", 767),
        Row(SubjectType.REAL, "超级英雄", "U", 221),
        Row(SubjectType.REAL, "电影+日本", "D:CATEGORY/D:REGION", 1000),
        Row(SubjectType.REAL, "特摄", "D:THEME", 1000),
        Row(SubjectType.REAL, "假面骑士", "U", 314),
        Row(SubjectType.REAL, "超级战队", "U", 251),
        Row(SubjectType.REAL, "奥特曼", "U", 159),
        Row(SubjectType.REAL, "电视剧+英国", "D:CATEGORY/D:REGION", 1000),
        Row(SubjectType.REAL, "华语", "D:REGION", 1000),
        Row(SubjectType.REAL, "电视剧+华语+古装", "D:CATEGORY/D:REGION/D:THEME", 294),
        Row(SubjectType.REAL, "电视剧+韩国", "D:CATEGORY/D:REGION", 819),
        Row(SubjectType.REAL, "电视剧+韩国+爱情", "D:CATEGORY/D:REGION/D:THEME", 325),
        Row(SubjectType.REAL, "纪录片", "D:THEME", 309),
        Row(SubjectType.REAL, "综艺", "D:CATEGORY", 502),
        )
    }
}
