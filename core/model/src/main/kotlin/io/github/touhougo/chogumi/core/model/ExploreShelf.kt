package io.github.touhougo.chogumi.core.model

import java.time.LocalDate
import java.time.YearMonth

/**
 * 探索页的固定入口与货架 —— **全仓唯一的纯函数来源**。
 *
 * 探索页 = 一行固定入口 + 近期热门大图 + 一列货架，每个类型各有一份。货架怎么摆是一份布局
 * （[ShelfEntry] 的列表，见 ExploreShelfLayout.kt），[exploreShelvesFor] 把布局变成要画的货架；
 * 界面（feature/explore）只消费这里的结果，不得自己拼条件、自己判断某个词落在哪里。
 *
 * 三条不可绕过的规矩：
 * 1. 条件**一律从一个空条件起、用 [pickWord] 逐词加**，词落在维度还是标签只由 [resolveTagInput] 决定——
 *    预设目录（ExploreShelfCatalog.kt）与方块候选池（ExploreTilePools.kt）都不出现手写的维度或标签。
 * 2. 货架名用 [ExploreTitle]（core/model 用不了字符串资源），feature/explore 里有唯一的
 *    一个映射函数把它转成文字；方块的显示文字本身就是数据，不走 ExploreTitle。
 * 3. 题材词只来自 [themeWordsFor]（题材维度的取值 + 人工词），不另存一份词表。
 */
sealed interface ExploreTitle {
    // —— 固定入口
    /** 每日放送（只有动画）。 */
    data object DailyCalendar : ExploreTitle

    /** 打开筛选面板（跳到空条件的搜索页并自动展开面板）。 */
    data object Filters : ExploreTitle

    /** 排行榜（入口与货架同名）。 */
    data object Ranking : ExploreTitle

    // —— 带月份 / 带类型的名字
    /** 「N 月新番」：今天往后两周所在季度的首月。 */
    data class NewSeason(val month: Int) : ExploreTitle

    /** 「N 月番回顾」：上一季的首月。 */
    data class CatchUpSeason(val month: Int) : ExploreTitle

    /** 「本月新刊 / 本月新碟 / 本月发售 / 本月开播」，按类型取词。 */
    data class ThisMonth(val type: SubjectType) : ExploreTitle

    // —— 货架名
    /** 近期热门（大图轮播）。 */
    data object Trending : ExploreTitle

    /** 今日放送（只有动画）。 */
    data object TodayOnAir : ExploreTitle

    /** 按题材浏览。 */
    data object ThemeBrowse : ExploreTitle

    /** 按年代浏览。 */
    data object DecadeBrowse : ExploreTitle

    /** 按公司浏览：动画是「按动画公司浏览」，游戏是「按游戏公司浏览」。 */
    data class CompanyBrowse(val type: SubjectType) : ExploreTitle

    /** 按平台浏览。 */
    data object PlatformBrowse : ExploreTitle

    /** 按玩法浏览。 */
    data object GenreBrowse : ExploreTitle

    /** 按形式浏览。 */
    data object FormatBrowse : ExploreTitle

    /** 按地区浏览。 */
    data object RegionBrowse : ExploreTitle

    /** 按特摄系列浏览（三次元）。 */
    data object Tokusatsu : ExploreTitle

    /**
     * 名字本身就是文字：预设目录里的名字（[ShelfPreset.name]）或用户起的名字，两种语言一样
     * （与方块上的词同理：词来自 Bangumi 的标签，本来就只有一种写法）。
     */
    data class Named(val name: String) : ExploreTitle
}

/**
 * 探索页的固定入口。
 *
 * @property title 入口名（[ExploreEntry.Search] 另有条件）
 */
sealed interface ExploreEntry {
    val title: ExploreTitle

    /** 每日放送：打开放送表，不是搜索（只有动画）。 */
    data object Calendar : ExploreEntry {
        override val title: ExploreTitle get() = ExploreTitle.DailyCalendar
    }

    /**
     * 打开筛选面板：跳到搜索页并自动展开面板，条件只含当前分类（面板据此预选该分类）。
     * 条件由 [exploreEntriesFor] 一处的 `openFiltersEntry` 构造，页面导航只交语义回调。
     */
    data class OpenFilters(val criteria: SearchCriteria) : ExploreEntry {
        override val title: ExploreTitle get() = ExploreTitle.Filters
    }

    /** 带条件跳搜索页。 */
    data class Search(override val title: ExploreTitle, val criteria: SearchCriteria) : ExploreEntry
}

/**
 * 方块下方的文字是什么。措辞随语言变的部分（年代、「全部」）由界面按资源取，这里只给结构。
 *
 * @property key 方块的稳定身份：同一行里的列表 key，也是方块货架存下来的方块键（[TileShelfEntry.tileKeys]）
 */
sealed interface TileLabel {
    val key: String

    /** 词本身就是文字：维度取值、题材词、公司名（服务端的数据，两种语言一样）。 */
    data class Word(val word: String) : TileLabel {
        override val key: String get() = "word:$word"
    }

    /** 一个年代，[startYear] 是它的第一年（1990 年代是 1990）。 */
    data class Decade(val startYear: Int) : TileLabel {
        override val key: String get() = "decade:$startYear"
    }

    /** 一组的组头：这个词下的全部作品。 */
    data class AllOf(val word: String) : TileLabel {
        override val key: String get() = "all:$word"
    }

    /** 一条预设（[ShelfPreset]）：自己建的方块货架从预设目录里挑词，文字是预设的名字。 */
    data class Preset(val id: String, val name: String) : TileLabel {
        override val key: String get() = "preset:$id"
    }
}

/**
 * 一个方块：大字 + 下方文字，点了带 [criteria] 跳搜索页。
 *
 * @property label 方块下方的文字
 * @property big 方块上的大字：短词就是它本身，长词用缩写，年代写成「20s」
 * @property logo 标志画法画什么：公司方块是 Bangumi 上的人物条目，平台方块是随包的平台标志；其余方块为 null
 * @property subtitle 代表作画法压在词下面的一行（这个词的中文名，如 RPG 的「角色扮演」）；没有时为 null
 */
data class ExploreTile(
    val label: TileLabel,
    val big: String,
    val criteria: SearchCriteria,
    val logo: TileLogo? = null,
    val subtitle: String? = null,
)

/** 一块方块的标志从哪来。 */
sealed interface TileLogo {
    /** 公司：Bangumi 上这家公司的人物条目，图要取。 */
    data class Company(val personId: Int) : TileLogo

    /** 游戏平台：随包的平台标志（[GamePlatform]），不取。 */
    data class Platform(val platform: GamePlatform) : TileLogo
}

/**
 * 「按平台浏览」的平台，顺序即方块的顺序。[word] 是搜索的平台词（落成平台维度的取值）。
 * 标志是各平台的官方标志（core:res `platform_*`，来源见 docs/third-party-notices.md）。
 *
 * Switch 两代只有一块：平台维度的多个取值在服务端是「同时满足」，合不成「二选一」，这一块搜的是 Switch。
 */
enum class GamePlatform(val word: String) {
    SWITCH("Nintendo Switch"),
    PS5("PS5"),
    PC("PC"),
    XBOX_SERIES("Xbox Series X/S"),
    ANDROID("Android"),
}

/**
 * 探索页上要画的一条货架（[exploreShelvesFor] 的结果）。
 *
 * @property id 货架的稳定身份：取数、列表 key 都按它（布局里的 [ShelfEntry.id]，近期热门固定是 [TRENDING_SHELF_ID]）
 * @property title 货架名
 */
sealed interface ExploreShelf {
    val id: String
    val title: ExploreTitle

    /** 近期热门大图轮播：固定在入口行下面，不在布局里，不能挪、不能移除。 */
    data class Trending(val criteria: SearchCriteria) : ExploreShelf {
        override val id: String get() = TRENDING_SHELF_ID
        override val title: ExploreTitle get() = ExploreTitle.Trending
    }

    /** 作品货架：一行作品，按 [style] 画。 */
    data class Works(
        override val id: String,
        override val title: ExploreTitle,
        val content: WorkShelfContent,
        val style: WorkShelfStyle,
    ) : ExploreShelf

    /** 方块货架：一个词一块，按 [drawing] 画。 */
    data class Tiles(
        override val id: String,
        override val title: ExploreTitle,
        val tiles: List<ExploreTile>,
        val drawing: TileDrawing,
    ) : ExploreShelf
}

/** 作品货架的作品从哪来。 */
sealed interface WorkShelfContent {
    /** 一次搜索的第一页。 */
    data class Search(val criteria: SearchCriteria) : WorkShelfContent

    /** 放送表里今天的那一天（今日放送）。 */
    data object TodayOnAir : WorkShelfContent
}

/** 近期热门的货架 id（布局里的 id 都带种类前缀，不会撞上它）。 */
const val TRENDING_SHELF_ID: String = "trending"

/**
 * 探索页的固定入口（唯一来源）。
 *
 * 动画四个：每日放送、「N 月新番」（[seasonFor] 所在季度的首月，热度排序）、排行榜、筛选。
 * 其余类型三个：「本月新刊 / 新碟 / 发售 / 开播」（本月，日期排序）、排行榜、筛选。
 *
 * [SubjectType.UNKNOWN] 与「全部」没有探索页，返回空。
 */
fun exploreEntriesFor(type: SubjectType, today: LocalDate): List<ExploreEntry> = when (type.concreteOrNull()) {
    SubjectType.ANIME -> listOf(
        ExploreEntry.Calendar,
        ExploreEntry.Search(
            ExploreTitle.NewSeason(seasonFor(today).monthValue),
            monthCriteria(type, seasonFor(today), SearchSort.TRENDS),
        ),
        rankingEntry(type),
        openFiltersEntry(type),
    )

    SubjectType.BOOK, SubjectType.MUSIC, SubjectType.GAME, SubjectType.REAL -> listOf(
        ExploreEntry.Search(
            ExploreTitle.ThisMonth(type),
            monthCriteria(type, YearMonth.of(today.year, today.monthValue), SearchSort.DATE),
        ),
        rankingEntry(type),
        openFiltersEntry(type),
    )

    null, SubjectType.UNKNOWN -> emptyList()
}

/**
 * 布局 → 要画的货架（唯一来源）：近期热门打头，后面是 [layout] 里没隐藏的货架，顺序即展示顺序。
 *
 * [layout] 是这个分类存下来的布局；没动过的分类传 [defaultShelfLayout]。
 * 布局里这个分类用不了的货架（游戏布局里的今日放送）、预设目录里已经没有的预设、一个方块都没有的方块货架不出现：
 * 存下来的布局可能比当前版本的目录旧，读不懂的行跳过，不让一行拖垮整页。
 *
 * [SubjectType.UNKNOWN] 与「全部」没有探索页，返回空。
 */
fun exploreShelvesFor(type: SubjectType, today: LocalDate, layout: List<ShelfEntry>): List<ExploreShelf> {
    if (type.concreteOrNull() == null) return emptyList()
    val trending = ExploreShelf.Trending(SearchCriteria(subjectType = type, sort = SearchSort.TRENDS))
    return listOf(trending) + layout.filterNot { it.hidden }.mapNotNull { entry ->
        when (entry) {
            is WorkShelfEntry -> worksShelf(type, today, entry)
            is TileShelfEntry -> tilesShelf(type, today, entry)
        }
    }
}

private fun worksShelf(type: SubjectType, today: LocalDate, entry: WorkShelfEntry): ExploreShelf.Works? {
    val (title, content) = when (val source = entry.source) {
        is WorkShelfSource.Fixed -> fixedWorkShelf(type, today, source.shelf) ?: return null
        is WorkShelfSource.Preset, is WorkShelfSource.Custom -> {
            val words = source.words(type) ?: return null
            ExploreTitle.Named(words.name) to WorkShelfContent.Search(words.criteria)
        }
    }
    return ExploreShelf.Works(id = entry.id, title = title, content = content, style = entry.style)
}

/** 固定作品货架在 [type] 这一页、[today] 这一天叫什么、取什么；这个分类没有这条时为 null。 */
private fun fixedWorkShelf(type: SubjectType, today: LocalDate, shelf: FixedWorkShelf): Pair<ExploreTitle, WorkShelfContent>? {
    if (!shelf.isAvailableFor(type)) return null
    return when (shelf) {
        FixedWorkShelf.TODAY_ON_AIR -> ExploreTitle.TodayOnAir to WorkShelfContent.TodayOnAir
        FixedWorkShelf.RANKING ->
            ExploreTitle.Ranking to WorkShelfContent.Search(SearchCriteria(subjectType = type, sort = SearchSort.RANK))
        FixedWorkShelf.CATCH_UP_SEASON -> ExploreTitle.CatchUpSeason(previousSeasonFor(today).monthValue) to
            WorkShelfContent.Search(monthCriteria(type, previousSeasonFor(today), SearchSort.RANK))
    }
}

private fun tilesShelf(type: SubjectType, today: LocalDate, entry: TileShelfEntry): ExploreShelf.Tiles? {
    val tiles = tilesOf(type, entry.source, entry.tileKeys).takeIf { it.isNotEmpty() } ?: return null
    val title = shelfTitleOf(type, today, entry) ?: return null
    val drawing = entry.drawing.takeIf { it in tileDrawingsFor(entry.source) } ?: TileDrawing.COLOR
    return ExploreShelf.Tiles(id = entry.id, title = title, tiles = tiles, drawing = drawing)
}

/**
 * 一条布局行的货架名（唯一来源）：画货架、整理列表、「添加货架」都取它。
 * 这个分类用不了的固定货架、预设目录里已经没有的预设是 null（[exploreShelvesFor] 不画这样的行）。
 */
fun shelfTitleOf(type: SubjectType, today: LocalDate, entry: ShelfEntry): ExploreTitle? = when (entry) {
    is WorkShelfEntry -> when (val source = entry.source) {
        is WorkShelfSource.Fixed -> fixedWorkShelf(type, today, source.shelf)?.first
        is WorkShelfSource.Preset, is WorkShelfSource.Custom -> source.words(type)?.let { ExploreTitle.Named(it.name) }
    }
    is TileShelfEntry -> when (val source = entry.source) {
        is TileShelfSource.Fixed -> source.set.title(type)
        is TileShelfSource.Custom -> ExploreTitle.Named(source.name)
    }
}

/**
 * 今天往后两周所在**季度**的首月（v1 已定的设计决定，原型 `SEASON`）。
 * 先加 14 天再取季度，故 9 月 30 日已算 10 月新番、12 月 20 日算次年 1 月新番。
 */
internal fun seasonFor(today: LocalDate): YearMonth {
    val shifted = today.plusDays(SEASON_LEAD_DAYS)
    return YearMonth.of(shifted.year, (shifted.monthValue - 1) / 3 * 3 + 1)
}

/** 上一个季度的首月：1 月时回到上一年 10 月（原型 `PREV_SEASON`）。 */
internal fun previousSeasonFor(today: LocalDate): YearMonth {
    val season = seasonFor(today)
    return if (season.monthValue == 1) YearMonth.of(season.year - 1, 10) else season.minusMonths(3)
}

/** 「N 月新番」看的是今天往后两周，常量单独留着以免成为魔法数字。 */
private const val SEASON_LEAD_DAYS = 14L

private fun rankingEntry(type: SubjectType): ExploreEntry = ExploreEntry.Search(
    ExploreTitle.Ranking,
    SearchCriteria(subjectType = type, sort = SearchSort.RANK),
)

/** 「全部筛选」入口的条件：只带类型（不带排序、不带筛选），全仓唯一构造点。 */
private fun openFiltersEntry(type: SubjectType): ExploreEntry.OpenFilters =
    ExploreEntry.OpenFilters(SearchCriteria(subjectType = type))

/** 某一整个月（[month] 所在年月）的条件；[sort] 由调用方给（新番用热度、本月用日期）。 */
private fun monthCriteria(type: SubjectType, month: YearMonth, sort: SearchSort): SearchCriteria = SearchCriteria(
    subjectType = type,
    sort = sort,
    filters = SearchFilters(airtime = SearchAirtime(month.year, month.year, month.monthValue)),
)
