package io.github.touhougo.chogumi.core.model

/**
 * 通知发送者。
 */
data class NoticeSenderBo(
    val id: Int,
    val username: String,
    val nickname: String,
    val avatar: String = "",
)

/**
 * 社区通知领域模型。
 *
 * 对应 NextAPI `/p1/notify` 中的 Notice 契约。
 */
data class NoticeBo(
    val id: Int,
    val type: Int,
    val sender: NoticeSenderBo,
    val title: String,
    val mainId: Int,
    val relatedId: Int,
    /** Unix 时间戳，毫秒 */
    val createdAt: Long,
    val unread: Boolean,
)

/** 通知所在的地方：点开去哪一页（[noticeLinkOf]）只由它决定。 */
enum class NoticePlace {
    GROUP_TOPIC,
    SUBJECT_TOPIC,
    BLOG,
    CHARACTER,
    PERSON,
    EPISODE,
    INDEX,

    /** 好友请求、通过：去发送者的主页。 */
    SENDER,

    /** 动态、同人站、条目修改与未知类型：App 里没有对应的一页，也拼不出确定的网页地址，点了不跳。 */
    NONE,
}

/** 一种通知（[NoticeBo.type] 解出来的意思）：文案由 core:ui 按它取，去处由它的 [place] 定。 */
enum class NoticeKind(val place: NoticePlace) {
    UNKNOWN(NoticePlace.NONE),
    GROUP_TOPIC_REPLY(NoticePlace.GROUP_TOPIC),
    GROUP_POST_REPLY(NoticePlace.GROUP_TOPIC),
    SUBJECT_TOPIC_REPLY(NoticePlace.SUBJECT_TOPIC),
    SUBJECT_POST_REPLY(NoticePlace.SUBJECT_TOPIC),
    CHARACTER_TOPIC_REPLY(NoticePlace.CHARACTER),
    CHARACTER_POST_REPLY(NoticePlace.CHARACTER),
    BLOG_TOPIC_REPLY(NoticePlace.BLOG),
    BLOG_POST_REPLY(NoticePlace.BLOG),
    EPISODE_TOPIC_REPLY(NoticePlace.EPISODE),
    EPISODE_POST_REPLY(NoticePlace.EPISODE),
    INDEX_COMMENT(NoticePlace.INDEX),
    INDEX_REPLY(NoticePlace.INDEX),
    PERSON_REPLY(NoticePlace.PERSON),
    REQUEST_FRIEND(NoticePlace.SENDER),
    ACCEPT_FRIEND(NoticePlace.SENDER),
    DOUJIN_CLUB_TOPIC_REPLY(NoticePlace.NONE),
    DOUJIN_CLUB_POST_REPLY(NoticePlace.NONE),
    DOUJIN_SUBJECT_REPLY(NoticePlace.NONE),
    DOUJIN_EVENT_TOPIC_REPLY(NoticePlace.NONE),
    DOUJIN_EVENT_POST_REPLY(NoticePlace.NONE),
    TIMELINE_REPLY(NoticePlace.NONE),
    GROUP_MENTION(NoticePlace.GROUP_TOPIC),
    SUBJECT_MENTION(NoticePlace.SUBJECT_TOPIC),
    CHARACTER_MENTION(NoticePlace.CHARACTER),
    PERSON_MENTION(NoticePlace.PERSON),
    INDEX_MENTION(NoticePlace.INDEX),
    TIMELINE_MENTION(NoticePlace.NONE),
    BLOG_MENTION(NoticePlace.BLOG),
    EPISODE_MENTION(NoticePlace.EPISODE),
    DOUJIN_CLUB_SHOUTBOX_MENTION(NoticePlace.NONE),
    DOUJIN_CLUB_TOPIC_MENTION(NoticePlace.NONE),
    DOUJIN_SUBJECT_MENTION(NoticePlace.NONE),
    DOUJIN_EVENT_TOPIC_MENTION(NoticePlace.NONE),
    SUBJECT_PATCH_ACCEPTED(NoticePlace.NONE),
    EPISODE_PATCH_ACCEPTED(NoticePlace.NONE),
    SUBJECT_PATCH_REJECTED(NoticePlace.NONE),
    EPISODE_PATCH_REJECTED(NoticePlace.NONE),
    SUBJECT_PATCH_EXPIRED(NoticePlace.NONE),
    EPISODE_PATCH_EXPIRED(NoticePlace.NONE),
    CHARACTER_PATCH_ACCEPTED(NoticePlace.NONE),
    PERSON_PATCH_ACCEPTED(NoticePlace.NONE),
    CHARACTER_PATCH_REJECTED(NoticePlace.NONE),
    PERSON_PATCH_REJECTED(NoticePlace.NONE),
    CHARACTER_PATCH_EXPIRED(NoticePlace.NONE),
    PERSON_PATCH_EXPIRED(NoticePlace.NONE),
    SUBJECT_PATCH_REPLY(NoticePlace.NONE),
    EPISODE_PATCH_REPLY(NoticePlace.NONE),
    CHARACTER_PATCH_REPLY(NoticePlace.NONE),
    PERSON_PATCH_REPLY(NoticePlace.NONE),
}

/**
 * 通知类型 → [NoticeKind]（全仓唯一的 `type` 判定）。依据服务端 `lib/notify.ts` 的 `_settings`：每种类型的
 * 网页地址（`url` + mainID + `#post_` + relatedID）就是它的意思，网站通知页按这张表出链接。
 * - 3、4 的枚举名是 IndexTopicReply / IndexPostReply，地址却是 `/subject/topic/`：条目讨论；
 * - 7、8 的地址是 `/blog/`：日志（新服务端的枚举名叫 SubjectTopicReply，与表里的地址对不上，以地址为准）；
 * - 16 没有设置，与 0、越界一样是未知。
 */
fun noticeKindOf(type: Int): NoticeKind = when (type) {
    1 -> NoticeKind.GROUP_TOPIC_REPLY
    2 -> NoticeKind.GROUP_POST_REPLY
    3 -> NoticeKind.SUBJECT_TOPIC_REPLY
    4 -> NoticeKind.SUBJECT_POST_REPLY
    5 -> NoticeKind.CHARACTER_TOPIC_REPLY
    6 -> NoticeKind.CHARACTER_POST_REPLY
    7 -> NoticeKind.BLOG_TOPIC_REPLY
    8 -> NoticeKind.BLOG_POST_REPLY
    9 -> NoticeKind.EPISODE_TOPIC_REPLY
    10 -> NoticeKind.EPISODE_POST_REPLY
    11 -> NoticeKind.INDEX_COMMENT
    12 -> NoticeKind.INDEX_REPLY
    13 -> NoticeKind.PERSON_REPLY
    14 -> NoticeKind.REQUEST_FRIEND
    15 -> NoticeKind.ACCEPT_FRIEND
    17 -> NoticeKind.DOUJIN_CLUB_TOPIC_REPLY
    18 -> NoticeKind.DOUJIN_CLUB_POST_REPLY
    19 -> NoticeKind.DOUJIN_SUBJECT_REPLY
    20 -> NoticeKind.DOUJIN_EVENT_TOPIC_REPLY
    21 -> NoticeKind.DOUJIN_EVENT_POST_REPLY
    22 -> NoticeKind.TIMELINE_REPLY
    23 -> NoticeKind.GROUP_MENTION
    24 -> NoticeKind.SUBJECT_MENTION
    25 -> NoticeKind.CHARACTER_MENTION
    26 -> NoticeKind.PERSON_MENTION
    27 -> NoticeKind.INDEX_MENTION
    28 -> NoticeKind.TIMELINE_MENTION
    29 -> NoticeKind.BLOG_MENTION
    30 -> NoticeKind.EPISODE_MENTION
    31 -> NoticeKind.DOUJIN_CLUB_SHOUTBOX_MENTION
    32 -> NoticeKind.DOUJIN_CLUB_TOPIC_MENTION
    33 -> NoticeKind.DOUJIN_SUBJECT_MENTION
    34 -> NoticeKind.DOUJIN_EVENT_TOPIC_MENTION
    35 -> NoticeKind.SUBJECT_PATCH_ACCEPTED
    36 -> NoticeKind.EPISODE_PATCH_ACCEPTED
    37 -> NoticeKind.SUBJECT_PATCH_REJECTED
    38 -> NoticeKind.EPISODE_PATCH_REJECTED
    39 -> NoticeKind.SUBJECT_PATCH_EXPIRED
    40 -> NoticeKind.EPISODE_PATCH_EXPIRED
    41 -> NoticeKind.CHARACTER_PATCH_ACCEPTED
    42 -> NoticeKind.PERSON_PATCH_ACCEPTED
    43 -> NoticeKind.CHARACTER_PATCH_REJECTED
    44 -> NoticeKind.PERSON_PATCH_REJECTED
    45 -> NoticeKind.CHARACTER_PATCH_EXPIRED
    46 -> NoticeKind.PERSON_PATCH_EXPIRED
    47 -> NoticeKind.SUBJECT_PATCH_REPLY
    48 -> NoticeKind.EPISODE_PATCH_REPLY
    49 -> NoticeKind.CHARACTER_PATCH_REPLY
    50 -> NoticeKind.PERSON_PATCH_REPLY
    else -> NoticeKind.UNKNOWN
}

/**
 * 点开一条通知去的网页地址，带楼层锚点（[BgmWeb.floor]）；App 里打开哪一页、落到哪层楼由 core:ui 的 `inAppRoute` 从它解出，
 * 与正文里的站内链接同一条路。null 表示这条通知没有去处（[NoticePlace.NONE]）。
 * [NoticeBo.mainId] 是话题 / 日志 / 章节 / 角色 / 人物 / 目录的 id，[NoticeBo.relatedId] 是楼的 id。
 */
fun noticeLinkOf(notice: NoticeBo): String? {
    val main = notice.mainId
    val page = when (noticeKindOf(notice.type).place) {
        NoticePlace.GROUP_TOPIC -> BgmWeb.topic(TopicType.GROUP, main)
        NoticePlace.SUBJECT_TOPIC -> BgmWeb.topic(TopicType.SUBJECT, main)
        NoticePlace.BLOG -> BgmWeb.blog(main)
        NoticePlace.CHARACTER -> BgmWeb.mono(MonoType.CHARACTER, main)
        NoticePlace.PERSON -> BgmWeb.mono(MonoType.PERSON, main)
        NoticePlace.EPISODE -> BgmWeb.episode(main)
        NoticePlace.INDEX -> BgmWeb.index(main)
        NoticePlace.SENDER -> return notice.sender
            .takeIf { it.id > 0 || it.username.isNotBlank() }
            ?.let { BgmWeb.user(userKeyOf(it.username, it.id)) }
        NoticePlace.NONE -> return null
    }
    return if (main > 0) BgmWeb.floor(page, notice.relatedId) else null
}
