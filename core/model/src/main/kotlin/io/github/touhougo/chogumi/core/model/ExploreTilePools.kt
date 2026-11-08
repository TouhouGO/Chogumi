package io.github.touhougo.chogumi.core.model

/**
 * 方块货架的候选池与默认方块 —— 方块从哪来的**唯一来源**。
 *
 * 预设的一组（[TileSet]）只是默认内容：每条都能删方块、从同一组的候选（[tileCandidates]）里加方块；
 * 自己建的方块货架从这个分类的整个预设目录里挑。条件一律从空条件起、用 [pickWord] 加（年代与公司除外：
 * 年代是时间条件，公司由 [ExploreCompany.toCriteria] 统一构造）。
 *
 * ## 实测（2026-10-01，匿名；公司名单 2026-10-23 补测）
 *
 * | 与早先原型不同处 | 原因（实测） |
 * |---|---|
 * | 书籍题材方块不放「异世界」 | 「异世界」在书籍里落**维度**，全站只有 **1** 部 |
 * | 游戏、音乐年代方块不放 1970 年代 | 1970–1979 游戏 **1** 部、音乐 **2** 部 |
 * | 音乐形式方块用「精选集」（14 部），不用「角色歌」 | 「角色歌」只有 **5** 部 |
 * | 音乐形式方块用「IN」（20 部），不用「Drama」 | 「Drama」**0** 部（空货架） |
 *
 * 词落点全部经 [resolveTagInput] 核对：动画的「异世界」「治愈」、游戏的「魂系」、三次元的「假面骑士」「超级战队」「奥特曼」
 * 落成**用户标签**；音乐形式方块的「精选集」「IN」落成 GENRE 维度取值；其余落成维度取值。
 */
enum class TileSet(
    /**
     * 这一组的默认画法：每一组默认都有图。年代是刻度尺，公司与平台是标志；其余的词取这个词排在前面的作品画封面：
     * 题材、地区叠三张（合集），玩法、形式、特摄取第一部（代表作）。三次元的形式与地区挨着，一组代表作、一组合集，
     * 不连着两行一样。色块只在用户选它时画。
     */
    val defaultDrawing: TileDrawing,
) {
    THEME(TileDrawing.STACK),
    DECADE(TileDrawing.RULER),
    COMPANY(TileDrawing.LOGO),
    PLATFORM(TileDrawing.LOGO),
    GENRE(TileDrawing.COVER),
    FORMAT(TileDrawing.COVER),
    REGION(TileDrawing.STACK),
    TOKUSATSU(TileDrawing.COVER),
}

/** 一组方块的货架名。 */
fun TileSet.title(type: SubjectType): ExploreTitle = when (this) {
    TileSet.THEME -> ExploreTitle.ThemeBrowse
    TileSet.DECADE -> ExploreTitle.DecadeBrowse
    TileSet.COMPANY -> ExploreTitle.CompanyBrowse(type)
    TileSet.PLATFORM -> ExploreTitle.PlatformBrowse
    TileSet.GENRE -> ExploreTitle.GenreBrowse
    TileSet.FORMAT -> ExploreTitle.FormatBrowse
    TileSet.REGION -> ExploreTitle.RegionBrowse
    TileSet.TOKUSATSU -> ExploreTitle.Tokusatsu
}

/**
 * 一条方块货架能用的画法（唯一判定）：色块、合集、代表作都能用；标志只给公司（Bangumi 的公司条目有标志图）
 * 与平台（随包的平台图形），刻度尺只给年代（年代是连续的）。
 */
fun tileDrawingsFor(source: TileShelfSource): List<TileDrawing> = TileDrawing.entries.filter { drawing ->
    when (drawing) {
        TileDrawing.COLOR, TileDrawing.STACK, TileDrawing.COVER -> true
        TileDrawing.LOGO -> source == TileShelfSource.Fixed(TileSet.COMPANY) || source == TileShelfSource.Fixed(TileSet.PLATFORM)
        TileDrawing.RULER -> source == TileShelfSource.Fixed(TileSet.DECADE)
    }
}

/**
 * 一条方块货架能加的全部方块（候选池），顺序即「加方块」里的顺序。这个分类没有这一组时为空
 * （音乐没有题材词、只有动画与游戏有公司名单……），这样的货架不出现。
 *
 * - 题材：[themeWordsFor] 去掉排除词；
 * - 年代：这个分类有作品的年代，从新到旧；
 * - 公司：[exploreCompaniesFor]；
 * - 平台：游戏的 [GamePlatform]，一个平台一块，带它的图形；
 * - 玩法、形式、地区、特摄：下面的词表；
 * - 自己建的：这个分类的整个预设目录（[shelfPresetsFor]），一条预设一块。
 */
fun tileCandidates(type: SubjectType, source: TileShelfSource): List<ExploreTile> = when (source) {
    is TileShelfSource.Custom -> shelfPresetsFor(type).map { preset ->
        ExploreTile(label = TileLabel.Preset(preset.id, preset.name), big = TILE_ABBR[preset.name] ?: preset.name, criteria = preset.criteria)
    }
    is TileShelfSource.Fixed -> when (source.set) {
        TileSet.THEME -> themeWordsFor(type)
            .filterNot { it in THEME_TILE_EXCLUDE[type].orEmpty() }
            .map { wordTile(type, it) }
        TileSet.DECADE -> decadeTiles(type)
        TileSet.COMPANY -> exploreCompaniesFor(type).map { company ->
            ExploreTile(
                label = TileLabel.Word(company.name),
                big = companyAbbr(company.name),
                criteria = company.toCriteria(type),
                logo = TileLogo.Company(company.personId),
            )
        }
        TileSet.TOKUSATSU -> if (type == SubjectType.REAL) {
            // 组头是特摄这个词下的全部作品；后三个是特摄下的作品群。
            listOf(wordTile(type, "特摄", label = TileLabel.AllOf("特摄"))) +
                listOf("假面骑士", "超级战队", "奥特曼").map { wordTile(type, it) }
        } else {
            emptyList()
        }
        TileSet.PLATFORM -> if (type == SubjectType.GAME) {
            GamePlatform.entries.map { wordTile(type, it.word).copy(logo = TileLogo.Platform(it)) }
        } else {
            emptyList()
        }
        TileSet.GENRE, TileSet.FORMAT, TileSet.REGION ->
            WORD_TILE_POOLS[source.set to type].orEmpty().map { wordTile(type, it) }
    }
}

/**
 * 没动过的方块货架显示哪些方块（键）：题材与玩法取候选的前几个（[DEFAULT_TILE_COUNTS]，其余的留在「加方块」里），
 * 其余预设组是整个候选池，自己建的从空开始。
 */
fun defaultTileKeys(type: SubjectType, source: TileShelfSource): List<String> = when (source) {
    is TileShelfSource.Custom -> emptyList()
    is TileShelfSource.Fixed -> tileCandidates(type, source)
        .let { candidates -> DEFAULT_TILE_COUNTS[source.set]?.let(candidates::take) ?: candidates }
        .map { it.label.key }
}

/**
 * 一条方块货架要画的方块：[keys]（null 时用 [defaultTileKeys]）在候选池里查得到的那些，按存的顺序；
 * 候选池里已经没有的键跳过。年代始终按时间排（候选池的顺序），不管存的顺序。
 */
internal fun tilesOf(type: SubjectType, source: TileShelfSource, keys: List<String>?): List<ExploreTile> {
    val candidates = tileCandidates(type, source)
    val chosen = keys ?: defaultTileKeys(type, source)
    return if (source == TileShelfSource.Fixed(TileSet.DECADE)) {
        candidates.filter { it.label.key in chosen }
    } else {
        val byKey = candidates.associateBy { it.label.key }
        chosen.distinct().mapNotNull { byKey[it] }
    }
}

/** 只默认显示前几个方块的组：题材 10 个，玩法 12 个。 */
private val DEFAULT_TILE_COUNTS: Map<TileSet, Int> = mapOf(TileSet.THEME to 10, TileSet.GENRE to 12)

/**
 * 方块的缩写大字表：只收放不下整名的平台名。表里没有的词，大字就是它本身。
 */
private val TILE_ABBR: Map<String, String> = mapOf(
    "Nintendo Switch" to "NS",
    "Xbox Series X/S" to "XSX",
)

/**
 * 题材方块的排除表：这些词虽然是题材词，但不进题材方块。
 *
 * - 书籍「异世界」：它落**维度**，全站只有 1 部（实测）；
 * - 三次元「特摄」：单独成一组（[TileSet.TOKUSATSU]）。
 */
private val THEME_TILE_EXCLUDE: Map<SubjectType, Set<String>> = mapOf(
    SubjectType.BOOK to setOf("异世界"),
    SubjectType.REAL to setOf("特摄"),
)

/**
 * 玩法、形式、地区三组的词表（按分类）。玩法前 12 个默认显示，是条目最多的几大类（按 `meta_tags` 实测都在 500 部以上）；
 * 后面的是细分或较小的类，留在「加方块」里。每个玩法词都有同名预设，方块的中文副标题取预设名。
 */
private val WORD_TILE_POOLS: Map<Pair<TileSet, SubjectType>, List<String>> = mapOf(
    (TileSet.GENRE to SubjectType.GAME) to listOf(
        "RPG", "ACT", "AVG", "SLG", "FTG", "STG", "FPS", "PUZ", "SIM", "RAC", "SPG", "Roguelike",
        "ARPG", "SRPG", "TPS", "MUG", "VN", "休闲",
    ),
    (TileSet.FORMAT to SubjectType.MUSIC) to listOf("OST", "OP", "ED", "精选集", "Vocaloid", "同人音乐", "IN", "单曲"),
    (TileSet.FORMAT to SubjectType.REAL) to listOf("电视剧", "电影", "演出", "综艺"),
    (TileSet.REGION to SubjectType.REAL) to listOf("日本", "美国", "韩国", "华语", "英国", "中国香港"),
)

/**
 * 年代方块的起点年份，从新到旧。游戏与音乐的 1970 年代不足 10 部（实测 1 部、2 部），故不列。
 * 其余类型给满 2020–1970 六个年代。
 */
private val DECADE_START_YEARS: Map<SubjectType, List<Int>> = mapOf(
    SubjectType.GAME to listOf(2020, 2010, 2000, 1990, 1980),
    SubjectType.MUSIC to listOf(2020, 2010, 2000, 1990, 1980),
)

private val FULL_DECADE_START_YEARS = listOf(2020, 2010, 2000, 1990, 1980, 1970)

/**
 * 一个词一块：条件从空条件起，只用 [pickWord] 加，词落在哪里由 [resolveTagInput] 决定。按热度排：方块上的封面要的是
 * 一眼认得出的代表作，按排名排，前面多是老牌的冷门作品。
 * 副标题是同一个词的预设货架的名字（RPG 是「角色扮演」），与词一样或没有这条预设时没有。
 */
private fun wordTile(type: SubjectType, word: String, label: TileLabel = TileLabel.Word(word)): ExploreTile {
    val criteria = SearchCriteria(subjectType = type, sort = SearchSort.COLLECTS).pickWord(word, WordPick.ADD)
    val subtitle = shelfPresetOf(type, word)?.name?.takeIf { it != word }
    return ExploreTile(label = label, big = TILE_ABBR[word] ?: word, criteria = criteria, subtitle = subtitle)
}

/** 年代方块：每个年代一个年份范围，按排名排序，大字写成「20s」。只有具体分类有年代。 */
private fun decadeTiles(type: SubjectType): List<ExploreTile> {
    if (type.concreteOrNull() == null) return emptyList()
    return (DECADE_START_YEARS[type] ?: FULL_DECADE_START_YEARS).map { start ->
        ExploreTile(
            label = TileLabel.Decade(start),
            big = decadeAbbr(start),
            criteria = SearchCriteria(
                subjectType = type,
                sort = SearchSort.RANK,
                filters = SearchFilters(airtime = SearchAirtime(start, start + 9)),
            ),
        )
    }
}

/**
 * 年代的大字：取年份的后两位再补个 s。
 * **必须补足两位**：2000 的 `2000 % 100` 是 0，直接拼会得到「0s」，应是「00s」。
 */
private fun decadeAbbr(startYear: Int): String = "%02ds".format(startYear % 100)

/** 公司方块的大字：名字里的字母与数字，前两个。 */
private fun companyAbbr(name: String): String = name.filter { it.isLetterOrDigit() }.take(2)
