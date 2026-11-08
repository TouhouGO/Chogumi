package io.github.touhougo.chogumi.core.model

/**
 * 题材区：某类型的「题材」由**题材维度的取值 + 人工挑的题材词**合成一组。
 *
 * 依据 2026-09-30 实测：
 * 人工词表取自 bgm.tv/tag 热门标签（= 标签目录前 50）经 K 的归类筛选，去掉成人向词与
 * 与分类取值同义的词（如「搞笑」≈「喜剧」、「乙女向」≈受众「乙女」），也不收形式词（轻小说、泡面番）。
 * 词表入库、改了要发版；守卫测试 SearchThemeTest 逐条核对不重名与顺序表完整。
 */

/**
 * 唯一判定点：哪个维度是「题材」。动画是 GENRE，书籍与三次元是 THEME；
 * 游戏（GENRE 在界面上叫「玩法」）与音乐没有题材维度——它们的题材区只有人工挑的词。
 */
fun themeDimensionFor(type: SubjectType?): SearchDimension? = when (type) {
    SubjectType.ANIME -> SearchDimension.GENRE
    SubjectType.BOOK -> SearchDimension.THEME
    SubjectType.REAL -> SearchDimension.THEME
    SubjectType.GAME, SubjectType.MUSIC, null, SubjectType.UNKNOWN -> null
}

/** 人工挑的题材词（题材维度之外额外收进来的常用标签）。 */
private val THEME_EXTRA: Map<SubjectType, List<String>> = mapOf(
    SubjectType.ANIME to listOf(
        "异世界", "治愈", "热血", "青春", "致郁", "催泪", "群像", "乐队", "偶像", "魔法少女", "转生", "女主控",
    ),
    SubjectType.BOOK to listOf(
        "治愈", "热血", "致郁", "群像", "转生", "恶役千金",
    ),
    SubjectType.GAME to listOf(
        "纯爱", "百合", "解谜", "推理", "恐怖", "开放世界", "魂系", "科幻", "奇幻", "悬疑",
    ),
    SubjectType.MUSIC to emptyList(),
    SubjectType.REAL to emptyList(),
)

/**
 * 题材区的显示顺序（人工排）。没列到的词按词典顺序接在后面。
 * 表里出现而词表里没有的词不会显示，守卫测试 SearchThemeTest 禁止这种残留。
 */
internal fun themeOrderFor(type: SubjectType?): List<String> = THEME_ORDER[type].orEmpty()

private val THEME_ORDER: Map<SubjectType, List<String>> = mapOf(
    SubjectType.ANIME to listOf(
        "恋爱", "校园", "奇幻", "战斗", "日常", "异世界", "科幻", "喜剧", "百合", "治愈", "热血", "青春",
        "悬疑", "后宫", "运动", "音乐", "萌系", "冒险", "致郁", "催泪", "群像", "乐队", "偶像", "魔法少女",
        "转生", "机战", "推理", "惊悚", "恐怖", "耽美", "穿越", "玄幻", "乙女", "历史", "剧情", "武侠",
        "美食", "职场", "女主控",
    ),
    SubjectType.BOOK to listOf(
        "恋爱", "校园", "奇幻", "异世界", "战斗", "日常", "百合", "推理", "悬疑", "科幻", "治愈", "热血",
        "转生", "恶役千金", "搞笑", "后宫",
    ),
)

/**
 * 唯一判定点：条目类型 → 题材区的全部词（题材维度取值 + 人工词），已排好显示顺序。
 * 游戏与音乐的题材维度为 null，只返回人工词；「全部」与 UNKNOWN 没有任何题材区。
 */
fun themeWordsFor(type: SubjectType?): List<String> {
    val dictionary = themeDimensionFor(type)?.let { dimension ->
        searchDimensionsFor(type).singleOrNull { it.dimension == dimension }?.options?.map { it.label }.orEmpty()
    }.orEmpty()
    val words = dictionary + THEME_EXTRA[type].orEmpty()
    // 没有排序表的类型 order 为空，结果就是 words 原样
    val order = themeOrderFor(type)
    return order.filter { it in words } + words.filter { it !in order }
}

/**
 * 这个标签是不是当前类型题材区里的词（按用户标签存、且在题材区词表里）。
 * 决策「其他已选」与条件叫法「题材 / 标签」的唯一依据。
 */
fun TagFilter.isThemeWord(type: SubjectType?): Boolean = this is TagFilter.User && value in themeWordsFor(type)

/**
 * 搜索框输入的词落到哪里：等于该类型某个维度取值（先比显示名、再比 metaTag，
 * 例「中国香港」与「香港」都落到地区）就落成那个维度；其余一律当用户标签——**目录外的词也收**。
 */
sealed interface TagInputResolution {
    data class Dimension(val dimension: SearchDimension, val option: SearchDimensionOption) : TagInputResolution
    data class UserTag(val value: String) : TagInputResolution
}

/**
 * 唯一解析点：用户输入/点选的词 → 维度取值或用户标签。题材区的点选、搜索框回车、
 * 搜索建议的点选都只走它，界面不得自己判断某个词是不是分类。
 * 比较顺序：先比显示名，再比 metaTag（例「中国香港」与「香港」都落到地区）；空白输入返回 null。
 */
fun resolveTagInput(type: SubjectType?, text: String): TagInputResolution? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val groups = searchDimensionsFor(type)
    val byLabel = groups.firstNotNullOfOrNull { group ->
        group.options.firstOrNull { it.label == trimmed }?.let { group.dimension to it }
    }
    val byMetaTag = byLabel ?: groups.firstNotNullOfOrNull { group ->
        group.options.firstOrNull { it.metaTag == trimmed }?.let { group.dimension to it }
    }
    return if (byMetaTag == null) {
        TagInputResolution.UserTag(trimmed)
    } else {
        TagInputResolution.Dimension(byMetaTag.first, byMetaTag.second)
    }
}

/**
 * 选一个词的两种写法：
 * - [TOGGLE]：题材区 chip。已选的再点一下即取消，维度取值和自由词一样；
 * - [ADD]：搜索框回车、点建议。只加不减，已选的再选一次保持不变；单选维度照旧替换（由 [toggleDimension] 决定）。
 */
enum class WordPick { TOGGLE, ADD }

/**
 * 唯一判定点：这个词在当前条件里是否已选（「这个用户标签是否已存在」只在这里判断）。
 * 题材区 chip 的选中态、建议行的「已选」、[pickWord] 的切换与去重都用它。
 */
fun SearchCriteria.isWordSelected(text: String): Boolean =
    resolveTagInput(subjectType, text)?.let { isSelected(it) } ?: false

/**
 * 唯一更新点：把一个词按 [pick] 落进条件。词落到哪里只由 [resolveTagInput] 决定；
 * 维度取值走 [toggleDimension]，自由词按用户标签加入或去掉。空白输入原样返回。
 */
fun SearchCriteria.pickWord(text: String, pick: WordPick): SearchCriteria {
    val resolved = resolveTagInput(subjectType, text) ?: return this
    val selected = isSelected(resolved)
    if (selected && pick == WordPick.ADD) return this
    return when (resolved) {
        is TagInputResolution.Dimension -> toggleDimension(resolved.dimension, resolved.option)
        is TagInputResolution.UserTag -> {
            val tag = TagFilter.User(resolved.value)
            copy(filters = filters.copy(tags = if (selected) filters.tags - tag else filters.tags + tag))
        }
    }
}

private fun SearchCriteria.isSelected(resolved: TagInputResolution): Boolean = when (resolved) {
    is TagInputResolution.Dimension -> resolved.option in filters.dimensions[resolved.dimension].orEmpty()
    is TagInputResolution.UserTag -> TagFilter.User(resolved.value) in filters.tags
}
