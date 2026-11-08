package io.github.touhougo.chogumi.core.model

/** 一行键值资料：作品的「ⓘ 资料」面板、人物与角色的资料表与「全部资料」面板共用。多个值已用「、」连接。 */
data class FactRow(val key: String, val value: String)

/** 作品资料与未关联职位的制作人员原文；两个界面共用一次分类结果。 */
data class SubjectInfoPartition(val workFacts: List<FactRow>, val otherStaffFacts: List<FactRow>)

/**
 * 作品资料唯一分类：仅收录官方作品模板的名称、放送/发行、数量、规格、链接与外部标识字段。
 * 来源：Bangumi 官方 common/wiki_template.yml，固定版本 9568390b7bfd7bdf14c662fe8f28256654ed2983：
 * https://raw.githubusercontent.com/bangumi/common/9568390b7bfd7bdf14c662fe8f28256654ed2983/wiki_template.yml
 * 制作职位语义对照 https://raw.githubusercontent.com/bangumi/common/master/subject_staffs.yml，
 * 出版社、连载杂志、厂牌也属于职位；职位清单 2026-10-05 核对过。
 *
 * 非白名单键保留为其他制作人员；关联职位仅去除这些文本行的重复，不影响作品资料准入。
 * 键和值只去外侧空白，空键/空值忽略，多个值用「、」连接，各组保持服务端顺序。
 * @param displayedTitle 调用方复用已有标题派生的显示标题，用于省略同名中文名。
 * @param linkedStaffPositions 已有关联制作人员的职位名（RelatedPersonBo.relation）。
 */
fun subjectInfoPartition(
    subject: SubjectBo,
    displayedTitle: String,
    linkedStaffPositions: Set<String>,
): SubjectInfoPartition {
    val workFacts = mutableListOf<FactRow>()
    val otherStaffFacts = mutableListOf<FactRow>()
    val linkedPositions = linkedStaffPositions.map(String::trim).toSet()
    subject.infobox.forEach { item ->
        val key = item.key.trim()
        val value = item.values.map(String::trim).filter(String::isNotEmpty).joinToString("、")
        if (key.isEmpty() || value.isEmpty()) return@forEach
        val row = FactRow(key, value)
        if (key in WORK_FACT_KEYS) {
            if (key != "中文名" || value != displayedTitle.trim()) workFacts += row
        } else if (key !in linkedPositions) {
            otherStaffFacts += row
        }
    }
    return SubjectInfoPartition(workFacts, otherStaffFacts)
}

/** 官方作品模板中筛选的 53 个原始键；不按作品类型复制分类规则，不扩充未经证实的别名。 */
private val WORK_FACT_KEYS = setOf(
    "中文名", "别名", "话数", "放送开始", "放送星期", "官方网站", "在线播放平台", "播放电视台",
    "其他电视台", "播放结束", "链接", "Copyright", "发售日", "开始", "结束", "上映年度", "片长",
    "价格", "页数", "ISBN", "册数", "发售日期", "版本特性", "播放时长", "碟片数量", "平台",
    "游戏类型", "游戏引擎", "游玩人数", "发行日期", "售价", "website", "集数", "季数", "类型",
    "国家/地区", "语言", "每集长", "电视网", "电视台", "频道", "视频制式", "音频制式", "首播国家",
    "首播地区", "台湾名称", "港澳名称", "马新名称", "imdb_id", "tvdb_id", "上映日", "tmdb_id", "尺寸",
)

/** 标签行的一枚标签：[isMeta] 为真是形式标签（加粗、在前）。 */
data class SubjectTagChip(val name: String, val isMeta: Boolean)

/**
 * 详情页标签行（唯一判定）：形式标签（metaTags）在前，用户标签去重在后——去掉与形式标签、标题、原名同名的，
 * 以及重复项。保持服务端顺序（用户标签已按人数降序）。
 */
fun subjectTagRow(subject: SubjectBo): List<SubjectTagChip> {
    val meta = subject.metaTags.map(String::trim).filter(String::isNotEmpty).distinct()
    val excluded = meta.toSet() + setOf(subject.nameCn.trim(), subject.name.trim())
    val user = subject.tags.map { it.name.trim() }.filter { it.isNotEmpty() && it !in excluded }.distinct()
    return meta.map { SubjectTagChip(it, isMeta = true) } + user.map { SubjectTagChip(it, isMeta = false) }
}

/** 曲目 tab 的一张碟。[disc] 为 0 表示接口没给碟号（不分碟）。 */
data class TrackDisc(val disc: Int, val tracks: List<EpisodeBo>)

/**
 * 音乐的曲目按碟分组（唯一判定；曲目 tab 沿用章节组件，按碟分组）：
 * 只取正片（[EpisodeTracking.mainEpisodes]，与章节同一切分），碟按碟号升序，碟内按 `sort` 升序。
 */
fun trackDiscs(episodes: List<EpisodeBo>): List<TrackDisc> =
    EpisodeTracking.mainEpisodes(episodes)
        .groupBy { it.disc }
        .toSortedMap()
        .map { (disc, tracks) -> TrackDisc(disc, tracks.sortedBy { it.sort }) }

/**
 * 书籍的「单行本」tab：单行本关联（relation 1003）按卷号（服务端 order）升序。
 * 关系归类只经 [relationKindOf]，不在这里认 1003 这个数字。
 */
fun subjectVolumes(relations: List<SubjectRelationBo>): List<SubjectRelationBo> =
    relations.filter { relationKindOf(it.relationId, it.type) == SubjectRelationKind.OFFPRINT }.sortedBy { it.order }
