package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.res.R
import kotlin.test.Test
import kotlin.test.assertEquals

/** 好友 tab 方向文案：看自己用「我」，看他人用「TA」（原型 `.cbg` 关注或粉丝）。 */
class SocialDirectionLabelTest {

    @Test
    fun `看自己_我关注的与关注我的`() {
        assertEquals(R.string.profile_friends_count, SocialDirection.FOLLOWING.labelRes(isSelf = true))
        assertEquals(R.string.profile_followers_count, SocialDirection.FOLLOWERS.labelRes(isSelf = true))
        assertEquals(R.string.empty_following, SocialDirection.FOLLOWING.emptyRes(isSelf = true))
        assertEquals(R.string.empty_followers, SocialDirection.FOLLOWERS.emptyRes(isSelf = true))
    }

    @Test
    fun `看他人_TA关注的与关注TA的`() {
        assertEquals(R.string.profile_others_following, SocialDirection.FOLLOWING.labelRes(isSelf = false))
        assertEquals(R.string.profile_others_followers, SocialDirection.FOLLOWERS.labelRes(isSelf = false))
        assertEquals(R.string.empty_others_following, SocialDirection.FOLLOWING.emptyRes(isSelf = false))
        assertEquals(R.string.empty_others_followers, SocialDirection.FOLLOWERS.emptyRes(isSelf = false))
    }
}
