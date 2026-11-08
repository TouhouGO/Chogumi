package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/** 条目领域模型（首页与详情所需字段的超集） */
data class SubjectBo(
    val id: Int,
    val type: SubjectType,
    override val name: String,
    override val nameCn: String,
    val summary: String = "",
    val nsfw: Boolean = false,
    val date: String? = null,
    val imageLarge: String? = null,
    val imageCommon: String? = null,
    val ratingScore: Double = 0.0,
    val ratingRank: Int = 0,
    val ratingTotal: Int = 0,
    /**
     * 评分 1–10 分各档人数，下标 0 是 1 分。只有详情接口给；列表快照与旧缓存为空表，表示「未知」而不是「没人打分」。
     */
    val ratingCounts: List<Int> = emptyList(),
    /** 收藏人数（来自 SlimSubject 的 collection_total；与评分人数 ratingTotal 语义不同） */
    val collectionTotal: Int = 0,
    val eps: Int? = null,
    val volumes: Int? = null,
    val totalEpisodes: Int? = null,
    val collectionWish: Int = 0,
    val collectionCollect: Int = 0,
    val collectionDoing: Int = 0,
    val collectionOnHold: Int = 0,
    val collectionDropped: Int = 0,
    val metaTags: List<String> = emptyList(),
    val tags: List<TagBo> = emptyList(),
    /** 未识别枚举仍保留服务端数值，不能误映射为动画。 */
    val rawType: Int = type.value,
    /** Bangumi 完整条目返回的平台字段。 */
    val platform: String = "",
    /** Bangumi 完整条目返回的资料项，保持服务端顺序。 */
    val infobox: List<SubjectInfoboxBo> = emptyList(),
    /**
     * 这条记录是否已被**详情接口**写过（而不是只有列表接口的截断快照）。
     *
     * 列表接口（p1 放送/浏览/热门、v0 搜索）落库的摘要行只带一部分字段——p1 短行有 metaTags
     * 却没有 tags/summary/date/platform，v0 搜索行反过来有 tags/summary 却没有 metaTags。
     * 详情页从列表点进来时读到的就是这种行，缺的区块等完整数据到了才插进来，会让下面的内容整体下移。
     *
     * 真源是 `subjects` 行的 `updatedAt`（详情接口写入时非 0，摘要行恒为 0），由
     * `SubjectEntity.toBo()` 投影到这里；首屏据此为缺席的区块预留占位。
     */
    val hasFullDetail: Boolean = true,
) : NamedEntity {
    /**
     * 封面图：大图（原图）优先，没有再用普通图；都没有（或是空串）为 null。全 App 选封面图只经这里。
     * 按画出来的宽换成图床的缩放档由图片管线一处决定（core:ui `CoverSizeInterceptor`），调用方不分大图小图。
     */
    val coverUrl: String? get() = imageLarge.nonBlank() ?: imageCommon.nonBlank()
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

/** 条目标签（@Serializable 供 SubjectMapper 用 kotlinx-serialization 编解码 Room JSON 列） */
@Serializable
data class TagBo(
    val name: String,
    val count: Int,
)

/** 每日放送条目 */
data class CalendarItemBo(
    val subject: SubjectBo,
    val watchers: Int = 0,
)

/** 某个收藏状态的人数（条目详情的五态计数；[CollectionType.UNKNOWN] 没有计数，为 0）。 */
fun SubjectBo.collectionCountOf(type: CollectionType): Int = when (type) {
    CollectionType.WISH -> collectionWish
    CollectionType.DONE -> collectionCollect
    CollectionType.DOING -> collectionDoing
    CollectionType.ON_HOLD -> collectionOnHold
    CollectionType.DROPPED -> collectionDropped
    CollectionType.UNKNOWN -> 0
}
