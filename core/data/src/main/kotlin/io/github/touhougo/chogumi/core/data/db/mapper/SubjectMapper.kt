package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectInfoboxBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagBo
import io.github.touhougo.chogumi.core.model.infoboxValueText
import io.github.touhougo.chogumi.core.network.model.v0.EpisodeDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxEntryDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxItemDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxKeyValueDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxListDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxOnlyValueDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxTextDto
import io.github.touhougo.chogumi.core.network.model.v0.SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.v0.SubjectDto

/** 评分档数（1–10 分）。 */
private const val RATING_LEVELS = 10

/**
 * v0 的评分分布是 `{"1": n, …, "10": n}` 字典 → 下标 0 是 1 分的 10 项列表；缺的档记 0。
 * 整个字典为空（接口没给）时返回空表，表示「未知」，不能当成「没人打分」。
 */
private fun Map<String, Int>.toRatingCounts(): List<Int> =
    if (isEmpty()) emptyList() else List(RATING_LEVELS) { this[(it + 1).toString()] ?: 0 }

/** v0 资料表的一个值 → 显示文本（规则见 [infoboxValueText]）。 */
private fun InfoboxEntryDto.toDisplayText(): String? = when (this) {
    is InfoboxKeyValueDto -> infoboxValueText(k, v)
    is InfoboxOnlyValueDto -> infoboxValueText(null, v)
}

private fun InfoboxItemDto.toDomain(): SubjectInfoboxBo? {
    val normalizedKey = key.trim()
    val normalizedValues = when (val itemValue = value) {
        is InfoboxTextDto -> listOf(itemValue.value)
        is InfoboxListDto -> itemValue.value.mapNotNull { it.toDisplayText() }
    }.map(String::trim).filter(String::isNotBlank)
    return normalizedKey.takeIf(String::isNotBlank)?.let {
        normalizedValues.takeIf(List<String>::isNotEmpty)?.let { values ->
            SubjectInfoboxBo(key = it, values = values)
        }
    }
}

fun SubjectDto.toDomain() = SubjectBo(
    id = id,
    type = SubjectType.from(type) ?: SubjectType.UNKNOWN,
    rawType = type,
    name = name,
    nameCn = nameCn,
    summary = summary,
    nsfw = nsfw,
    date = date,
    platform = platform,
    infobox = infobox.orEmpty().mapNotNull { it.toDomain() },
    imageLarge = images.large,
    imageCommon = images.common,
    ratingScore = rating.score,
    ratingRank = rating.rank,
    ratingTotal = rating.total,
    ratingCounts = rating.count.toRatingCounts(),
    collectionTotal = collection.wish + collection.collect + collection.doing + collection.onHold + collection.dropped,
    eps = eps,
    volumes = volumes,
    totalEpisodes = totalEpisodes,
    collectionWish = collection.wish,
    collectionCollect = collection.collect,
    collectionDoing = collection.doing,
    collectionOnHold = collection.onHold,
    collectionDropped = collection.dropped,
    metaTags = metaTags,
    tags = tags.map { TagBo(it.name, it.count) },
)

fun SlimSubjectDto.toDomain() = SubjectBo(
    id = id,
    type = SubjectType.from(type) ?: SubjectType.UNKNOWN,
    rawType = type,
    name = name,
    nameCn = nameCn,
    summary = shortSummary,
    date = date,
    imageLarge = images.large,
    imageCommon = images.common,
    ratingScore = score,
    ratingRank = rank,
    collectionTotal = collectionTotal,
    eps = eps,
    volumes = volumes,
    tags = tags.map { TagBo(it.name, it.count) },
    // 搜索短行：没有 metaTags，不是完整详情。
    hasFullDetail = false,
)

fun SubjectDto.toSnapshot(updatedAt: Long, detail: Boolean = true) = SubjectSnapshot(
    value = toDomain().toEntity(updatedAt = updatedAt),
    fields = SubjectField.entries.toSet() - buildSet {
        if (date == null) add(SubjectField.DATE)
    },
    detail = detail,
)

fun SlimSubjectDto.toSnapshot(updatedAt: Long) = SubjectSnapshot(
    value = toDomain().toEntity(updatedAt),
    fields = buildSet {
        addAll(listOf(SubjectField.TYPE, SubjectField.NAME, SubjectField.NAME_CN,
            SubjectField.SUMMARY, SubjectField.EPS, SubjectField.VOLUMES,
            SubjectField.COLLECTION_TOTAL, SubjectField.RATING_SCORE, SubjectField.RATING_RANK,
            SubjectField.TAGS, SubjectField.IMAGE_LARGE, SubjectField.IMAGE_COMMON))
        if (date != null) add(SubjectField.DATE)
    },
)

fun SubjectBo.toEntity(updatedAt: Long) = SubjectEntity(
    id = id,
    type = rawType,
    name = name,
    nameCn = nameCn,
    summary = summary,
    nsfw = nsfw,
    date = date,
    platform = platform,
    imageLarge = imageLarge,
    imageCommon = imageCommon,
    ratingScore = ratingScore,
    ratingRank = ratingRank,
    ratingTotal = ratingTotal,
    collectionTotal = collectionTotal,
    eps = eps,
    volumes = volumes,
    totalEpisodes = totalEpisodes,
    collectionWish = collectionWish,
    collectionCollect = collectionCollect,
    collectionDoing = collectionDoing,
    collectionOnHold = collectionOnHold,
    collectionDropped = collectionDropped,
    metaTagsJson = metaTags.toJson(),
    tagsJson = tags.toJson(),
    infoboxJson = infobox.toJson(),
    ratingCountsJson = ratingCounts.toJson(),
    updatedAt = updatedAt,
)

fun SubjectEntity.toBo() = SubjectBo(
    id = id,
    type = SubjectType.from(type) ?: SubjectType.UNKNOWN,
    rawType = type,
    name = name,
    nameCn = nameCn,
    summary = summary,
    nsfw = nsfw,
    date = date,
    platform = platform,
    imageLarge = imageLarge,
    imageCommon = imageCommon,
    ratingScore = ratingScore,
    ratingRank = ratingRank,
    ratingTotal = ratingTotal,
    collectionTotal = collectionTotal,
    eps = eps,
    volumes = volumes,
    totalEpisodes = totalEpisodes,
    collectionWish = collectionWish,
    collectionCollect = collectionCollect,
    collectionDoing = collectionDoing,
    collectionOnHold = collectionOnHold,
    collectionDropped = collectionDropped,
    metaTags = metaTagsJson.toStringList(),
    tags = tagsJson.toTagList(),
    infobox = infoboxJson.toInfoboxList(),
    ratingCounts = ratingCountsJson.toIntList(),
    // updatedAt 为 0 表示这行只被列表接口的截断快照写过，从未经过详情接口补全。
    hasFullDetail = updatedAt != 0L,
)

fun EpisodeDto.toDomain() = EpisodeBo(
    id = id,
    subjectId = subjectId,
    type = type,
    name = name,
    nameCn = nameCn,
    sort = sort,
    ep = ep,
    airdate = airdate,
    comment = comment,
    desc = desc,
    disc = disc,
)

fun EpisodeBo.toEntity(updatedAt: Long) = EpisodeEntity(
    id = id,
    subjectId = subjectId,
    type = type,
    name = name,
    nameCn = nameCn,
    sort = sort,
    ep = ep,
    airdate = airdate,
    comment = comment,
    desc = desc,
    updatedAt = updatedAt,
    disc = disc,
)

fun EpisodeEntity.toBo() = EpisodeBo(
    id = id,
    subjectId = subjectId,
    type = type,
    name = name,
    nameCn = nameCn,
    sort = sort,
    ep = ep,
    airdate = airdate,
    comment = comment,
    desc = desc,
    disc = disc,
)
