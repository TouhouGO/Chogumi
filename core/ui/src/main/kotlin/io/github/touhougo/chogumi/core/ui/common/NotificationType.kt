package io.github.touhougo.chogumi.core.ui.common

import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.model.NoticeKind
import io.github.touhougo.chogumi.core.res.R

/** 一种通知的动作文案（「在小组话题中回复了你」）；类型怎么解成 [NoticeKind] 只在 core:model 的 `noticeKindOf`。 */
@StringRes
fun NoticeKind.labelRes(): Int = when (this) {
    NoticeKind.UNKNOWN -> R.string.notify_type_unknown
    NoticeKind.GROUP_TOPIC_REPLY -> R.string.notify_type_group_topic_reply
    NoticeKind.GROUP_POST_REPLY -> R.string.notify_type_group_post_reply
    NoticeKind.SUBJECT_TOPIC_REPLY -> R.string.notify_type_subject_topic_reply
    NoticeKind.SUBJECT_POST_REPLY -> R.string.notify_type_subject_post_reply
    NoticeKind.CHARACTER_TOPIC_REPLY -> R.string.notify_type_character_topic_reply
    NoticeKind.CHARACTER_POST_REPLY -> R.string.notify_type_character_post_reply
    NoticeKind.BLOG_TOPIC_REPLY -> R.string.notify_type_blog_topic_reply
    NoticeKind.BLOG_POST_REPLY -> R.string.notify_type_blog_post_reply
    NoticeKind.EPISODE_TOPIC_REPLY -> R.string.notify_type_episode_topic_reply
    NoticeKind.EPISODE_POST_REPLY -> R.string.notify_type_episode_post_reply
    NoticeKind.INDEX_COMMENT -> R.string.notify_type_index_comment
    NoticeKind.INDEX_REPLY -> R.string.notify_type_index_reply
    NoticeKind.PERSON_REPLY -> R.string.notify_type_person_reply
    NoticeKind.REQUEST_FRIEND -> R.string.notify_type_request_friend
    NoticeKind.ACCEPT_FRIEND -> R.string.notify_type_accept_friend
    NoticeKind.DOUJIN_CLUB_TOPIC_REPLY -> R.string.notify_type_doujin_club_topic_reply
    NoticeKind.DOUJIN_CLUB_POST_REPLY -> R.string.notify_type_doujin_club_post_reply
    NoticeKind.DOUJIN_SUBJECT_REPLY -> R.string.notify_type_doujin_subject_reply
    NoticeKind.DOUJIN_EVENT_TOPIC_REPLY -> R.string.notify_type_doujin_event_topic_reply
    NoticeKind.DOUJIN_EVENT_POST_REPLY -> R.string.notify_type_doujin_event_post_reply
    NoticeKind.TIMELINE_REPLY -> R.string.notify_type_timeline_reply
    NoticeKind.GROUP_MENTION -> R.string.notify_type_group_mention
    NoticeKind.SUBJECT_MENTION -> R.string.notify_type_subject_mention
    NoticeKind.CHARACTER_MENTION -> R.string.notify_type_character_mention
    NoticeKind.PERSON_MENTION -> R.string.notify_type_person_mention
    NoticeKind.INDEX_MENTION -> R.string.notify_type_index_mention
    NoticeKind.TIMELINE_MENTION -> R.string.notify_type_timeline_mention
    NoticeKind.BLOG_MENTION -> R.string.notify_type_blog_mention
    NoticeKind.EPISODE_MENTION -> R.string.notify_type_episode_mention
    NoticeKind.DOUJIN_CLUB_SHOUTBOX_MENTION -> R.string.notify_type_doujin_club_shoutbox_mention
    NoticeKind.DOUJIN_CLUB_TOPIC_MENTION -> R.string.notify_type_doujin_club_topic_mention
    NoticeKind.DOUJIN_SUBJECT_MENTION -> R.string.notify_type_doujin_subject_mention
    NoticeKind.DOUJIN_EVENT_TOPIC_MENTION -> R.string.notify_type_doujin_event_topic_mention
    NoticeKind.SUBJECT_PATCH_ACCEPTED -> R.string.notify_type_subject_patch_accepted
    NoticeKind.EPISODE_PATCH_ACCEPTED -> R.string.notify_type_episode_patch_accepted
    NoticeKind.SUBJECT_PATCH_REJECTED -> R.string.notify_type_subject_patch_rejected
    NoticeKind.EPISODE_PATCH_REJECTED -> R.string.notify_type_episode_patch_rejected
    NoticeKind.SUBJECT_PATCH_EXPIRED -> R.string.notify_type_subject_patch_expired
    NoticeKind.EPISODE_PATCH_EXPIRED -> R.string.notify_type_episode_patch_expired
    NoticeKind.CHARACTER_PATCH_ACCEPTED -> R.string.notify_type_character_patch_accepted
    NoticeKind.PERSON_PATCH_ACCEPTED -> R.string.notify_type_person_patch_accepted
    NoticeKind.CHARACTER_PATCH_REJECTED -> R.string.notify_type_character_patch_rejected
    NoticeKind.PERSON_PATCH_REJECTED -> R.string.notify_type_person_patch_rejected
    NoticeKind.CHARACTER_PATCH_EXPIRED -> R.string.notify_type_character_patch_expired
    NoticeKind.PERSON_PATCH_EXPIRED -> R.string.notify_type_person_patch_expired
    NoticeKind.SUBJECT_PATCH_REPLY -> R.string.notify_type_subject_patch_reply
    NoticeKind.EPISODE_PATCH_REPLY -> R.string.notify_type_episode_patch_reply
    NoticeKind.CHARACTER_PATCH_REPLY -> R.string.notify_type_character_patch_reply
    NoticeKind.PERSON_PATCH_REPLY -> R.string.notify_type_person_patch_reply
}
