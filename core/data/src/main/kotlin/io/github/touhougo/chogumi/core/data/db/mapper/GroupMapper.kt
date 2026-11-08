package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.userAvatarUrl
import io.github.touhougo.chogumi.core.network.model.p1.AvatarDto
import io.github.touhougo.chogumi.core.network.model.p1.GroupDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimGroupDto

// 小组 → 领域模型。p1 时间一律 Unix 秒。

fun SlimGroupDto.toGroupSummaryBo() = GroupSummaryBo(
    name = name,
    title = title.unescapeHtmlEntities(),
    icon = icon.groupIconUrl(),
    members = members,
)

/**
 * 小组页头部。`membership` 是当前账号在这个小组里的身份：成员、组长、管理员、禁言成员（role ≥ 0）都算已加入，
 * 访客、游客（负数）与没有这一项都算没加入。
 */
fun GroupDto.toGroupBo() = GroupBo(
    summary = GroupSummaryBo(name = name, title = title.unescapeHtmlEntities(), icon = icon.groupIconUrl(), members = members),
    description = description,
    topics = topics,
    createdAt = createdAt.epochSecondsToMillis(),
    joined = membership?.let { it.role >= 0 } == true,
)

/** 小组图标取最大的一份（小组页头部、置顶行都画得比列表头像大）；服务端没有图标时给空串。 */
internal fun AvatarDto.groupIconUrl(): String? = userAvatarUrl(large, medium, small)
