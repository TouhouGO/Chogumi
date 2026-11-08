package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/**
 * 分类浏览维度。名称与顺序对应 Bangumi 网页「分类浏览」页面（bangumi.tv/<类型>/browser）的维度标题：
 * 网页上动画/游戏/音乐叫「类型」、书籍/三次元叫「题材」，按原文区分为 GENRE / THEME。
 * 这是维度本身的唯一枚举；各类型包含哪些维度、每个维度有哪些取值，只由 [searchDimensionsFor] 决定。
 *
 * @Serializable：随 [SearchFilters.dimensions] 的 Map 键进路由与本地组合表。
 */
@Serializable
enum class SearchDimension {
    CATEGORY,
    SERIES,
    PLATFORM,
    SOURCE,
    GENRE,
    THEME,
    REGION,
    AUDIENCE,
    CONTENT_RATING,
    PUBLICATION_STATUS,
}

/** 维度的选法。 */
enum class DimensionSelectionMode {
    /** 单选：点别的值就替换。 */
    SINGLE,

    /** 多选：点一下加、再点去掉；同一维度内的多个取值在服务端是「同时满足」。 */
    MULTI,
}

/**
 * 维度取值。[label] 是网页原文（用于显示），[metaTag] 是实测命中的 meta_tag（用于请求）。
 * 两者多数相同；少数网页文字不是公共标签、但其 URL 段或短名是（如「Nintendo Switch」→ `NS`、「中国香港」→ `香港`），
 * 具体映射由 core:model 的维度夹具守卫。
 *
 * @Serializable：随 [SearchFilters.dimensions] 进路由与本地组合表。
 */
@Serializable
data class SearchDimensionOption(val label: String, val metaTag: String)

/** 一个维度及其有序取值。 */
data class SearchDimensionGroup(val dimension: SearchDimension, val options: List<SearchDimensionOption>)

/**
 * 维度词典：每个条目类型一份，维度顺序与取值顺序均按网页原顺序。
 *
 * 只收录实测命中的取值；
 * 不命中的取值（如书籍「写真」、三次元「日剧」、各类「其他」）不收录，不做置灰占位。
 * SearchDimensionFixtureTest 逐项对照稳定夹具，新增取值必须先实测。
 */
object SearchDimensionDictionary {
    val ANIME: List<SearchDimensionGroup> = listOf(
        group(
            SearchDimension.CATEGORY,
            o("TV"), o("WEB"), o("OVA"), o("剧场版"), o("动态漫画"),
        ),
        group(
            SearchDimension.SOURCE,
            o("原创"), o("漫画改"), o("游戏改"), o("小说改"), o("动画改"), o("影视改"),
        ),
        group(
            SearchDimension.GENRE,
            o("科幻"), o("喜剧"), o("同人"), o("百合"), o("校园"), o("惊悚"), o("后宫"), o("机战"), o("悬疑"), o("恋爱"), o("奇幻"),
            o("推理"), o("运动"), o("耽美"), o("音乐"), o("战斗"), o("冒险"), o("萌系"), o("穿越"), o("玄幻"), o("乙女"), o("恐怖"),
            o("历史"), o("日常"), o("剧情"), o("武侠"), o("短片集"), o("美食"), o("职场"),
        ),
        group(
            SearchDimension.REGION,
            o("日本"), o("欧美"), o("中国"), o("美国"), o("韩国"), o("法国"), o("英国"), o("中国香港", "香港"), o("俄罗斯"), o("苏联"),
            o("捷克"), o("中国台湾", "台湾"), o("马来西亚"),
        ),
        group(
            SearchDimension.AUDIENCE,
            o("BL"), o("GL"), o("子供向"), o("女性向"), o("少女向"), o("少年向"), o("青年向"), o("无cp"),
        ),
    )

    val BOOK: List<SearchDimensionGroup> = listOf(
        group(
            SearchDimension.CATEGORY,
            o("漫画"), o("小说"), o("画集"), o("绘本"), o("公式书"),
        ),
        group(
            SearchDimension.SERIES,
            o("系列"), o("单行本"),
        ),
        group(
            SearchDimension.SOURCE,
            o("游戏改"), o("原创"), o("小说改"), o("动画改"), o("漫画改"), o("影视改"),
        ),
        group(
            SearchDimension.THEME,
            o("推理"), o("后宫"), o("科幻"), o("百合"), o("恐怖"), o("恋爱"), o("音乐"), o("爱情"), o("校园"), o("穿越"), o("战斗"),
            o("运动"), o("游戏"), o("武侠"), o("现实"), o("奇幻"), o("惊悚"), o("搞笑"), o("日常"), o("悬疑"), o("剧情"), o("冒险"),
            o("异世界"), o("历史"), o("军事"), o("乙女"), o("美食"), o("职场"), o("玄幻"), o("机战"), o("都市"), o("仙侠"), o("无限"),
        ),
        group(
            SearchDimension.AUDIENCE,
            o("少年"), o("少女"), o("青年"), o("BL"), o("一般向"), o("名著"), o("儿童"), o("女性"), o("TL"), o("无cp"),
        ),
        group(
            SearchDimension.REGION,
            o("日本"), o("中国"), o("韩国"), o("中国台湾", "台湾"), o("美国"), o("法国"), o("泰国"), o("中国香港", "香港"), o("马来西亚"),
        ),
        group(
            SearchDimension.PUBLICATION_STATUS,
            o("已完结"), o("连载中"), o("停载"),
        ),
    )

    val GAME: List<SearchDimensionGroup> = listOf(
        group(
            SearchDimension.CATEGORY,
            o("游戏"), o("扩展包"), o("软件"), o("桌游"),
        ),
        group(
            SearchDimension.PLATFORM,
            o("PC"), o("Web"), o("Windows"), o("Mac"), o("Linux"), o("PS5"), o("Xbox Series X/S", "XSX"),
            o("Nintendo Switch", "NS"), o("Nintendo Switch 2", "NS2"), o("Android"), o("VR"), o("PSVR2"),
            o("街机"), o("Xbox One", "XboxOne"), o("Xbox"), o("Xbox 360", "Xbox360"), o("Wii"), o("Wii U", "WiiU"),
            o("NDS"), o("FC"), o("GBA"), o("3DS"), o("GBC"), o("GB"), o("N64"), o("NGC"), o("SFC"), o("PS4"),
            o("PSVR"), o("PS3"), o("PSP"), o("PS2"), o("PS"), o("Dreamcast", "DC"), o("MD"),
            o("Sega Saturn", "SS"), o("DOS"), o("PCE"), o("Apple II", "AppleII"), o("Amiga"), o("PC98"),
            o("X68000"), o("PC88"), o("Symbian"),
        ),
        group(
            SearchDimension.GENRE,
            o("AAVG"), o("ACT"), o("ADV"), o("ARPG"), o("AVG"), o("CCG"), o("CRPG"), o("DBG"), o("DRPG"), o("EDU"),
            o("FPS"), o("FTG"), o("Fly"), o("Horror"), o("JRPG"), o("MMORPG"), o("MOBA"), o("MUG"), o("PUZ"),
            o("Platform"), o("RAC"), o("RPG"), o("RTS"), o("RTT"), o("Rhythm"), o("Roguelike"), o("SIM"), o("SLG"),
            o("SPG"), o("SRPG"), o("STG"), o("Sandbox"), o("Strategy"), o("Survival"), o("TAB"), o("TBS"),
            o("TBT"), o("TCG"), o("TPS"), o("VN"), o("休闲"),
        ),
        group(
            SearchDimension.AUDIENCE,
            o("同人"), o("BL"), o("乙女"), o("GL"),
        ),
        group(
            SearchDimension.CONTENT_RATING,
            o("全年龄"), o("R18"),
        ),
    )

    val MUSIC: List<SearchDimensionGroup> = listOf(
        group(
            SearchDimension.GENRE,
            o("OST"), o("同人音乐"), o("ED"), o("OP"), o("角色歌"), o("Vocaloid"), o("Drama"), o("Remix"), o("IN"),
            o("印象曲"), o("VOCAL"), o("Radio"), o("Arrange"), o("单曲"), o("精选集"), o("TM"), o("朗读剧"), o("艺人专辑"),
        ),
        group(
            SearchDimension.SOURCE,
            o("动画"), o("游戏"), o("原创"), o("电影"), o("小说"), o("电视剧"), o("漫画"), o("有声作品"),
        ),
        group(
            SearchDimension.AUDIENCE,
            o("BL"), o("乙女"), o("TL"),
        ),
    )

    val REAL: List<SearchDimensionGroup> = listOf(
        group(
            SearchDimension.CATEGORY,
            o("电视剧"), o("电影"), o("演出"), o("综艺"),
        ),
        group(
            SearchDimension.THEME,
            o("犯罪"), o("悬疑"), o("推理"), o("喜剧"), o("爱情"), o("特摄"), o("科幻"), o("音乐"), o("校园"), o("美食"), o("奇幻"),
            o("动作"), o("家庭"), o("战争"), o("玄幻"), o("西部"), o("歌舞"), o("历史"), o("传记"), o("剧情"), o("纪录片"), o("恐怖"),
            o("惊悚"), o("职场"), o("武侠"), o("古装"), o("布袋戏"), o("灾难"), o("冒险"), o("少儿"), o("运动"), o("同性"), o("选集剧"),
        ),
        group(
            SearchDimension.REGION,
            o("日本"), o("欧美"), o("美国"), o("中国"), o("华语"), o("英国"), o("韩国"), o("中国香港", "香港"), o("中国台湾", "台湾"),
            o("加拿大"), o("法国"), o("意大利"), o("泰国"), o("俄罗斯"), o("新西兰"),
        ),
    )
}

private fun o(label: String, metaTag: String = label) = SearchDimensionOption(label, metaTag)

private fun group(dimension: SearchDimension, vararg options: SearchDimensionOption) =
    SearchDimensionGroup(dimension, options.toList())

/**
 * 唯一判定点：条目类型 → 可用维度及取值。
 * null（全部）/UNKNOWN 返回空：各类型词典互不通用（标签按 type 隔离），不存在跨类型的维度。
 */
fun searchDimensionsFor(type: SubjectType?): List<SearchDimensionGroup> = when (type) {
    SubjectType.ANIME -> SearchDimensionDictionary.ANIME
    SubjectType.BOOK -> SearchDimensionDictionary.BOOK
    SubjectType.GAME -> SearchDimensionDictionary.GAME
    SubjectType.MUSIC -> SearchDimensionDictionary.MUSIC
    SubjectType.REAL -> SearchDimensionDictionary.REAL
    null, SubjectType.UNKNOWN -> emptyList()
}

/**
 * 唯一判定点：某类型的某维度单选还是多选。依据 2026-09-30 实测
 * 的共现统计（每个类型收藏最多的 120 部里，有几部同时带同一维度的两个值）：
 *
 * | 维度 | 选法 | 依据 |
 * |---|---|---|
 * | CATEGORY、SERIES | 单选 | 服务端单值列 |
 * | PUBLICATION_STATUS | 单选 | 实测互斥 0/120 |
 * | SOURCE | 音乐多选，其余单选 | 音乐 2/120 可叠加，其余 0/120 |
 * | GENRE（动画、游戏、音乐）、THEME（书籍、三次元） | 多选 | 94、11、4、25、63 /120 |
 * | PLATFORM | 多选 | 94/120 |
 * | REGION | 动画、三次元多选；书籍单选 | 1、51 /120；书籍 0/120 未定，按单选 |
 * | AUDIENCE、CONTENT_RATING | 单选 | 未定，用户已同意按单选 |
 */
fun dimensionSelectionMode(type: SubjectType?, dimension: SearchDimension): DimensionSelectionMode = when (dimension) {
    SearchDimension.CATEGORY,
    SearchDimension.SERIES,
    SearchDimension.PUBLICATION_STATUS,
    SearchDimension.AUDIENCE,
    SearchDimension.CONTENT_RATING,
    -> DimensionSelectionMode.SINGLE

    SearchDimension.SOURCE -> if (type == SubjectType.MUSIC) DimensionSelectionMode.MULTI else DimensionSelectionMode.SINGLE
    SearchDimension.PLATFORM -> DimensionSelectionMode.MULTI
    SearchDimension.GENRE,
    SearchDimension.THEME,
    -> DimensionSelectionMode.MULTI

    SearchDimension.REGION -> if (type == SubjectType.BOOK) DimensionSelectionMode.SINGLE else DimensionSelectionMode.MULTI
}

/**
 * 唯一翻译点：维度选择 → meta_tags。按该类型词典的维度顺序、组内按词典取值顺序输出，
 * 与 Map 插入顺序、列表插入顺序都无关。
 * 不属于该类型词典的选择不输出——合法性由 [normalizeSearchCriteria] 先行拒绝，这里不做第二次校验。
 */
fun dimensionMetaTags(
    type: SubjectType?,
    selections: Map<SearchDimension, List<SearchDimensionOption>>,
): List<String> = searchDimensionsFor(type).flatMap { group ->
    val chosen = selections[group.dimension].orEmpty().toSet()
    group.options.filter { it in chosen }.map { it.metaTag }
}

/**
 * 按请求用的 meta_tag 反查维度取值（同类型内 metaTag 唯一，见 SearchDimensionTest）。
 * 条目上的公共标签就是 meta_tag 原值，所以用 metaTag 而不是网页文字匹配（如 `香港` → 地区「中国香港」）。
 */
fun searchDimensionOptionFor(type: SubjectType?, metaTag: String): Pair<SearchDimension, SearchDimensionOption>? =
    searchDimensionsFor(type).firstNotNullOfOrNull { group ->
        group.options.firstOrNull { it.metaTag == metaTag }?.let { group.dimension to it }
    }

/**
 * 维度选择是否全部落在该类型词典内，且单选维度最多一个值（多值说明调用方绕过了唯一更新点）。
 * [normalizeSearchCriteria] 的唯一依据。
 */
fun dimensionSelectionsValid(
    type: SubjectType?,
    selections: Map<SearchDimension, List<SearchDimensionOption>>,
): Boolean {
    val groups = searchDimensionsFor(type).associateBy { it.dimension }
    return selections.all { (dimension, options) ->
        val dictionary = groups[dimension]?.options
        dictionary != null &&
            options.distinct().size == options.size &&
            options.all { it in dictionary } &&
            (dimensionSelectionMode(type, dimension) == DimensionSelectionMode.MULTI || options.size <= 1)
    }
}

/**
 * 唯一更新点：点选某个维度的某个取值（按当前条目类型决定该维度单选还是多选）。
 * 单选维度点别的值直接替换，再点已选值则取消；多选维度点一下加、再点去掉。
 * 其余维度与标签原样保留。类型（含「全部」）不合法时由 [dimensionSelectionsValid] 在规范化时拒绝。
 */
fun SearchCriteria.toggleDimension(dimension: SearchDimension, option: SearchDimensionOption): SearchCriteria {
    val current = filters.dimensions[dimension].orEmpty()
    val next = when {
        option in current -> current - option
        dimensionSelectionMode(subjectType, dimension) == DimensionSelectionMode.MULTI -> current + option
        else -> listOf(option)
    }
    return withDimensionOptions(dimension, next)
}

/** 唯一写回点：把某维度的取值列表写回 dimensions，空列表即删除该维度。 */
private fun SearchCriteria.withDimensionOptions(
    dimension: SearchDimension,
    options: List<SearchDimensionOption>,
): SearchCriteria = copy(
    filters = filters.copy(
        dimensions = if (options.isEmpty()) filters.dimensions - dimension else filters.dimensions + (dimension to options),
    ),
)

/**
 * 唯一判定点：切换条目类型。类型真的变化时，已选的分类随之带过去（[withCategoriesPlaced]）：
 * 在新类型词典里有的重新落成维度，没有的保留为「分类」标签——切到「全部」时就是跨类型按分类搜索，不会把条件清空。
 * 重选同一类型不动。用户标签与时间不受影响。
 */
fun SearchCriteria.withSubjectType(type: SubjectType?): SearchCriteria =
    if (type == subjectType) this else copy(subjectType = type).withCategoriesPlaced()

/**
 * 唯一落位点：把已选分类（维度选择 + 「分类」标签）按当前类型重新落位。
 * 每个分类取其 meta_tag，按该类型词典反查（[searchDimensionOptionFor]）：查到且该维度还能再收（多选维度一直能收、
 * 单选维度未被占用）→ 落成维度；否则保留为「分类」标签。
 * 顺序：原维度（按当前类型的词典顺序，词典外的接在后面）在前、原「分类」标签在后，同值只留一个。
 * 用户标签与时间原样保留。
 */
fun SearchCriteria.withCategoriesPlaced(): SearchCriteria {
    // 词典里有顺序的按词典排（维度顺序 + 组内取值顺序），没落进词典的按原顺序接在后面。
    val orderedMetaTags = dimensionMetaTags(subjectType, filters.dimensions).let { ordered ->
        val placed = ordered.toMutableSet()
        // 同值去重后把词典未覆盖的补在末尾（同一 metaTag 只留一个位置）。
        ordered + filters.dimensions.values.flatten().map { it.metaTag }.filter { placed.add(it) }
    }
    val categories = (orderedMetaTags + filters.tags.filterIsInstance<TagFilter.Public>().map { it.value }).distinct()
    val dimensions = mutableMapOf<SearchDimension, List<SearchDimensionOption>>()
    val leftover = mutableListOf<TagFilter>()
    for (metaTag in categories) {
        val placed = searchDimensionOptionFor(subjectType, metaTag)
        val dimension = placed?.first
        val canPlace = dimension != null &&
            (dimensionSelectionMode(subjectType, dimension) == DimensionSelectionMode.MULTI ||
                dimensions[dimension].isNullOrEmpty())
        if (placed != null && dimension != null && canPlace) {
            dimensions += dimension to (dimensions[dimension].orEmpty() + placed.second)
        } else {
            leftover += TagFilter.Public(metaTag)
        }
    }
    return copy(
        filters = filters.copy(
            dimensions = dimensions,
            tags = leftover + filters.tags.filterIsInstance<TagFilter.User>(),
        ),
    )
}
