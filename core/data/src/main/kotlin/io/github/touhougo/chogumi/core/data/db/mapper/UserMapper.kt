package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.model.p1.UserDto as P1UserDto
import io.github.touhougo.chogumi.core.network.model.v0.UserDto

fun UserDto.toDomain() = UserBo(
    id = id,
    username = username,
    nickname = nickname,
    avatarLarge = avatar.large,
    avatarMedium = avatar.medium,
    avatarSmall = avatar.small,
    sign = sign,
)

fun UserBo.toEntity(updatedAt: Long) = UserEntity(
    id = id,
    username = username,
    nickname = nickname,
    avatarLarge = avatarLarge,
    avatarMedium = avatarMedium,
    avatarSmall = avatarSmall,
    sign = sign,
    updatedAt = updatedAt,
)

fun UserEntity.toBo() = UserBo(
    id = id,
    username = username,
    nickname = nickname,
    avatarLarge = avatarLarge,
    avatarMedium = avatarMedium,
    avatarSmall = avatarSmall,
    sign = sign,
)

/**
 * p1 用户资料 → 领域模型。
 *
 * `stats.subject` 的 wire 形状是 `Map<String, Map<String, Int>>`（类型 → 状态 → 条数），
 * key 是**字符串形式的数字**。服务端已用零填满全部 类型×状态 组合
 * （见 bangumi-server 的 `countUserSubjectCollection`），所以这里不能假设
 * "缺 key 就是没有"，未知枚举按丢弃处理即可。
 */
fun P1UserDto.toFullBo() = UserBo(
    id = id,
    username = username,
    nickname = nickname.unescapeHtmlEntities(),
    avatarLarge = avatar.large,
    avatarMedium = avatar.medium,
    avatarSmall = avatar.small,
    sign = sign.unescapeHtmlEntities(),
    site = site,
    location = location,
    bio = bio,
    joinedAt = joinedAt.epochSecondsToMillis(),
    subjectStats = stats.subject.toSubjectStats(),
    characterCount = stats.mono.character,
    personCount = stats.mono.person,
    blogCount = stats.blog,
    groupCount = stats.group,
)

private fun Map<String, Map<String, Int>>.toSubjectStats(): Map<SubjectType, Map<CollectionType, Int>> {
    val result = mutableMapOf<SubjectType, Map<CollectionType, Int>>()
    for ((typeKey, row) in this) {
        val type = SubjectType.from(typeKey.toIntOrNull() ?: continue) ?: continue
        val counts = mutableMapOf<CollectionType, Int>()
        for ((statusKey, count) in row) {
            val status = CollectionType.from(statusKey.toIntOrNull() ?: continue) ?: continue
            if (status != CollectionType.UNKNOWN) counts[status] = count
        }
        if (counts.isNotEmpty()) result[type] = counts
    }
    return result
}
