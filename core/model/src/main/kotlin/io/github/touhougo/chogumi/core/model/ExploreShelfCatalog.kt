package io.github.touhougo.chogumi.core.model

/**
 * 作品货架的预设目录 —— 「添加货架」里能挑的现成货架的**唯一来源**，五个分类 129 条。
 *
 * 每条是一组词（[ShelfPreset.words]）：条件从空条件起、用 [pickWord] 逐词加，词落在维度还是用户标签只由
 * [resolveTagInput] 决定，本文件不写维度或标签。排序是这组条件 [availableSorts] 之内的一种
 * （带用户标签的只有排名与收藏等），样式是默认画法，名字是给人看的叫法（[ExploreTitle.Named]）。
 * 按大类分组（[ShelfPreset.group]），「添加货架」按组列。
 *
 * 实测（2026-10-23，匿名，p1 搜索、不含 NSFW）：每条都 ≥ 17 部（最少的是游戏「魂系」）；
 * 落点与部数逐条记在 ExploreShelfCatalogTest。
 */
data class ShelfPreset(
    val type: SubjectType,
    /** 「添加货架」里的大类名（目录数据，两种语言一样）。 */
    val group: String,
    val words: List<String>,
    val name: String,
    val sort: SearchSort,
    val style: WorkShelfStyle,
) {
    /** 在这个分类的目录里唯一：词用「+」连起来。布局按它引用预设。 */
    val id: String get() = words.joinToString(PRESET_WORD_SEPARATOR)

    /** 这组词的条件（按预设自己的排序）。 */
    val criteria: SearchCriteria
        get() = words.fold(SearchCriteria(subjectType = type, sort = sort)) { criteria, word ->
            criteria.pickWord(word, WordPick.ADD)
        }
}

/** 某个分类的整个预设目录，按组、组内按目录顺序。没有探索页的分类为空。 */
fun shelfPresetsFor(type: SubjectType): List<ShelfPreset> = when (type.concreteOrNull()) {
    SubjectType.ANIME -> ANIME_PRESETS
    SubjectType.BOOK -> BOOK_PRESETS
    SubjectType.GAME -> GAME_PRESETS
    SubjectType.MUSIC -> MUSIC_PRESETS
    SubjectType.REAL -> REAL_PRESETS
    null, SubjectType.UNKNOWN -> emptyList()
}

/** 按 id 查预设；目录里已经没有的返回 null（存下来的布局可能比目录旧）。 */
fun shelfPresetOf(type: SubjectType, id: String): ShelfPreset? = shelfPresetsFor(type).firstOrNull { it.id == id }

private const val PRESET_WORD_SEPARATOR = "+"

/** 写目录用的小构造器：[group] 里逐条 [preset]，词用「+」连成一串写。 */
private class PresetCatalogBuilder(private val type: SubjectType) {
    val presets = mutableListOf<ShelfPreset>()
    private var group = ""

    fun group(name: String, block: PresetCatalogBuilder.() -> Unit) {
        group = name
        block()
    }

    fun preset(words: String, name: String = words, sort: SearchSort, style: WorkShelfStyle = WorkShelfStyle.POSTER_ROW) {
        presets += ShelfPreset(type, group, words.split(PRESET_WORD_SEPARATOR), name, sort, style)
    }
}

private fun presets(type: SubjectType, block: PresetCatalogBuilder.() -> Unit): List<ShelfPreset> =
    PresetCatalogBuilder(type).apply(block).presets.toList()

private val ANIME_PRESETS: List<ShelfPreset> = presets(SubjectType.ANIME) {
    group("奇幻") {
        preset("奇幻", sort = SearchSort.COLLECTS, style = WorkShelfStyle.FOCUS)
        preset("异世界", sort = SearchSort.COLLECTS)
        preset("奇幻+战斗", name = "奇幻战斗", sort = SearchSort.COLLECTS)
        preset("冒险+奇幻", name = "冒险奇幻", sort = SearchSort.RANK)
        preset("异世界+转生", name = "转生异世界", sort = SearchSort.COLLECTS)
        preset("魔法少女", sort = SearchSort.RANK)
    }
    group("恋爱") {
        preset("恋爱", sort = SearchSort.COLLECTS)
        preset("恋爱+搞笑", name = "恋爱喜剧", sort = SearchSort.COLLECTS)
        preset("后宫", sort = SearchSort.COLLECTS)
        preset("校园+恋爱", name = "校园恋爱", sort = SearchSort.RANK)
        preset("女性向", sort = SearchSort.RANK)
    }
    group("战斗") {
        preset("战斗", sort = SearchSort.COLLECTS, style = WorkShelfStyle.BENTO)
        preset("热血", sort = SearchSort.COLLECTS)
        preset("战斗+热血", name = "热血战斗", sort = SearchSort.COLLECTS)
        preset("机战", sort = SearchSort.RANK)
    }
    group("校园") {
        preset("校园", sort = SearchSort.COLLECTS)
        preset("青春", sort = SearchSort.COLLECTS)
    }
    group("日常") {
        preset("日常", sort = SearchSort.COLLECTS)
        preset("治愈", sort = SearchSort.COLLECTS)
        preset("日常+治愈", name = "日常治愈", sort = SearchSort.COLLECTS)
        preset("美食", sort = SearchSort.COLLECTS)
        preset("催泪", sort = SearchSort.RANK)
    }
    group("搞笑") {
        preset("搞笑", sort = SearchSort.COLLECTS)
    }
    group("剧场版") {
        preset("剧场版", sort = SearchSort.RANK, style = WorkShelfStyle.COVER_FLOW)
        preset("剧场版+原创", name = "原创剧场版", sort = SearchSort.RANK)
    }
    group("科幻") {
        preset("科幻", sort = SearchSort.RANK)
        preset("机战+科幻", name = "机战科幻", sort = SearchSort.RANK)
    }
    group("悬疑") {
        preset("悬疑", sort = SearchSort.RANK)
        preset("推理", sort = SearchSort.RANK)
        preset("致郁", sort = SearchSort.RANK)
        preset("惊悚", sort = SearchSort.RANK)
    }
    group("百合") {
        preset("百合", sort = SearchSort.COLLECTS)
    }
    group("运动音乐") {
        preset("运动", sort = SearchSort.COLLECTS)
        preset("音乐", sort = SearchSort.COLLECTS)
        preset("偶像", sort = SearchSort.COLLECTS)
        preset("乐队", sort = SearchSort.COLLECTS)
    }
    group("国创") {
        preset("中国", name = "国创", sort = SearchSort.TRENDS, style = WorkShelfStyle.CHART)
        preset("中国+玄幻", name = "国创玄幻", sort = SearchSort.TRENDS)
        preset("中国+武侠", name = "国创武侠", sort = SearchSort.RANK)
    }
}

private val BOOK_PRESETS: List<ShelfPreset> = presets(SubjectType.BOOK) {
    group("少年漫画") {
        preset("漫画+少年", name = "少年漫画", sort = SearchSort.COLLECTS)
        preset("漫画+热血", name = "热血漫画", sort = SearchSort.COLLECTS)
        preset("漫画+战斗", name = "战斗漫画", sort = SearchSort.COLLECTS)
    }
    group("少女漫画") {
        preset("漫画+少女", name = "少女漫画", sort = SearchSort.COLLECTS)
        preset("漫画+恋爱", name = "恋爱漫画", sort = SearchSort.COLLECTS)
    }
    group("轻小说") {
        preset("轻小说", sort = SearchSort.COLLECTS, style = WorkShelfStyle.SPINE)
        preset("轻小说+转生", name = "转生轻小说", sort = SearchSort.COLLECTS)
        preset("轻小说+奇幻", name = "奇幻轻小说", sort = SearchSort.RANK)
        preset("轻小说+恋爱", name = "恋爱轻小说", sort = SearchSort.COLLECTS)
    }
    group("青年漫画") {
        preset("漫画+青年", name = "青年漫画", sort = SearchSort.COLLECTS)
        preset("漫画+猎奇", name = "猎奇漫画", sort = SearchSort.RANK)
    }
    group("奇幻漫画") {
        preset("漫画+奇幻", name = "奇幻漫画", sort = SearchSort.COLLECTS, style = WorkShelfStyle.BENTO)
        preset("漫画+科幻", name = "科幻漫画", sort = SearchSort.RANK)
    }
    group("连载") {
        preset("漫画+连载中", name = "连载中", sort = SearchSort.TRENDS)
        preset("漫画+已完结", name = "已完结", sort = SearchSort.RANK, style = WorkShelfStyle.CHART)
    }
    group("推理小说") {
        preset("小说+推理", name = "推理小说", sort = SearchSort.RANK)
        preset("小说+悬疑", name = "悬疑小说", sort = SearchSort.RANK)
    }
    group("科幻小说") {
        preset("小说+科幻", name = "科幻小说", sort = SearchSort.RANK)
    }
    group("百合漫画") {
        preset("漫画+百合", name = "百合漫画", sort = SearchSort.COLLECTS)
        preset("漫画+GL", name = "GL 漫画", sort = SearchSort.COLLECTS)
    }
    group("搞笑漫画") {
        preset("漫画+搞笑", name = "搞笑漫画", sort = SearchSort.COLLECTS)
        preset("漫画+治愈", name = "治愈漫画", sort = SearchSort.COLLECTS)
        preset("漫画+日常", name = "日常漫画", sort = SearchSort.COLLECTS)
    }
    group("国漫") {
        preset("漫画+中国", name = "国漫", sort = SearchSort.TRENDS)
    }
    group("画集") {
        preset("画集", sort = SearchSort.COLLECTS)
    }
}

private val GAME_PRESETS: List<ShelfPreset> = presets(SubjectType.GAME) {
    group("角色扮演") {
        preset("RPG", name = "角色扮演", sort = SearchSort.RANK, style = WorkShelfStyle.FOCUS)
        preset("JRPG", sort = SearchSort.RANK)
        preset("Nintendo Switch+RPG", name = "Switch 角色扮演", sort = SearchSort.RANK)
        preset("SRPG", name = "战棋角色扮演", sort = SearchSort.RANK)
    }
    group("动作") {
        preset("ACT", name = "动作", sort = SearchSort.COLLECTS)
        preset("ARPG", name = "动作角色扮演", sort = SearchSort.COLLECTS)
        preset("魂系", sort = SearchSort.COLLECTS)
        preset("PS5+ACT", name = "PS5 动作", sort = SearchSort.COLLECTS)
    }
    group("文字冒险") {
        preset("AVG", name = "文字冒险", sort = SearchSort.RANK)
        preset("AVG+悬疑", name = "悬疑冒险", sort = SearchSort.RANK)
        preset("推理", sort = SearchSort.RANK)
    }
    group("Galgame") {
        preset("Galgame", sort = SearchSort.RANK, style = WorkShelfStyle.DECK)
        preset("纯爱", sort = SearchSort.COLLECTS)
        preset("百合", sort = SearchSort.COLLECTS)
        preset("VN", name = "视觉小说", sort = SearchSort.RANK)
    }
    group("独立游戏") {
        preset("独立游戏", sort = SearchSort.COLLECTS)
        preset("Roguelike", sort = SearchSort.RANK)
        preset("开放世界", sort = SearchSort.COLLECTS)
    }
    group("解谜") {
        preset("解谜", sort = SearchSort.RANK)
        preset("PUZ", name = "益智", sort = SearchSort.COLLECTS)
    }
    group("格斗") {
        preset("FTG", name = "格斗", sort = SearchSort.COLLECTS)
    }
    group("射击") {
        preset("FPS", name = "射击", sort = SearchSort.COLLECTS)
        preset("STG", name = "弹幕射击", sort = SearchSort.RANK)
        preset("TPS", name = "第三人称射击", sort = SearchSort.COLLECTS)
    }
    group("策略") {
        preset("SLG", name = "策略", sort = SearchSort.RANK)
        preset("战棋", sort = SearchSort.RANK)
    }
    group("恐怖") {
        preset("恐怖", sort = SearchSort.RANK)
    }
    group("音游") {
        preset("MUG", name = "音游", sort = SearchSort.COLLECTS)
    }
    group("模拟") {
        preset("SIM", name = "模拟", sort = SearchSort.COLLECTS)
        preset("休闲", sort = SearchSort.COLLECTS)
    }
    group("竞速与体育") {
        preset("RAC", name = "竞速", sort = SearchSort.COLLECTS)
        preset("SPG", name = "体育", sort = SearchSort.COLLECTS)
    }
    group("国产") {
        preset("国产", sort = SearchSort.COLLECTS)
    }
}

private val MUSIC_PRESETS: List<ShelfPreset> = presets(SubjectType.MUSIC) {
    group("主题曲") {
        preset("OP", name = "片头曲", sort = SearchSort.COLLECTS)
        preset("ED", name = "片尾曲", sort = SearchSort.COLLECTS)
        preset("角色歌", sort = SearchSort.COLLECTS)
    }
    group("原声") {
        preset("OST", name = "原声", sort = SearchSort.COLLECTS)
        preset("OST+动画", name = "动画原声", sort = SearchSort.COLLECTS)
        preset("OST+电影", name = "电影原声", sort = SearchSort.RANK)
        preset("OST+电视剧", name = "剧集原声", sort = SearchSort.RANK)
    }
    group("游戏音乐") {
        preset("游戏", name = "游戏音乐", sort = SearchSort.COLLECTS)
        preset("OST+游戏", name = "游戏原声", sort = SearchSort.COLLECTS)
        preset("Arrange", name = "改编", sort = SearchSort.RANK)
    }
    group("Galgame 音乐") {
        preset("galgame", name = "Galgame 音乐", sort = SearchSort.COLLECTS)
    }
    group("Vocaloid") {
        preset("Vocaloid", sort = SearchSort.TRENDS, style = WorkShelfStyle.COVER_FLOW)
    }
    group("同人音乐") {
        preset("同人音乐", sort = SearchSort.COLLECTS)
        preset("東方+同人音乐", name = "东方同人", sort = SearchSort.COLLECTS)
    }
    group("广播剧") {
        preset("Drama", name = "广播剧", sort = SearchSort.COLLECTS)
        preset("Radio", name = "广播", sort = SearchSort.DATE)
    }
    group("风格") {
        preset("摇滚", sort = SearchSort.COLLECTS)
    }
}

private val REAL_PRESETS: List<ShelfPreset> = presets(SubjectType.REAL) {
    group("日剧") {
        preset("电视剧+日本", name = "日剧", sort = SearchSort.COLLECTS)
        preset("电视剧+日本+悬疑", name = "日本悬疑剧", sort = SearchSort.COLLECTS)
    }
    group("美剧") {
        preset("电视剧+美国", name = "美剧", sort = SearchSort.COLLECTS)
        preset("情景喜剧", sort = SearchSort.COLLECTS)
        preset("电视剧+犯罪", name = "犯罪剧", sort = SearchSort.RANK)
    }
    group("欧美电影") {
        preset("电影+欧美", name = "欧美电影", sort = SearchSort.COLLECTS, style = WorkShelfStyle.FOCUS)
        preset("电影+动作", name = "动作片", sort = SearchSort.COLLECTS)
        preset("电影+科幻", name = "科幻片", sort = SearchSort.RANK)
        preset("超级英雄", sort = SearchSort.COLLECTS)
    }
    group("日本电影") {
        preset("电影+日本", name = "日本电影", sort = SearchSort.RANK)
    }
    group("特摄") {
        preset("特摄", sort = SearchSort.COLLECTS, style = WorkShelfStyle.TIMELINE)
        preset("假面骑士", sort = SearchSort.COLLECTS)
        preset("超级战队", sort = SearchSort.COLLECTS)
        preset("奥特曼", sort = SearchSort.COLLECTS)
    }
    group("英剧") {
        preset("电视剧+英国", name = "英剧", sort = SearchSort.RANK)
    }
    group("华语") {
        preset("华语", sort = SearchSort.COLLECTS)
        preset("电视剧+华语+古装", name = "古装剧", sort = SearchSort.RANK)
    }
    group("韩剧") {
        preset("电视剧+韩国", name = "韩剧", sort = SearchSort.COLLECTS)
        preset("电视剧+韩国+爱情", name = "韩国爱情剧", sort = SearchSort.COLLECTS)
    }
    group("纪录综艺") {
        preset("纪录片", sort = SearchSort.RANK)
        preset("综艺", sort = SearchSort.TRENDS)
    }
}
