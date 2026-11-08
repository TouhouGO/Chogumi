package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.TimelineCategory
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineReactionTarget
import io.github.touhougo.chogumi.core.model.TimelineSegmentBo
import io.github.touhougo.chogumi.core.model.TimelineTargetBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.timelineCategoryOf
import io.github.touhougo.chogumi.core.model.userAvatarUrl
import io.github.touhougo.chogumi.core.model.userDisplayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.P1UserDto
import io.github.touhougo.chogumi.core.network.model.p1.ReactionDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineDto

fun P1UserDto.toUserBo() = UserBo(id, username, nickname.unescapeHtmlEntities(),
    avatar.large, avatar.medium, avatar.small, sign.unescapeHtmlEntities())

fun TimelineDto.toTimelineItem(currentUserId: Int? = null, fallbackUser: UserBo? = null): TimelineItemBo {
    val targets = buildList {
        memo.daily?.users.orEmpty().forEach { user ->
            add(TimelineTargetBo(
                userDisplayNameOf(user.username, user.nickname.unescapeHtmlEntities(), user.id),
                BgmWeb.user(userKeyOf(user.username, user.id)),
                description = user.sign.unescapeHtmlEntities(),
                imageUrl = userAvatarUrl(user.avatar.large, user.avatar.medium, user.avatar.small),
            ))
        }
        memo.daily?.groups.orEmpty().forEach { group ->
            add(TimelineTargetBo(
                group.title.unescapeHtmlEntities(),
                BgmWeb.group(group.name),
                segments = listOf(TimelineSegmentBo.GroupMembers(group.members)),
                imageUrl = group.icon.groupIconUrl(),
            ))
        }
        memo.wiki?.subject?.let { add(it.target()) }
        memo.subject.orEmpty().forEach { collection ->
            add(collection.subject.target().copy(description = collection.comment.unescapeHtmlEntities(),
                rating = collection.rate?.takeIf { it > 0 }))
        }
        memo.progress?.batch?.let { progress ->
            add(progress.subject.target().copy(segments = listOf(
                TimelineSegmentBo.ProgressBatch(progress.epsTotal, progress.epsUpdate, progress.volsTotal, progress.volsUpdate),
            )))
        }
        memo.progress?.single?.let { progress ->
            val episode = progress.episode
            val name = displayNameOf(name = episode.name, nameCn = episode.nameCN).unescapeHtmlEntities()
            add(progress.subject.target().copy(segments = listOf(TimelineSegmentBo.EpisodeProgress(episode.sort, name))))
            add(TimelineTargetBo(name, BgmWeb.episode(episode.id)))
        }
        memo.blog?.let { blog -> add(TimelineTargetBo(blog.title.unescapeHtmlEntities(), BgmWeb.blog(blog.id),
            description = blog.summary.unescapeHtmlEntities())) }
        memo.index?.let { index -> add(TimelineTargetBo(index.title.unescapeHtmlEntities(), BgmWeb.index(index.id),
            segments = listOf(TimelineSegmentBo.IndexTotal(index.total)))) }
        memo.mono?.characters.orEmpty().forEach { character ->
            add(TimelineTargetBo(
                displayNameOf(name = character.name, nameCn = character.nameCN).unescapeHtmlEntities(),
                BgmWeb.mono(MonoType.CHARACTER, character.id),
                description = character.info.unescapeHtmlEntities(),
                imageUrl = character.images?.toRelatedImagesBo()?.avatarUrl(),
            ))
        }
        memo.mono?.persons.orEmpty().forEach { person ->
            add(TimelineTargetBo(
                displayNameOf(name = person.name, nameCn = person.nameCN).unescapeHtmlEntities(),
                BgmWeb.mono(MonoType.PERSON, person.id),
                description = person.info.unescapeHtmlEntities(),
                imageUrl = person.images?.toRelatedImagesBo()?.avatarUrl(),
            ))
        }
    }
    // 正文只承载**用户内容**（签名 / 吐槽 / 昵称）；界面兜底文案由 core:ui 依 cat 决定。
    val text = if (timelineCategoryOf(cat) == TimelineCategory.STATUS) {
        buildList {
            memo.status?.sign?.let { add(it) }
            memo.status?.tsukkomi?.let { add(it) }
            memo.status?.nickname?.let { add("${it.before} → ${it.after}") }
        }.joinToString("\n").unescapeHtmlEntities()
    } else {
        ""
    }
    return TimelineItemBo(
        id = id, uid = uid, user = user?.toUserBo() ?: fallbackUser?.takeIf { it.id == uid },
        cat = cat, type = type, createdAt = createdAt.epochSecondsToMillis(), text = text,
        targets = targets, reactions = reactions.toReactions(currentUserId), replies = replies,
        reactionTarget = reactionTarget(), batch = batch,
        isOwn = currentUserId != null && uid == currentUserId,
    )
}

/**
 * 贴贴挂在哪的唯一判定：说说贴在动态本身；写了吐槽的单条收藏贴在那条收藏上（服务端只给这种动态回填那条收藏的贴贴，
 * 没写吐槽的贴了也回不到动态上）；批量收藏没有单独的一条可贴，其余类别没有贴贴接口。
 */
private fun TimelineDto.reactionTarget(): TimelineReactionTarget? = when {
    timelineCategoryOf(cat) == TimelineCategory.STATUS -> TimelineReactionTarget.Status
    batch -> null
    else -> memo.subject?.singleOrNull()?.takeIf { it.comment.isNotEmpty() }?.let { collection ->
        collection.collectID?.let { TimelineReactionTarget.Collect(subjectId = collection.subject.id, collectId = it) }
    }
}

private fun List<ReactionDto>?.toReactions(currentUserId: Int?) = orEmpty().map { it.toReactionBo(currentUserId) }

private fun P1SlimSubjectDto.target(): TimelineTargetBo {
    val subject = toSubjectBo()
    return TimelineTargetBo(
        title = displayNameOf(subject).unescapeHtmlEntities(),
        url = BgmWeb.subject(id),
        subject = subject,
        imageUrl = subject.imageCommon,
    )
}

