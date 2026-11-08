package io.github.touhougo.chogumi.feature.community

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.CommunityTopicSource
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.RakuenFilter
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.PostLeading
import io.github.touhougo.chogumi.core.ui.composables.PostListItem
import io.github.touhougo.chogumi.core.ui.composables.PostListItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.episodeNumberLabel
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText

/**
 * 社区帖子流的一行（社区的小组页与超展开、一个小组的小组页、我的帖子共用）：帖子行 [PostListItem]。
 * 有发帖人时左边是发帖人头像，发帖人那一行的最右边是来源（小组名 / 作品名），下面标题；
 * 没有发帖人时（单集讨论、角色人物吐槽）左边是这帖挂着的东西（单集是作品封面，角色、人物是头像），标题顶格，
 * 来源在标题下面。最后一行时间与回复数。没有来源（小组页里这个小组的帖子）时不画来源。
 *
 * @param kind 这一行所在的列表（[TopicListKind]）：角色、人物的来源「角色」「人物」画不画由它定。
 * @param onLongClick 只有超展开的小组帖给（屏蔽这个小组）。
 */
@Composable
internal fun CommunityTopicRow(
    topic: CommunityTopicBo,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TopicListKind = TopicListKind.POSTS,
    onLongClick: (() -> Unit)? = null,
) {
    val episode = topic.episode
    val author = topic.author
    val source = sourceParts(topic.source, kind.showMonoKind)
    PostListItem(
        title = if (episode == null) topic.title else listOf(episodeNumberLabel(episode), topic.title).filter { it.isNotBlank() }.joinToString(" "),
        replies = topic.replies,
        repliesDescription = pluralStringResource(R.plurals.topic_reply_count, topic.replies, topic.replies),
        index = index,
        count = count,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        leading = if (author != null) PostLeading.Avatar(author.avatarUrl) else source.image,
        author = author?.let(::displayNameOf),
        source = source.label,
        time = relativeTimeText(topic.updatedAt),
    )
}

/** 首屏骨架的一行：与 [CommunityTopicRow] 同一个 [PostListItem] 框架，左列、发帖人与来源照 [kind] 里会出现的行。 */
@Composable
internal fun CommunityTopicRowSkeleton(index: Int, count: Int, modifier: Modifier = Modifier, kind: TopicListKind = TopicListKind.POSTS) {
    PostListItemSkeleton(index, count, modifier, leading = kind.leading, withSource = kind.withSource, withAuthor = kind.withAuthor)
}

/**
 * 帖子流的几种列表：骨架照它画左列、发帖人与来源，角色人物的「角色」「人物」画不画也看它。
 *
 * @property showMonoKind 列表本身就是「角色」或「人物」分类时那两个字重复，不画。
 */
internal enum class TopicListKind(val leading: PostLeading, val withAuthor: Boolean, val withSource: Boolean, val showMonoKind: Boolean) {
    /** 小组新帖、作品讨论，或几种帖子混排（超展开「全部」）：头像、发帖人、来源。 */
    POSTS(PostLeading.Avatar(null), withAuthor = true, withSource = true, showMonoKind = true),

    /** 一个小组里的帖子：列表就在这个小组里，不画来源。 */
    IN_GROUP(PostLeading.Avatar(null), withAuthor = true, withSource = false, showMonoKind = true),

    /** 单集讨论：作品封面，没有发帖人，标题下面是作品名。 */
    EPISODES(PostLeading.Cover(null), withAuthor = false, withSource = true, showMonoKind = true),

    /** 角色或人物的吐槽：头像，没有发帖人，也不画来源。 */
    MONOS(PostLeading.Avatar(null), withAuthor = false, withSource = false, showMonoKind = false),
}

/** 超展开一个分类是哪种列表（按分类分支只在这里）。 */
internal fun topicListKindOf(filter: RakuenFilter): TopicListKind = when (filter) {
    RakuenFilter.ALL, RakuenFilter.GROUP, RakuenFilter.SUBJECT -> TopicListKind.POSTS
    RakuenFilter.EPISODE -> TopicListKind.EPISODES
    RakuenFilter.CHARACTER, RakuenFilter.PERSON -> TopicListKind.MONOS
}

/** 一帖的来源在帖子行里的两样东西：来源文字，没有发帖人时左列画的图。 */
private class SourceParts(val label: String?, val image: PostLeading)

/** 来源与左列的图都由一帖挂在哪里决定，只在这里分支。 */
@Composable
private fun sourceParts(source: CommunityTopicSource?, showMonoKind: Boolean): SourceParts = when (source) {
    null -> SourceParts(label = null, image = PostLeading.Avatar(null))
    is CommunityTopicSource.Group -> SourceParts(label = source.group.title, image = PostLeading.Avatar(null))
    is CommunityTopicSource.Subject -> SourceParts(label = displayNameOf(source.subject), image = PostLeading.Cover(source.subject.cover))
    is CommunityTopicSource.Mono -> SourceParts(
        label = if (showMonoKind) {
            stringResource(if (source.type == MonoType.CHARACTER) R.string.rakuen_filter_character else R.string.rakuen_filter_person)
        } else {
            null
        },
        image = PostLeading.Avatar(source.imageUrl),
    )
}
