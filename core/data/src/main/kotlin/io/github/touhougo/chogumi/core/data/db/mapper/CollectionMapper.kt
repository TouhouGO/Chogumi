package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.model.v0.UserCollectionDto

fun UserCollectionDto.toDomain() = UserCollectionBo(
    subjectId = subjectId,
    subjectType = SubjectType.from(subjectType) ?: SubjectType.UNKNOWN,
    type = CollectionType.from(type) ?: CollectionType.UNKNOWN,
    rawType = type,
    rawSubjectType = subjectType,
    rate = rate,
    tags = tags,
    epStatus = epStatus,
    volStatus = volStatus,
    comment = comment,
    isPrivate = `private`,
    updatedAt = runCatching {
        java.time.OffsetDateTime.parse(updatedAt).toInstant().toEpochMilli()
    }.getOrDefault(0L),
    subject = subject?.toDomain(),
)

fun UserCollectionBo.toEntity(accountId: Int) = UserCollectionEntity(
    subjectId = subjectId,
    accountId = accountId,
    subjectType = rawSubjectType,
    type = rawType,
    rate = rate,
    tagsJson = tags.toJson(),
    epStatus = epStatus,
    volStatus = volStatus,
    comment = comment,
    isPrivate = isPrivate,
    updatedAt = updatedAt,
)

fun UserCollectionEntity.toBo(subject: SubjectBo? = null) = UserCollectionBo(
    subjectId = subjectId,
    subjectType = SubjectType.from(subjectType) ?: SubjectType.UNKNOWN,
    type = CollectionType.from(type) ?: CollectionType.UNKNOWN,
    rawType = type,
    rawSubjectType = subjectType,
    rate = rate,
    tags = tagsJson.toStringList(),
    epStatus = epStatus,
    volStatus = volStatus,
    comment = comment,
    isPrivate = isPrivate,
    updatedAt = updatedAt,
    subject = subject,
)
