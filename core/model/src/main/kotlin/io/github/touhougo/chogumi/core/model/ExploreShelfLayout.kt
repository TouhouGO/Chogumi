package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 探索页货架的布局：每个分类一份 [ShelfEntry] 的列表，顺序即近期热门下面的显示顺序。
 *
 * 整页只有两种货架：
 * - **作品货架**（[WorkShelfEntry]）：一个词（或几个词的交集），一行作品，按 [WorkShelfStyle] 画；
 * - **方块货架**（[TileShelfEntry]）：几个词，一个词一块，按 [TileDrawing] 画，点一块进那个词。
 *
 * 没动过的分类用 [defaultShelfLayout]；存下来的布局与它同一个形状，[exploreShelvesFor] 把任一份布局变成要画的货架。
 *
 * @Serializable：布局按行存进本地表（core:data `explore_shelves`，一行一条货架的 kotlinx JSON），也是整体导出的格式。
 * 各子类的 [SerialName] 是存下来的类型标记，写死成稳定名字：改类名、挪包不会让存下来的布局读不出来；
 * 枚举按常量名存，改常量名的那一行读不出来（读不懂的行跳过，见 [exploreShelvesFor]）。
 */
@Serializable
sealed interface ShelfEntry {
    /** 货架在这个分类里的稳定身份：取数、列表 key、存储都按它。默认布局里的货架由下面的构造函数给，前缀分种类。 */
    val id: String

    /** 移除了的货架（「添加货架」里还能加回来）：不画。 */
    val hidden: Boolean
}

/**
 * 一条作品货架。
 *
 * @property source 作品从哪来、叫什么
 * @property style 画法；八种样式所有作品货架都能换
 */
@Serializable
@SerialName("works")
data class WorkShelfEntry(
    override val id: String,
    val source: WorkShelfSource,
    val style: WorkShelfStyle,
    override val hidden: Boolean = false,
) : ShelfEntry

/** 作品货架的来源。 */
@Serializable
sealed interface WorkShelfSource {
    /** 固定货架（今日放送、排行榜、「N 月番回顾」）：只能换样式。 */
    @Serializable
    @SerialName("fixed")
    data class Fixed(val shelf: FixedWorkShelf) : WorkShelfSource

    /**
     * 预设目录里的一条（[ShelfPreset]）：词跟着目录走；能改名（[name] 为空时用预设的名字）、改排序。
     * 改了词就不再是这条预设，存成 [Custom]。
     */
    @Serializable
    @SerialName("preset")
    data class Preset(val presetId: String, val sort: SearchSort, val name: String?) : WorkShelfSource

    /** 自己建的（含搜索页图钉）：整份条件（排序在条件里）与名字。 */
    @Serializable
    @SerialName("custom")
    data class Custom(val criteria: SearchCriteria, val name: String) : WorkShelfSource
}

/**
 * 一条方块货架。
 *
 * @property tileKeys 方块键（[TileLabel.key]）的列表；为 null 时跟随来源的默认方块（[defaultTileKeys]），
 *   动过（删了或加了方块）才存。年代货架不管存的顺序，始终按时间排
 * @property drawing 画法；来源用不了的画法（[tileDrawingsFor] 之外）画成色块
 */
@Serializable
@SerialName("tiles")
data class TileShelfEntry(
    override val id: String,
    val source: TileShelfSource,
    val tileKeys: List<String>?,
    val drawing: TileDrawing,
    override val hidden: Boolean = false,
) : ShelfEntry

/** 方块货架的来源。 */
@Serializable
sealed interface TileShelfSource {
    /** 预设的一组（按题材、按年代、按公司……）：候选是同一组的词。 */
    @Serializable
    @SerialName("fixed")
    data class Fixed(val set: TileSet) : TileShelfSource

    /** 自己建的：候选是这个分类的整个预设目录。 */
    @Serializable
    @SerialName("custom")
    data class Custom(val name: String) : TileShelfSource
}

/** 作品货架的八种样式。 */
enum class WorkShelfStyle { POSTER_ROW, FOCUS, CHART, BENTO, COVER_FLOW, DECK, SPINE, TIMELINE }

/** 年表上的一年：那一年的作品，保持货架原来的顺序。 */
data class TimelineYear(val year: Int, val works: List<SubjectBo>)

/**
 * 年表样式怎么排（唯一判定）：不另查数据，取这条货架本来那一页作品，按各自的放送或发售年份（[parseSubjectYearMonth]）分组，
 * 从早到晚一年一组；没有作品的年份不出现，没有日期的作品不上年表。一部有日期的都没有时是空表（画的一方退回海报行）。
 */
fun timelineYearsOf(subjects: List<SubjectBo>): List<TimelineYear> = subjects
    .mapNotNull { subject -> subject.date?.let(::parseSubjectYearMonth)?.let { it.year to subject } }
    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    .toSortedMap()
    .map { (year, works) -> TimelineYear(year, works) }

/**
 * 方块货架的五种画法：色块、合集（色块上叠三张小封面）、代表作、标志（只有公司与平台）、刻度尺（只有年代）。
 * 一条货架能用哪几种只由 [tileDrawingsFor] 决定。
 *
 * @property needs 这种画法每一块要取什么（唯一判定）：取数的一方只看它，不另判断画法
 */
enum class TileDrawing(val needs: TileNeed) {
    COLOR(TileNeed.NOTHING),
    STACK(TileNeed.WORKS),
    COVER(TileNeed.WORKS),
    LOGO(TileNeed.LOGO),
    RULER(TileNeed.WORKS),
}

/** 一块方块画出来要的数据。 */
enum class TileNeed {
    /** 本地就有：词与大字。 */
    NOTHING,

    /** 这个词的条件取到的前几部作品（合集叠三张封面，代表作取第一张，刻度尺停在哪一块下面就排那一块的）。 */
    WORKS,

    /** 方块的标志（[ExploreTile.logo]）：公司取人物条目的图，平台的图形随包、什么都不取。 */
    LOGO,
}

/**
 * 固定的作品货架：内容不由词决定，只能换样式。
 *
 * @property defaultStyle 默认布局里、从「添加货架」放上来时的样式
 */
enum class FixedWorkShelf(val defaultStyle: WorkShelfStyle) {
    /** 今日放送：放送表里今天的那一天（只有动画）。 */
    TODAY_ON_AIR(WorkShelfStyle.POSTER_ROW),

    /** 排行榜：按排名排的整个分类。 */
    RANKING(WorkShelfStyle.CHART),

    /** 「N 月番回顾」：上一季开播的动画按排名（只有动画）。 */
    CATCH_UP_SEASON(WorkShelfStyle.POSTER_ROW),
}

/** 这个分类能不能有这条固定货架（「添加货架」只列能有的）。 */
fun FixedWorkShelf.isAvailableFor(type: SubjectType): Boolean = when (this) {
    FixedWorkShelf.RANKING -> type.concreteOrNull() != null
    FixedWorkShelf.TODAY_ON_AIR, FixedWorkShelf.CATCH_UP_SEASON -> type == SubjectType.ANIME
}

/** 固定作品货架的布局行，按它的默认样式（默认布局与「添加货架」共用，id 一种货架一个）。 */
fun fixedWorkShelfEntry(shelf: FixedWorkShelf): WorkShelfEntry =
    WorkShelfEntry(id = "fixed:${shelf.name}", source = WorkShelfSource.Fixed(shelf), style = shelf.defaultStyle)

/** 预设作品货架的布局行，按预设自己的排序与样式（默认布局与「添加货架」共用，id 一条预设一个）。 */
fun presetShelfEntry(preset: ShelfPreset): WorkShelfEntry = WorkShelfEntry(
    id = "preset:${preset.id}",
    source = WorkShelfSource.Preset(presetId = preset.id, sort = preset.sort, name = null),
    style = preset.style,
)

/** 预设方块货架的布局行：跟随默认方块，用这一组的默认画法（默认布局与「添加货架」共用，id 一组一个）。 */
fun tileSetShelfEntry(set: TileSet): TileShelfEntry = TileShelfEntry(
    id = "tiles:${set.name}",
    source = TileShelfSource.Fixed(set),
    tileKeys = null,
    drawing = set.defaultDrawing,
)

/**
 * 没动过的分类的布局（唯一来源）。
 *
 * 默认显示哪些：取每个分类按热度排的前 400 部，统计它们的元标签和用户标签，选一组能盖住最多作品的大词
 * （2026-10-22，覆盖率动画 100%、书籍 84%、游戏 96%、音乐 95%、三次元 98%）；每个大类的细分留在「添加货架」。
 *
 * [SubjectType.UNKNOWN] 与「全部」没有探索页，返回空。
 */
fun defaultShelfLayout(type: SubjectType): List<ShelfEntry> {
    val preset = { id: String -> presetShelfEntry(checkNotNull(shelfPresetOf(type, id)) { "$type 的预设目录里没有「$id」" }) }
    val ranking = fixedWorkShelfEntry(FixedWorkShelf.RANKING)
    val decades = tileSetShelfEntry(TileSet.DECADE)
    return when (type.concreteOrNull()) {
        SubjectType.ANIME -> listOf(
            fixedWorkShelfEntry(FixedWorkShelf.TODAY_ON_AIR),
            ranking,
            preset("奇幻"),
            preset("恋爱"),
            tileSetShelfEntry(TileSet.THEME),
            preset("战斗"),
            preset("校园"),
            preset("日常"),
            decades,
            preset("搞笑"),
            preset("剧场版"),
            tileSetShelfEntry(TileSet.COMPANY),
            preset("科幻"),
            preset("悬疑"),
            preset("百合"),
            fixedWorkShelfEntry(FixedWorkShelf.CATCH_UP_SEASON),
        )

        SubjectType.BOOK -> listOf(
            ranking,
            preset("漫画+少年"),
            preset("漫画+少女"),
            tileSetShelfEntry(TileSet.THEME),
            preset("轻小说"),
            preset("漫画+青年"),
            preset("漫画+奇幻"),
            preset("漫画+连载中"),
            preset("小说+推理"),
            preset("小说+科幻"),
            preset("漫画+百合"),
            preset("漫画+搞笑"),
            decades,
        )

        SubjectType.GAME -> listOf(
            ranking,
            tileSetShelfEntry(TileSet.PLATFORM),
            preset("RPG"),
            preset("ACT"),
            tileSetShelfEntry(TileSet.GENRE),
            preset("AVG"),
            preset("Galgame"),
            preset("独立游戏"),
            tileSetShelfEntry(TileSet.COMPANY),
            preset("解谜"),
            preset("FPS"),
            preset("SLG"),
            preset("恐怖"),
            preset("MUG"),
            tileSetShelfEntry(TileSet.THEME),
            decades,
        )

        SubjectType.MUSIC -> listOf(
            ranking,
            tileSetShelfEntry(TileSet.FORMAT),
            preset("OP"),
            preset("ED"),
            preset("OST"),
            preset("游戏"),
            preset("galgame"),
            preset("Vocaloid"),
            preset("同人音乐"),
            decades,
        )

        SubjectType.REAL -> listOf(
            ranking,
            tileSetShelfEntry(TileSet.FORMAT),
            tileSetShelfEntry(TileSet.REGION),
            preset("电视剧+日本"),
            preset("电视剧+美国"),
            preset("电影+欧美"),
            preset("电影+日本"),
            preset("特摄"),
            preset("电视剧+英国"),
            preset("华语"),
            preset("电视剧+韩国"),
            tileSetShelfEntry(TileSet.THEME),
            decades,
        )

        null, SubjectType.UNKNOWN -> emptyList()
    }
}

/** 按词取的作品货架叫什么、取哪组条件（[WorkShelfSource.words]）。 */
data class ShelfWords(val name: String, val criteria: SearchCriteria)

/**
 * 按词取的作品货架（预设与自建）的名字与条件（唯一来源）：[exploreShelvesFor] 画货架、图钉判等都取它。
 * 固定货架不是词，返回 null；预设目录里已经没有的预设也是 null。
 */
fun WorkShelfSource.words(type: SubjectType): ShelfWords? = when (this) {
    is WorkShelfSource.Fixed -> null
    is WorkShelfSource.Preset -> shelfPresetOf(type, presetId)?.let { preset ->
        ShelfWords(name = name ?: preset.name, criteria = preset.criteria.copy(sort = sort))
    }
    is WorkShelfSource.Custom -> ShelfWords(name = name, criteria = criteria)
}

/**
 * 搜索页图钉对着的货架：[layout] 里没隐藏、按词取且条件与 [criteria] 判等（[presetKey]）的第一条作品货架。
 * 图钉亮不亮、亮着时名字栏写什么都只看它；没有是 null。
 */
fun pinnedShelfOf(type: SubjectType, layout: List<ShelfEntry>, criteria: SearchCriteria): PinnedShelf? {
    val key = criteria.presetKey()
    return layout.firstNotNullOfOrNull { entry ->
        if (entry !is WorkShelfEntry || entry.hidden) return@firstNotNullOfOrNull null
        entry.source.words(type)?.takeIf { it.criteria.presetKey() == key }?.let { PinnedShelf(id = entry.id, name = it.name) }
    }
}

/** 图钉对着的一条货架（[pinnedShelfOf]）：取消、改名按 [id]，名字栏显示 [name]。 */
data class PinnedShelf(val id: String, val name: String)

/**
 * 图钉（与新建作品货架）：把 [criteria] 放上 [type] 的探索页，落在近期热门下面第一条。
 *
 * 判等用 [presetKey]（与 [pinnedShelfOf] 同一个判定）：布局里已有一条显示着的，不动；已有一条移除了的，放回来挪到最前；
 * 都没有才新建一条叫 [name] 的自建作品货架（样式 [style]）。同一组条件因此在一份布局里只有一条。
 */
fun List<ShelfEntry>.pinned(
    type: SubjectType,
    criteria: SearchCriteria,
    name: String,
    style: WorkShelfStyle = WorkShelfStyle.POSTER_ROW,
): List<ShelfEntry> {
    val key = criteria.presetKey()
    val existing = firstOrNull { it is WorkShelfEntry && it.source.words(type)?.criteria?.presetKey() == key }
        ?: return withShown(WorkShelfEntry(id = newCustomShelfId(this), source = WorkShelfSource.Custom(criteria, name), style = style))
    return if (existing.hidden) withShown(existing) else this
}

/** 移除一条货架（= 隐藏，「添加货架」里还能加回来）。没有这条时原样返回。 */
fun List<ShelfEntry>.withHidden(id: String): List<ShelfEntry> = map { if (it.id == id) it.withHiddenFlag(true) else it }

/**
 * 给按词取的作品货架改名：预设记下自己的名字（不再跟目录），自建的换名字；固定货架与方块货架没有可改的名字，原样返回。
 */
fun List<ShelfEntry>.withShelfName(id: String, name: String): List<ShelfEntry> = map { entry ->
    if (entry.id != id || entry !is WorkShelfEntry) return@map entry
    when (val source = entry.source) {
        is WorkShelfSource.Fixed -> entry
        is WorkShelfSource.Preset -> entry.copy(source = source.copy(name = name))
        is WorkShelfSource.Custom -> entry.copy(source = source.copy(name = name))
    }
}

/** 自建货架的 id 前缀：后面是这份布局里没用过的序号。 */
private const val CUSTOM_SHELF_PREFIX = "custom:"

/** 新建一条自建货架用的 id：比 [layout] 里已有的自建序号都大（只在一份布局里唯一，各分类各算各的）。 */
internal fun newCustomShelfId(layout: List<ShelfEntry>): String {
    val used = layout.mapNotNull { entry ->
        entry.id.takeIf { it.startsWith(CUSTOM_SHELF_PREFIX) }?.removePrefix(CUSTOM_SHELF_PREFIX)?.toIntOrNull()
    }
    return CUSTOM_SHELF_PREFIX + ((used.maxOrNull() ?: 0) + 1)
}
