package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity

/** 只更新来源实际承诺的字段；摘要与完整详情的覆盖范围不由 Bo 的默认值隐式决定。 */
enum class SubjectField {
    TYPE, NAME, NAME_CN, SUMMARY, NSFW, DATE, IMAGE_LARGE, IMAGE_COMMON,
    RATING_SCORE, RATING_RANK, RATING_TOTAL, COLLECTION_TOTAL, EPS, VOLUMES,
    TOTAL_EPISODES, COLLECTION_COUNTS, META_TAGS, TAGS, PLATFORM, INFOBOX, RATING_COUNTS,
}

data class SubjectSnapshot(
    val value: SubjectEntity,
    val fields: Set<SubjectField> = SubjectField.entries.toSet(),
    val detail: Boolean = false,
) {
    fun mergeInto(previous: SubjectEntity?): SubjectEntity {
        // 快照的 updatedAt 是它的生成时刻：无论摘要还是详情，都记为这一行最近一次被用到（touchedAt，按天前进）。
        if (previous == null) return value.copy(updatedAt = if (detail) value.updatedAt else 0, touchedAt = value.updatedAt)
        val old = previous
        fun has(field: SubjectField) = field in fields
        // 完整详情优先于截断摘要。摘要不能让详情 TTL 续期，也不能把未知话数视为完整信息。
        fun detailField(field: SubjectField) = has(field) && (detail || old.updatedAt == 0L)
        return old.copy(
            type = if (has(SubjectField.TYPE)) value.type else old.type,
            name = if (has(SubjectField.NAME)) value.name else old.name,
            nameCn = if (has(SubjectField.NAME_CN)) value.nameCn else old.nameCn,
            summary = if (detailField(SubjectField.SUMMARY)) value.summary else old.summary,
            nsfw = if (has(SubjectField.NSFW)) value.nsfw else old.nsfw,
            date = if (has(SubjectField.DATE)) value.date else old.date,
            platform = if (has(SubjectField.PLATFORM)) value.platform else old.platform,
            imageLarge = if (has(SubjectField.IMAGE_LARGE)) value.imageLarge else old.imageLarge,
            imageCommon = if (has(SubjectField.IMAGE_COMMON)) value.imageCommon else old.imageCommon,
            ratingScore = if (has(SubjectField.RATING_SCORE)) value.ratingScore else old.ratingScore,
            ratingRank = if (has(SubjectField.RATING_RANK)) value.ratingRank else old.ratingRank,
            ratingTotal = if (has(SubjectField.RATING_TOTAL)) value.ratingTotal else old.ratingTotal,
            ratingCountsJson = if (has(SubjectField.RATING_COUNTS)) value.ratingCountsJson else old.ratingCountsJson,
            collectionTotal = if (has(SubjectField.COLLECTION_TOTAL)) value.collectionTotal else old.collectionTotal,
            eps = if (detailField(SubjectField.EPS)) value.eps else old.eps,
            volumes = if (detailField(SubjectField.VOLUMES)) value.volumes else old.volumes,
            totalEpisodes = if (has(SubjectField.TOTAL_EPISODES)) value.totalEpisodes else old.totalEpisodes,
            collectionWish = if (has(SubjectField.COLLECTION_COUNTS)) value.collectionWish else old.collectionWish,
            collectionCollect = if (has(SubjectField.COLLECTION_COUNTS)) value.collectionCollect else old.collectionCollect,
            collectionDoing = if (has(SubjectField.COLLECTION_COUNTS)) value.collectionDoing else old.collectionDoing,
            collectionOnHold = if (has(SubjectField.COLLECTION_COUNTS)) value.collectionOnHold else old.collectionOnHold,
            collectionDropped = if (has(SubjectField.COLLECTION_COUNTS)) value.collectionDropped else old.collectionDropped,
            metaTagsJson = if (has(SubjectField.META_TAGS)) value.metaTagsJson else old.metaTagsJson,
            tagsJson = if (detailField(SubjectField.TAGS)) value.tagsJson else old.tagsJson,
            infoboxJson = if (detailField(SubjectField.INFOBOX)) value.infoboxJson else old.infoboxJson,
            updatedAt = if (detail) value.updatedAt else old.updatedAt,
            touchedAt = if (value.updatedAt - old.touchedAt >= TOUCH_STEP_MILLIS) value.updatedAt else old.touchedAt,
        )
    }
}

/**
 * 「最近一次被用到」（touchedAt）只按天前进：同一天里再被写到，内容没变的行就和库里那一行相等，
 * `SubjectDao.merge` / `mergeAll` 因而不写它——不写就不触发 `subjects` 表的失效，订阅收藏的查询也就不重跑。
 * 每次写入都记到毫秒的话，探索页一个货架、放送表一轮刷新就让每部被收藏的在播作品「变了」一次。
 * 清理策略按 30 天判「多久没人用到」（`LocalCacheJanitor.SUBJECTS_POLICY`），差一天无碍。
 */
internal const val TOUCH_STEP_MILLIS = 24 * 60 * 60 * 1000L
