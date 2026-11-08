package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.BlogDetailsBo
import io.github.touhougo.chogumi.core.model.castRoleOf
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.IndexDetailsBo
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexEntryBo
import io.github.touhougo.chogumi.core.model.SlimBlogBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.parseSubjectYearMonth
import io.github.touhougo.chogumi.core.network.model.p1.BlogEntryDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexRelatedDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexSubjectStatsDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimBlogEntryDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimIndexDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimPersonDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimUserDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectCharacterDto

fun io.github.touhougo.chogumi.core.network.model.v0.RelatedPersonDto.toDomain() = RelatedPersonBo(
    id = id,
    name = name,
    type = type,
    images = images?.toRelatedImagesBo(),
    relation = relation,
)

// ---- p1 的收藏分组（角色/人物/目录）----
// 与 v0 版本分开：p1 的 Slim 系没有 v0 的 relation/summary，字段集更小，
// 强行复用 v0 的映射会凭空造出空字段。

fun SlimCharacterDto.toBo() = RelatedCharacterBo(
    id = id,
    name = displayNameOf(name = name, nameCn = nameCN),
    summary = info,
    type = role,
    role = null,
    images = images?.toRelatedImagesBo(),
)

/** 条目的一个角色：角色本身同收藏分组的映射，另带在这部作品里的定位与声优（声优名同样中文名优先）。 */
fun SubjectCharacterDto.toBo() = character.toBo().copy(
    role = castRoleOf(type),
    actors = casts.map { it.person.toBo() },
)

fun SlimPersonDto.toBo() = RelatedPersonBo(
    id = id,
    name = displayNameOf(name = name, nameCn = nameCN),
    type = type,
    images = images?.toRelatedImagesBo(),
)

fun SlimIndexDto.toBo() = IndexBo(
    id = id,
    title = title,
    total = total,
    creator = user?.toUserBo(),
    createdAt = createdAt.epochSecondsToMillis(),
    isPrivate = `private`,
)

/** 好友/关注列表项 → 领域模型；字段集比完整资料小，缺失项保持默认值。 */
fun SlimUserDto.toBo() = UserBo(
    id = id,
    username = username,
    nickname = nickname.unescapeHtmlEntities(),
    avatarLarge = avatar.large,
    avatarMedium = avatar.medium,
    avatarSmall = avatar.small,
    sign = sign.unescapeHtmlEntities(),
)

// ---- 日志 / 目录只读页----

fun SlimBlogEntryDto.toBo() = SlimBlogBo(
    id = id,
    title = title.unescapeHtmlEntities(),
    summary = summary.unescapeHtmlEntities(),
    replies = replies,
    createdAt = createdAt.epochSecondsToMillis(),
    author = user?.toUserBo(),
)

fun BlogEntryDto.toBo(currentUserId: Int?) = BlogDetailsBo(
    id = id,
    title = title.unescapeHtmlEntities(),
    content = content,
    replies = replies,
    createdAt = createdAt.epochSecondsToMillis(),
    author = user.toUserBo(),
    isOwn = currentUserId != null && uid == currentUserId,
)

/** [currentUserId] 是取数时的登录账号（游客为 null）：「是不是我的目录」只在这里判定。 */
fun IndexDto.toDetailsBo(currentUserId: Int?) = IndexDetailsBo(
    id = id,
    title = title.unescapeHtmlEntities(),
    description = desc,
    total = total,
    collects = collects,
    replies = replies,
    typeCounts = stats.subject.typeCounts(),
    categoryCounts = listOf(
        IndexCategory.SUBJECT to stats.subject.typeCounts().sumOf { it.second },
        IndexCategory.CHARACTER to (stats.character ?: 0),
        IndexCategory.PERSON to (stats.person ?: 0),
        IndexCategory.EPISODE to (stats.episode ?: 0),
        IndexCategory.BLOG to (stats.blog ?: 0),
        IndexCategory.GROUP_TOPIC to (stats.groupTopic ?: 0),
        IndexCategory.SUBJECT_TOPIC to (stats.subjectTopic ?: 0),
    ).filter { it.second > 0 },
    createdAt = createdAt.epochSecondsToMillis(),
    updatedAt = updatedAt.epochSecondsToMillis(),
    creator = user?.toUserBo(),
    isCollected = collectedAt != null,
    isPrivate = `private`,
    isOwn = currentUserId != null && uid == currentUserId,
)

/** 各条目类型的收录数：只留非零项，按 [SubjectType] 声明顺序（头部类型 chip 的顺序）。 */
private fun IndexSubjectStatsDto.typeCounts(): List<Pair<SubjectType, Int>> = listOf(
    SubjectType.BOOK to book,
    SubjectType.ANIME to anime,
    SubjectType.MUSIC to music,
    SubjectType.GAME to game,
    SubjectType.REAL to real,
).mapNotNull { (type, count) -> count?.takeIf { it > 0 }?.let { type to it } }

/**
 * 目录收录行 → [IndexEntryBo]。按行自己的 `cat` 取对应的嵌套对象（[IndexCategory]；2026-10-04 实测条目行 cat = 0）；
 * 不认识的类别或缺嵌套对象的行返回 null，不会伪造成别的类别渲染。
 */
fun IndexRelatedDto.toEntryBo(): IndexEntryBo? = when (IndexCategory.fromCode(cat)) {
    IndexCategory.SUBJECT -> subject?.let { s ->
        IndexEntryBo(
            id = id,
            targetId = s.id,
            category = IndexCategory.SUBJECT,
            name = s.name,
            nameCn = s.nameCN,
            cover = s.images?.toRelatedImagesBo()?.posterUrl(),
            subjectType = SubjectType.from(s.type) ?: SubjectType.UNKNOWN,
            info = s.info,
            comment = comment,
            order = order,
            ratingScore = s.rating.score,
            ratingTotal = s.rating.total,
            date = parseSubjectYearMonth(s.info),
        )
    }
    IndexCategory.CHARACTER -> character?.let { c ->
        IndexEntryBo(
            id = id,
            targetId = c.id,
            category = IndexCategory.CHARACTER,
            name = c.name,
            nameCn = c.nameCN,
            cover = c.images?.toRelatedImagesBo()?.avatarUrl(),
            info = c.info,
            comment = comment,
            order = order,
        )
    }
    IndexCategory.PERSON -> person?.let { p ->
        IndexEntryBo(
            id = id,
            targetId = p.id,
            category = IndexCategory.PERSON,
            name = p.name,
            nameCn = p.nameCN,
            cover = p.images?.toRelatedImagesBo()?.avatarUrl(),
            info = p.info,
            comment = comment,
            order = order,
        )
    }
    IndexCategory.EPISODE -> episode?.let { e ->
        IndexEntryBo(
            id = id,
            targetId = e.id,
            category = IndexCategory.EPISODE,
            name = e.name,
            nameCn = e.nameCN,
            parentName = e.subject?.let { displayNameOf(it.name, it.nameCN) }.orEmpty(),
            episodeSort = e.sort,
            comment = comment,
            order = order,
        )
    }
    IndexCategory.BLOG -> blog?.let { b ->
        IndexEntryBo(
            id = id,
            targetId = b.id,
            category = IndexCategory.BLOG,
            name = b.title.unescapeHtmlEntities(),
            parentName = b.user?.toUserBo()?.let(::displayNameOf).orEmpty(),
            comment = comment,
            order = order,
        )
    }
    IndexCategory.GROUP_TOPIC -> groupTopic?.let { t ->
        IndexEntryBo(
            id = id,
            targetId = t.id,
            category = IndexCategory.GROUP_TOPIC,
            name = t.title.unescapeHtmlEntities(),
            parentName = t.group.title.unescapeHtmlEntities(),
            comment = comment,
            order = order,
        )
    }
    IndexCategory.SUBJECT_TOPIC -> subjectTopic?.let { t ->
        IndexEntryBo(
            id = id,
            targetId = t.id,
            category = IndexCategory.SUBJECT_TOPIC,
            name = t.title.unescapeHtmlEntities(),
            parentName = displayNameOf(t.subject.name, t.subject.nameCN),
            comment = comment,
            order = order,
        )
    }
    null -> null
}
