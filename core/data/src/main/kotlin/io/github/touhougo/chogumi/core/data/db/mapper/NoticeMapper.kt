package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.model.NoticeSenderBo
import io.github.touhougo.chogumi.core.model.userAvatarUrl
import io.github.touhougo.chogumi.core.network.model.p1.NoticeDto

fun NoticeDto.toDomain(): NoticeBo = NoticeBo(
    id = id,
    type = type,
    sender = NoticeSenderBo(
        id = sender.id,
        username = sender.username,
        nickname = sender.nickname.unescapeHtmlEntities(),
        avatar = userAvatarUrl(sender.avatar.large, sender.avatar.medium, sender.avatar.small).orEmpty(),
    ),
    title = title.unescapeHtmlEntities(),
    mainId = mainID,
    relatedId = relatedID,
    createdAt = createdAt.epochSecondsToMillis(),
    unread = unread,
)
