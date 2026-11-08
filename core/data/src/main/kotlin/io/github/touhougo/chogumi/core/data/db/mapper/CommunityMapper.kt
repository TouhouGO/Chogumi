package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.CommunityTopicSource
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.network.model.p1.GroupTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonImagesDto
import io.github.touhougo.chogumi.core.network.model.p1.RaKuenCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.RaKuenEpisodeDto
import io.github.touhougo.chogumi.core.network.model.p1.RaKuenGroupTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.RaKuenPersonDto
import io.github.touhougo.chogumi.core.network.model.p1.RaKuenSubjectTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.RaKuenTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.RaKuenUnknownTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.TopicDto

// 社区帖子流（超展开、小组新帖）→ [CommunityTopicBo]。帖子是哪一种只在这里判一次。p1 时间一律 Unix 秒。

/** 认不出种类的一条（[RaKuenUnknownTopicDto]）为 null：列表跳过它，其余照常。 */
fun RaKuenTopicDto.toCommunityTopicBo(): CommunityTopicBo? = when (this) {
    is RaKuenGroupTopicDto -> groupTopic(id, title, group.toGroupSummaryBo(), creator.toUserBo(), replyCount, updatedAt)
    is RaKuenSubjectTopicDto -> CommunityTopicBo(
        key = "subject:$id",
        webUrl = BgmWeb.topic(TopicType.SUBJECT, id),
        title = title.unescapeHtmlEntities(),
        episode = null,
        source = CommunityTopicSource.Subject(subject.toTopicSubjectBo()),
        author = creator.toUserBo(),
        replies = replyCount,
        updatedAt = updatedAt.epochSecondsToMillis(),
    )
    is RaKuenEpisodeDto -> {
        val ep = EpisodeBo(
            id = episode.id,
            subjectId = subject.id,
            type = episode.type,
            name = episode.name.unescapeHtmlEntities(),
            nameCn = episode.nameCN.unescapeHtmlEntities(),
            sort = episode.sort,
            comment = episode.comment,
        )
        CommunityTopicBo(
            key = "episode:$id",
            webUrl = BgmWeb.episode(id),
            title = displayNameOf(ep),
            episode = ep,
            source = CommunityTopicSource.Subject(subject.toTopicSubjectBo()),
            author = null,
            replies = ep.comment,
            updatedAt = updatedAt.epochSecondsToMillis(),
        )
    }
    is RaKuenCharacterDto -> monoTopic(MonoType.CHARACTER, id, name, nameCN, images, comment, updatedAt)
    is RaKuenPersonDto -> monoTopic(MonoType.PERSON, id, name, nameCN, images, comment, updatedAt)
    RaKuenUnknownTopicDto -> null
}

/** 小组新帖（`/p1/groups/-/topics`）与超展开里的小组帖是同一种帖子。 */
fun GroupTopicDto.toCommunityTopicBo(): CommunityTopicBo =
    groupTopic(id, title, group.toGroupSummaryBo(), creator?.toUserBo(), replyCount, updatedAt)

/** 小组页之外列出的、[group] 这个小组的帖子（小组新帖里合进来的小组）：来源是 [group]。 */
fun TopicDto.toCommunityTopicBo(group: GroupSummaryBo): CommunityTopicBo =
    groupTopic(id, title, group, creator?.toUserBo(), replyCount, updatedAt)

/** 小组页里这个小组的帖子：列表就在这个小组里，不带来源。 */
fun TopicDto.toGroupPageTopicBo(): CommunityTopicBo =
    groupTopic(id, title, group = null, creator?.toUserBo(), replyCount, updatedAt)

private fun groupTopic(id: Int, title: String, group: GroupSummaryBo?, author: UserBo?, replies: Int, updatedAt: Long) =
    CommunityTopicBo(
        key = "group:$id",
        webUrl = BgmWeb.topic(TopicType.GROUP, id),
        title = title.unescapeHtmlEntities(),
        episode = null,
        source = group?.let(CommunityTopicSource::Group),
        author = author,
        replies = replies,
        updatedAt = updatedAt.epochSecondsToMillis(),
    )

private fun monoTopic(type: MonoType, id: Int, name: String, nameCn: String, images: PersonImagesDto?, comments: Int, updatedAt: Long) =
    CommunityTopicBo(
        key = "${type.name.lowercase()}:$id",
        webUrl = BgmWeb.mono(type, id),
        title = displayNameOf(name = name.unescapeHtmlEntities(), nameCn = nameCn.unescapeHtmlEntities()),
        episode = null,
        source = CommunityTopicSource.Mono(type, images?.toRelatedImagesBo()?.avatarUrl()),
        author = null,
        replies = comments,
        updatedAt = updatedAt.epochSecondsToMillis(),
    )
