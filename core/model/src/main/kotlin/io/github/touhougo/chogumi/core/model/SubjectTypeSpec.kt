package io.github.touhougo.chogumi.core.model

/**
 * 详情页第 2 个选项卡的内容（随类型改名；游戏没有）。
 *
 * - [EPISODES]：章节（动画、三次元）；
 * - [VOLUMES]：单行本（书籍，内容来自单行本关联 relation 1003）；
 * - [TRACKS]：曲目（音乐，沿用章节组件按碟分组）。
 */
enum class SubjectSecondTab { EPISODES, VOLUMES, TRACKS }

/**
 * 进度怎么记：悬浮工具栏「+1」加的是什么、收藏编辑面板的进度区长什么样、进度写到哪里，都由它决定。
 * 为 null 的类型（游戏、音乐、不认识的类型）没有进度。「有没有进度 / 是不是逐集」的判定都从这里派生
 * （[EpisodeTracking.hasProgress]、[EpisodeTracking.tracksEpisodes]、[EpisodeTracking.tracksCounts]）。
 */
enum class ProgressUnit {
    /** 动画、三次元：按集（EP）。进度是逐集状态，经章节接口写。 */
    EPISODE,

    /**
     * 书籍：进度是收藏记录里的话数与卷数，经收藏 PATCH 写（服务端只允许书籍这样改）。
     * 工具栏只放卷（Vol）；编辑面板另带一行「话」。
     */
    VOLUME,
    ;

    /**
     * 按这个单位记进度时书籍该用的 [BookUnit]（唯一映射）：[VOLUME] 固定记卷；
     * [EPISODE] 不指定（非书籍类型用不到，书籍不会是它）。首页 / 进度页不经规格表，仍按 [bookUnit] 自动判定。
     */
    val bookUnitOverride: BookUnit?
        get() = if (this == VOLUME) BookUnit.VOL else null
}

/**
 * 这个类型用什么动词：看（动画、三次元）/ 读（书籍）/ 听（音乐）/ 玩（游戏）。
 * 收藏状态的说法（想读、在听…）与统计卡里「开始看的人看完了吗」的动词都从它取（bgm.tv 网页同口径）。
 */
enum class SubjectVerb { WATCH, READ, LISTEN, PLAY }

/** 元信息行里的计数单位。 */
enum class SubjectCountUnit { EPISODE, VOLUME, TRACK }

/** 头部元信息行的一段「取什么」。值怎么取由 [subjectMetaParts] 唯一决定。 */
sealed interface SubjectMetaField {
    /** 形式：条目的 `platform`（TV / 剧场版 / 漫画 / 日剧…）。 */
    data object Form : SubjectMetaField

    /** 类型名本身（「游戏」「音乐」），这两类的 `platform` 没有区分度。 */
    data object TypeName : SubjectMetaField

    /** 年月：取自条目 `date`。 */
    data object YearMonth : SubjectMetaField

    /** 话数 / 卷数 / 曲数。 */
    data class Count(val unit: SubjectCountUnit) : SubjectMetaField

    /** 资料表里第一个有值的键（按 [keys] 顺序找）。 */
    data class Infobox(val keys: List<String>) : SubjectMetaField
}

/**
 * 一个条目类型的全部「按类型变」的决定。
 *
 * @property metaFields 头部元信息行，按顺序用 MetaText 连成一行；取不到值的段直接跳过。
 * @property secondTab 第 2 个选项卡；null 表示没有（游戏）。
 * @property progressUnit 进度怎么记；null 表示没有进度（游戏、音乐、不认识的类型）。
 * @property keyPositions 主创职位（职位中文名，与制作人员数据的 `relation` 同名），按显示顺序。
 * @property verb 动词（看 / 读 / 听 / 玩）。
 * @property studioPosition 生涯「阵容」里制作公司按哪个职位数（与制作人员数据的 `relation` 同名）；
 *   null 表示这个类型的完整统计不算阵容（不逐部拉角色与制作人员）。
 */
data class SubjectTypeSpec(
    val verb: SubjectVerb,
    val metaFields: List<SubjectMetaField>,
    val secondTab: SubjectSecondTab?,
    val progressUnit: ProgressUnit?,
    val keyPositions: List<String>,
    val studioPosition: String? = null,
)

private val AnimeSpec = SubjectTypeSpec(
    verb = SubjectVerb.WATCH,
    metaFields = listOf(
        SubjectMetaField.Form,
        SubjectMetaField.YearMonth,
        SubjectMetaField.Count(SubjectCountUnit.EPISODE),
        SubjectMetaField.Infobox(listOf("动画制作")),
    ),
    secondTab = SubjectSecondTab.EPISODES,
    progressUnit = ProgressUnit.EPISODE,
    keyPositions = listOf("原作", "导演", "系列构成", "脚本", "人物设定", "音乐", "动画制作"),
    studioPosition = "动画制作",
)

/**
 * 按条目类型的**唯一规格表**。
 *
 * 页面、工具栏、编辑面板、人物 tab 里凡是「这个类型有没有 / 叫什么 / 排什么顺序」的问题都从这里取，
 * 任何地方都不准再写第二个 `when (type)`。
 *
 * 还没拿到类型（null）时按动画排版，骨架与动画同形。服务端给了不认识的类型（[SubjectType.UNKNOWN]）时
 * 版式与动词也同动画，但没有进度、没有第 2 个选项卡、不算阵容：不知道它的进度怎么记，就不让人去记。
 * 三次元「同动画，电视台代替制作」；电视台在资料表里有「电视台」「播放电视台」两种写法，按顺序取第一个有值的。
 */
fun subjectTypeSpec(type: SubjectType?): SubjectTypeSpec = when (type) {
    SubjectType.ANIME, null -> AnimeSpec
    SubjectType.UNKNOWN -> AnimeSpec.copy(secondTab = null, progressUnit = null, studioPosition = null)
    SubjectType.BOOK -> SubjectTypeSpec(
        verb = SubjectVerb.READ,
        metaFields = listOf(
            SubjectMetaField.Form,
            SubjectMetaField.YearMonth,
            SubjectMetaField.Count(SubjectCountUnit.VOLUME),
            SubjectMetaField.Infobox(listOf("出版社")),
        ),
        secondTab = SubjectSecondTab.VOLUMES,
        progressUnit = ProgressUnit.VOLUME,
        keyPositions = listOf("作者", "插图", "出版社", "连载杂志", "品牌"),
    )
    SubjectType.GAME -> SubjectTypeSpec(
        verb = SubjectVerb.PLAY,
        metaFields = listOf(
            SubjectMetaField.TypeName,
            SubjectMetaField.Infobox(listOf("平台")),
            SubjectMetaField.YearMonth,
        ),
        secondTab = null,
        progressUnit = null,
        keyPositions = listOf("开发", "发行", "剧本", "原画", "音乐"),
    )
    SubjectType.MUSIC -> SubjectTypeSpec(
        verb = SubjectVerb.LISTEN,
        metaFields = listOf(
            SubjectMetaField.TypeName,
            SubjectMetaField.YearMonth,
            SubjectMetaField.Count(SubjectCountUnit.TRACK),
        ),
        secondTab = SubjectSecondTab.TRACKS,
        progressUnit = null,
        keyPositions = listOf("艺术家", "作词", "作曲", "编曲", "录音", "发行方"),
    )
    SubjectType.REAL -> AnimeSpec.copy(
        metaFields = AnimeSpec.metaFields.dropLast(1) + SubjectMetaField.Infobox(listOf("电视台", "播放电视台")),
        keyPositions = listOf("导演", "编剧", "主演", "电视台"),
        studioPosition = null,
    )
}

/** 元信息行解析出来的一段；文字怎么拼（「26话」「2017年1月」）由界面按资源决定。 */
sealed interface SubjectMetaPart {
    /** 条目的形式（`platform`）：TV、剧场版、漫画这类短词。 */
    data class Text(val value: String) : SubjectMetaPart

    /**
     * 资料表里的值（制作公司、出版社、平台、电视台）：长短不定，可能是好几个名字。
     * 一行放不下时只截这一段，其余各段完整。
     */
    data class InfoboxValue(val value: String) : SubjectMetaPart
    data class TypeName(val type: SubjectType) : SubjectMetaPart
    data class Date(val value: SubjectYearMonth) : SubjectMetaPart
    data class Count(val unit: SubjectCountUnit, val count: Int) : SubjectMetaPart
}

/**
 * 条目 → 头部元信息行（唯一取值处）。按 [subjectTypeSpec] 的字段顺序取值，取不到的段跳过，不留空位。
 *
 * - 话数取 `eps`（不含 SP 的正片数），卷数取 `volumes`，曲数取 `totalEpisodes`（音乐的曲目按章节存，`eps` 恒 0）；
 *   0 或缺失都表示未知，跳过；
 * - 资料表取第一个有非空值的键，多个值用「、」连接。
 */
fun subjectMetaParts(subject: SubjectBo): List<SubjectMetaPart> =
    subjectTypeSpec(subject.type).metaFields.mapNotNull { field ->
        when (field) {
            SubjectMetaField.Form -> subject.platform.trim().takeIf { it.isNotEmpty() }?.let(SubjectMetaPart::Text)
            SubjectMetaField.TypeName -> SubjectMetaPart.TypeName(subject.type)
            SubjectMetaField.YearMonth -> subject.date?.let(::parseSubjectYearMonth)?.let(SubjectMetaPart::Date)
            is SubjectMetaField.Count -> when (field.unit) {
                SubjectCountUnit.EPISODE -> subject.eps
                SubjectCountUnit.VOLUME -> subject.volumes
                SubjectCountUnit.TRACK -> subject.totalEpisodes
            }?.takeIf { it > 0 }?.let { SubjectMetaPart.Count(field.unit, it) }
            is SubjectMetaField.Infobox -> field.keys.firstNotNullOfOrNull { key ->
                subject.infobox.firstOrNull { it.key == key }
                    ?.values?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString("、")
            }?.let(SubjectMetaPart::InfoboxValue)
        }
    }

/**
 * 人物 tab 的一行：一个职位和担任它的人（接口顺序）。
 */
data class StaffPositionRow(val position: String, val persons: List<RelatedPersonBo>)

/**
 * 制作人员 → 人物 tab 的职位行（唯一排法）：主创职位按 [SubjectTypeSpec.keyPositions] 顺序在前，
 * 其余职位按它在接口里第一次出现的顺序；行内人员保持接口顺序。职位名为空的行丢弃。
 */
fun staffPositionRows(persons: List<RelatedPersonBo>, type: SubjectType?): List<StaffPositionRow> {
    val byPosition = persons
        .filter { it.relation.isNotBlank() }
        .groupBy { it.relation.trim() }
    val keyOrder = subjectTypeSpec(type).keyPositions
    val keyRows = keyOrder.mapNotNull { position -> byPosition[position]?.let { StaffPositionRow(position, it) } }
    val otherRows = byPosition.filterKeys { it !in keyOrder }.map { (position, people) -> StaffPositionRow(position, people) }
    return keyRows + otherRows
}
