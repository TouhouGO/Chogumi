package io.github.touhougo.chogumi.core.model

/**
 * 探索页公司方块数据模型。
 *
 * 公司的作品不走人物条目，走**用户标签**（2026-10-01 匿名实测：公司名在标签目录里，
 * 服务端按用户标签处理；23 家对比表、来源判定与同义写法清单 2026-10-01 实测过，后加的 14 家动画公司
 * 2026-10-23 实测）。游戏公司 2026-11-04 重新实测：按热度搜，每家的标签下
 * 都有 30 部以上、排在前面的是这家的作品；同一家有几种写法时取部数最多的那个（光荣特库摩取「KOEI」236 部，
 * 「光荣特库摩」只有 51 部；索尼取「SCE」145 部）。「柚子社」标签只有 7 部且混进了别家的作品，不收。
 *
 * @property name 显示名
 * @property tag 主标签名（每家只配一个；同义写法凑不出「或」，多配只会漏——多标签是「且」语义）
 * @property personId Bangumi 上这家公司的人物条目（标志图从它取）
 */
data class ExploreCompany(
    val name: String,
    val tag: String,
    val personId: Int,
)

/**
 * 公司方块 → 搜索条件的**唯一**构造点：类型 + 排名排序 + 单个用户标签，
 * 走 [SearchCriteria.isTagBrowse] 的单标签浏览路径，与题材方块同一条调度。
 */
fun ExploreCompany.toCriteria(type: SubjectType): SearchCriteria = SearchCriteria(
    subjectType = type,
    sort = SearchSort.RANK,
    filters = SearchFilters(tags = listOf(TagFilter.User(tag))),
)

private val ANIME_COMPANIES = listOf(
    ExploreCompany(name = "京都动画", tag = "京阿尼", personId = 2481),
    ExploreCompany(name = "MAPPA", tag = "MAPPA", personId = 7357),
    ExploreCompany(name = "ufotable", tag = "ufotable", personId = 3059),
    ExploreCompany(name = "CloverWorks", tag = "CloverWorks", personId = 32356),
    ExploreCompany(name = "WIT STUDIO", tag = "WITSTUDIO", personId = 8481),
    ExploreCompany(name = "P.A.WORKS", tag = "P.A.WORKS", personId = 5917),
    ExploreCompany(name = "SHAFT", tag = "SHAFT", personId = 2751),
    ExploreCompany(name = "BONES", tag = "BONES", personId = 44),
    ExploreCompany(name = "动画工房", tag = "动画工房", personId = 6305),
    ExploreCompany(name = "A-1 Pictures", tag = "A-1Pictures", personId = 3525),
    ExploreCompany(name = "MADHOUSE", tag = "MADHouse", personId = 603),
    ExploreCompany(name = "J.C.STAFF", tag = "J.C.STAFF", personId = 390),
    ExploreCompany(name = "SUNRISE", tag = "SUNRISE", personId = 189),
    ExploreCompany(name = "Production I.G", tag = "Production.I.G", personId = 1286),
    ExploreCompany(name = "东映动画", tag = "東映アニメーション", personId = 3045),
    ExploreCompany(name = "Studio DEEN", tag = "studiodeen", personId = 181),
    ExploreCompany(name = "Studio Pierrot", tag = "StudioPierrot", personId = 320),
    ExploreCompany(name = "SILVER LINK.", tag = "SILVERLINK.", personId = 6352),
    ExploreCompany(name = "Brain's Base", tag = "BrainsBase", personId = 3329),
    ExploreCompany(name = "WHITE FOX", tag = "WHITEFOX", personId = 7512),
    ExploreCompany(name = "david production", tag = "davidproduction", personId = 6331),
    ExploreCompany(name = "8bit", tag = "8bit", personId = 6885),
    ExploreCompany(name = "feel.", tag = "Feel.", personId = 1275),
    ExploreCompany(name = "Lerche", tag = "Lerche", personId = 8203),
    ExploreCompany(name = "TRIGGER", tag = "TRIGGER", personId = 8008),
    ExploreCompany(name = "吉卜力", tag = "吉卜力", personId = 2216),
    ExploreCompany(name = "CoMix Wave Films", tag = "CoMixWaveFilms", personId = 2153),
)

private val GAME_COMPANIES = listOf(
    ExploreCompany(name = "任天堂", tag = "任天堂", personId = 6734),
    ExploreCompany(name = "CAPCOM", tag = "Capcom", personId = 471),
    ExploreCompany(name = "SQUARE ENIX", tag = "SquareEnix", personId = 497),
    ExploreCompany(name = "万代南梦宫", tag = "万代南梦宫", personId = 3502),
    ExploreCompany(name = "SEGA", tag = "SEGA", personId = 1541),
    ExploreCompany(name = "KONAMI", tag = "KONAMI", personId = 747),
    ExploreCompany(name = "索尼互动娱乐", tag = "SCE", personId = 1018),
    ExploreCompany(name = "ATLUS", tag = "ATLUS", personId = 6913),
    ExploreCompany(name = "FromSoftware", tag = "FROMSOFTWARE", personId = 7595),
    ExploreCompany(name = "光荣特库摩", tag = "KOEI", personId = 1203),
    ExploreCompany(name = "Falcom", tag = "Falcom", personId = 6792),
    ExploreCompany(name = "Game Freak", tag = "GameFreak", personId = 9043),
    ExploreCompany(name = "SNK", tag = "SNK", personId = 705),
    ExploreCompany(name = "Spike Chunsoft", tag = "Chunsoft", personId = 1947),
    ExploreCompany(name = "日本一", tag = "日本一", personId = 2924),
    ExploreCompany(name = "Ubisoft", tag = "Ubisoft", personId = 8105),
    ExploreCompany(name = "EA", tag = "EA", personId = 13930),
    ExploreCompany(name = "Bethesda", tag = "Bethesda", personId = 10310),
    ExploreCompany(name = "Valve", tag = "Valve", personId = 16323),
    ExploreCompany(name = "Rockstar", tag = "Rockstar", personId = 10302),
    ExploreCompany(name = "网易游戏", tag = "网易", personId = 28561),
    ExploreCompany(name = "KEY", tag = "key", personId = 47),
    ExploreCompany(name = "TYPE-MOON", tag = "Type-Moon", personId = 1465),
    ExploreCompany(name = "Nitro+", tag = "Nitro+", personId = 1736),
    ExploreCompany(name = "ALICESOFT", tag = "ALICESOFT", personId = 2116),
)

/**
 * 探索页公司名单唯一入口。
 *
 * @param type 条目类型
 * @return 对应类型的公司列表；没有配置名单的类型返回空列表。
 */
fun exploreCompaniesFor(type: SubjectType): List<ExploreCompany> = when (type) {
    SubjectType.ANIME -> ANIME_COMPANIES
    SubjectType.GAME -> GAME_COMPANIES
    else -> emptyList()
}
