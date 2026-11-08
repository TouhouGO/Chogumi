package io.github.touhougo.chogumi.core.ui.common

import io.github.touhougo.chogumi.core.model.noticeKindOf
import io.github.touhougo.chogumi.core.res.R
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTypeTest {

    @Test
    fun `0映射为未知通知`() {
        assertEquals(R.string.notify_type_unknown, noticeKindOf(0).labelRes())
    }

    @Test
    fun `负数及越界类型映射为未知通知`() {
        assertEquals(R.string.notify_type_unknown, noticeKindOf(-1).labelRes())
        assertEquals(R.string.notify_type_unknown, noticeKindOf(51).labelRes())
        assertEquals(R.string.notify_type_unknown, noticeKindOf(100).labelRes())
    }

    @Test
    fun `全表1到50映射验证`() {
        val expected = mapOf(
            1 to R.string.notify_type_group_topic_reply,
            2 to R.string.notify_type_group_post_reply,
            3 to R.string.notify_type_subject_topic_reply,
            4 to R.string.notify_type_subject_post_reply,
            5 to R.string.notify_type_character_topic_reply,
            6 to R.string.notify_type_character_post_reply,
            7 to R.string.notify_type_blog_topic_reply,
            8 to R.string.notify_type_blog_post_reply,
            9 to R.string.notify_type_episode_topic_reply,
            10 to R.string.notify_type_episode_post_reply,
            11 to R.string.notify_type_index_comment,
            12 to R.string.notify_type_index_reply,
            13 to R.string.notify_type_person_reply,
            14 to R.string.notify_type_request_friend,
            15 to R.string.notify_type_accept_friend,
            16 to R.string.notify_type_unknown,
            17 to R.string.notify_type_doujin_club_topic_reply,
            18 to R.string.notify_type_doujin_club_post_reply,
            19 to R.string.notify_type_doujin_subject_reply,
            20 to R.string.notify_type_doujin_event_topic_reply,
            21 to R.string.notify_type_doujin_event_post_reply,
            22 to R.string.notify_type_timeline_reply,
            23 to R.string.notify_type_group_mention,
            24 to R.string.notify_type_subject_mention,
            25 to R.string.notify_type_character_mention,
            26 to R.string.notify_type_person_mention,
            27 to R.string.notify_type_index_mention,
            28 to R.string.notify_type_timeline_mention,
            29 to R.string.notify_type_blog_mention,
            30 to R.string.notify_type_episode_mention,
            31 to R.string.notify_type_doujin_club_shoutbox_mention,
            32 to R.string.notify_type_doujin_club_topic_mention,
            33 to R.string.notify_type_doujin_subject_mention,
            34 to R.string.notify_type_doujin_event_topic_mention,
            35 to R.string.notify_type_subject_patch_accepted,
            36 to R.string.notify_type_episode_patch_accepted,
            37 to R.string.notify_type_subject_patch_rejected,
            38 to R.string.notify_type_episode_patch_rejected,
            39 to R.string.notify_type_subject_patch_expired,
            40 to R.string.notify_type_episode_patch_expired,
            41 to R.string.notify_type_character_patch_accepted,
            42 to R.string.notify_type_person_patch_accepted,
            43 to R.string.notify_type_character_patch_rejected,
            44 to R.string.notify_type_person_patch_rejected,
            45 to R.string.notify_type_character_patch_expired,
            46 to R.string.notify_type_person_patch_expired,
            47 to R.string.notify_type_subject_patch_reply,
            48 to R.string.notify_type_episode_patch_reply,
            49 to R.string.notify_type_character_patch_reply,
            50 to R.string.notify_type_person_patch_reply,
        )

        for (type in 1..50) {
            val expectedRes = expected[type] ?: R.string.notify_type_unknown
            assertEquals("Type $type 映射错误", expectedRes, noticeKindOf(type).labelRes())
        }
    }
}
