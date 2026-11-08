package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/**
 * 条目关联的一项：对面条目 + 关系类型 ID（p1 `/subjects/{id}/relations`）。
 * 关系怎么归类、怎么排、怎么分组，全部由 [relationKindOf] 与 [groupSubjectRelations] 决定。
 * @Serializable：按 kotlinx JSON 整存进本地缓存表（同 [RelatedImagesBo] 的说明）。
 */
@Serializable
data class SubjectRelationBo(
    val id: Int,
    val type: SubjectType,
    override val name: String,
    override val nameCn: String,
    val images: RelatedImagesBo? = null,
    /** 服务端关系类型 ID（bangumi/common subject_relations.yml），语义见 [relationKindOf]。 */
    val relationId: Int,
    /** 同一关系类型内的服务端排序值。 */
    val order: Int = 0,
    /** 对面条目是否 NSFW；详情页关联列表按 NSFW 开关过滤（默认 false 不误伤存量构造）。 */
    val nsfw: Boolean = false,
    /**
     * 对面条目的评分；0 是还没有评分。不给默认值：缓存里缺这一字段的行解析失败，按未命中重新拉取，
     * 不会把有分的作品读成 0。
     */
    val score: Double,
) : NamedEntity

/**
 * 关联作品的权重组（设计稿的「关联作品权重」）。**声明顺序就是显示顺序**。
 * 关联子页按组分段；概览按这个顺序取前若干个。
 */
enum class SubjectRelationWeight {
    /** 主线：改编、前传、续集、主线故事、系列、总集篇、全集。 */
    MAIN,

    /** 衍生：番外篇、不同演绎、相同世界观、衍生、单行本、不同版本。 */
    DERIVATIVE,

    /** 音乐与周边：原声集、片头曲、片尾曲、插入歌、角色歌、印象曲、广播剧、画集。 */
    MUSIC_AND_MERCH,

    /** 客串与其他：其余所有关系。 */
    OTHER,
}

/**
 * 关系的语义归类与它所属的权重组。**声明顺序就是详情页的排序**：先按 [weight] 分组，
 * 组内按设计稿的顺序；「客串与其他」组内设计稿没有规定，按这里的声明顺序。
 *
 * 设计稿主线组里的「原作」没有独立的关系 ID：网页上的「原作」与「改编」是 ID 1 的两个方向，
 * 统一归 [ADAPTATION]（2026-10-05 实测）。
 * 关系 ID 不在对面条目类型那张表里的一项也归 [ADAPTATION]（见 [relationKindOf]）：它和改编一样按对面条目类型显示。
 */
enum class SubjectRelationKind(val weight: SubjectRelationWeight) {
    ADAPTATION(SubjectRelationWeight.MAIN),
    PREQUEL(SubjectRelationWeight.MAIN),
    SEQUEL(SubjectRelationWeight.MAIN),
    PARENT_STORY(SubjectRelationWeight.MAIN),
    SERIES(SubjectRelationWeight.MAIN),
    SUMMARY(SubjectRelationWeight.MAIN),
    FULL_STORY(SubjectRelationWeight.MAIN),
    SIDE_STORY(SubjectRelationWeight.DERIVATIVE),
    ALTERNATIVE_VERSION(SubjectRelationWeight.DERIVATIVE),
    SAME_SETTING(SubjectRelationWeight.DERIVATIVE),
    SPIN_OFF(SubjectRelationWeight.DERIVATIVE),
    OFFPRINT(SubjectRelationWeight.DERIVATIVE),
    VERSION(SubjectRelationWeight.DERIVATIVE),
    OST(SubjectRelationWeight.MUSIC_AND_MERCH),
    OPENING_SONG(SubjectRelationWeight.MUSIC_AND_MERCH),
    ENDING_SONG(SubjectRelationWeight.MUSIC_AND_MERCH),
    INSERT_SONG(SubjectRelationWeight.MUSIC_AND_MERCH),
    CHARACTER_SONG(SubjectRelationWeight.MUSIC_AND_MERCH),
    IMAGE_SONG(SubjectRelationWeight.MUSIC_AND_MERCH),
    DRAMA(SubjectRelationWeight.MUSIC_AND_MERCH),
    ALBUM(SubjectRelationWeight.MUSIC_AND_MERCH),
    CHARACTER(SubjectRelationWeight.OTHER),
    ALTERNATIVE_SETTING(SubjectRelationWeight.OTHER),
    COLLABORATION(SubjectRelationWeight.OTHER),
    MAIN_VERSION(SubjectRelationWeight.OTHER),
    DLC(SubjectRelationWeight.OTHER),
    COLLECTION(SubjectRelationWeight.OTHER),
    IN_COLLECTION(SubjectRelationWeight.OTHER),
    OTHER(SubjectRelationWeight.OTHER),
}

/**
 * 一项关联 → 语义归类（唯一映射）：[relationId] 是服务端关系类型 ID，[relatedType] 是对面条目的类型。
 *
 * ID 出自 bangumi/common subject_relations.yml，按**被描述的那一边**的条目类型分表：动画与三次元共用 2~99，
 * 书籍 1002~1099，音乐 3001~3099，游戏 4002~4099，1 是动画、三次元、书籍、游戏共用的「改编」（[relationTableOf]）。
 * ID 在对面条目类型的表里，它描述的是对面条目（游戏的关联里音乐条目带 3001：对面是这部游戏的原声集），只凭 ID 归类。
 * 不在表里，它描述的是当前条目这一边（音乐条目的关联里游戏带同一个 3001：当前条目是那部游戏的原声集，
 * 游戏不是原声集）：没有对面条目的关系名，和网页一样按对面条目类型显示，归 [SubjectRelationKind.ADAPTATION]。
 * 实测服务端按对面条目类型查表，这时给的 cn 是空串、网页显示对面的类型（「游戏」「书籍」）。
 * 不依赖服务端返回的中文名。未知 ID、未知对面类型只凭 ID 归类，认不出的归入 [SubjectRelationKind.OTHER]。
 */
fun relationKindOf(relationId: Int, relatedType: SubjectType): SubjectRelationKind {
    val table = relationTableOf(relationId)
    if (table != null && relatedType != SubjectType.UNKNOWN && relatedType !in table) return SubjectRelationKind.ADAPTATION
    return kindOfRelationId(relationId)
}

/** 关系 ID 所在的表描述哪几类条目；不在任何一段里是 null。 */
private fun relationTableOf(relationId: Int): Set<SubjectType>? = when (relationId) {
    1 -> setOf(SubjectType.ANIME, SubjectType.REAL, SubjectType.BOOK, SubjectType.GAME)
    in 2..99 -> setOf(SubjectType.ANIME, SubjectType.REAL)
    in 1002..1099 -> setOf(SubjectType.BOOK)
    in 3001..3099 -> setOf(SubjectType.MUSIC)
    in 4002..4099 -> setOf(SubjectType.GAME)
    else -> null
}

/** ID → 它在自己那张表里的语义。各段 ID 互不重叠，所以只凭 ID 就能归类。 */
private fun kindOfRelationId(relationId: Int): SubjectRelationKind = when (relationId) {
    1 -> SubjectRelationKind.ADAPTATION
    2, 1005, 4002 -> SubjectRelationKind.PREQUEL
    3, 1006, 4003 -> SubjectRelationKind.SEQUEL
    4 -> SubjectRelationKind.SUMMARY
    5 -> SubjectRelationKind.FULL_STORY
    6, 1007, 4006 -> SubjectRelationKind.SIDE_STORY
    7, 1011, 4007 -> SubjectRelationKind.CHARACTER
    8, 1012, 4008 -> SubjectRelationKind.SAME_SETTING
    9, 1013, 4009 -> SubjectRelationKind.ALTERNATIVE_SETTING
    10, 1015, 4010 -> SubjectRelationKind.ALTERNATIVE_VERSION
    11 -> SubjectRelationKind.SPIN_OFF
    12, 1008, 4012 -> SubjectRelationKind.PARENT_STORY
    14, 1014, 4014 -> SubjectRelationKind.COLLABORATION
    1002 -> SubjectRelationKind.SERIES
    1003 -> SubjectRelationKind.OFFPRINT
    1004 -> SubjectRelationKind.ALBUM
    1010, 4016 -> SubjectRelationKind.VERSION
    4015 -> SubjectRelationKind.DLC
    4017 -> SubjectRelationKind.MAIN_VERSION
    4018 -> SubjectRelationKind.COLLECTION
    4019 -> SubjectRelationKind.IN_COLLECTION
    3001 -> SubjectRelationKind.OST
    3002 -> SubjectRelationKind.CHARACTER_SONG
    3003 -> SubjectRelationKind.OPENING_SONG
    3004 -> SubjectRelationKind.ENDING_SONG
    3005 -> SubjectRelationKind.INSERT_SONG
    3006 -> SubjectRelationKind.IMAGE_SONG
    3007 -> SubjectRelationKind.DRAMA
    else -> SubjectRelationKind.OTHER
}

/**
 * 详情页的一个关联分组。[adaptationType] 仅在 [kind] 为改编时非空：改编按对面条目类型再拆
 * （与网页、旧 v0 接口一致，显示为「书籍」「动画」「游戏」）。
 */
data class SubjectRelationGroup(
    val kind: SubjectRelationKind,
    val adaptationType: SubjectType?,
    val items: List<SubjectRelationBo>,
)

/**
 * 关联条目分组与排序（唯一真源）：按 [relationKindOf] 归类，改编再按对面条目类型拆开；
 * 组按 [SubjectRelationKind] 声明顺序排（即先按权重组、组内按设计稿顺序；改编各组按 [SubjectType] 声明顺序）；
 * 组内按服务端 order 升序，相同 order 保持服务端返回顺序。
 * 需要「按权重组分段」的地方对结果按 `kind.weight` 再分段即可，顺序已经排好。
 */
fun groupSubjectRelations(relations: List<SubjectRelationBo>): List<SubjectRelationGroup> =
    relations
        .groupBy { relation ->
            val kind = relationKindOf(relation.relationId, relation.type)
            kind to relation.type.takeIf { kind == SubjectRelationKind.ADAPTATION }
        }
        .map { (key, items) -> SubjectRelationGroup(key.first, key.second, items.sortedBy { it.order }) }
        .sortedWith(compareBy({ it.kind.ordinal }, { it.adaptationType?.ordinal ?: -1 }))
