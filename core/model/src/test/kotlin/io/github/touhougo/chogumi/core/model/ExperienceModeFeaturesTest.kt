package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.model.ExperienceMode.FOCUS
import io.github.touhougo.chogumi.core.model.ExperienceMode.FULL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [appFeatures] 的**全表 2×2×2×2 断言**：模式、公共评分开关、封面评分开关、好友标识开关笛卡尔积的每个字段都逐个钉死。
 *
 * | mode | showPublicRatings | showCoverScores | community | publicRatings | coverScores |
 * |---|---|---|---|---|---|
 * | FULL | true | true | true | true | true |
 * | FULL | true | false | true | true | false |
 * | FULL | false | true / false | true | false | false |
 * | FOCUS | 同上 | 同上 | false | 同上 | 同上 |
 *
 * friendMarks = (mode == FULL) 且 showFriendMarks。
 */
class ExperienceModeFeaturesTest {

    @Test
    fun `完整模式加各个开关都开启全部功能`() {
        assertEquals(
            AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true),
            appFeatures(FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = true),
        )
    }

    @Test
    fun `关闭封面评分只关封面角标`() {
        assertEquals(
            AppFeatures(community = true, publicRatings = true, coverScores = false, friendMarks = true),
            appFeatures(FULL, showPublicRatings = true, showCoverScores = false, showFriendMarks = true),
        )
    }

    @Test
    fun `关闭公共评分时封面评分开关不起作用`() {
        assertEquals(
            AppFeatures(community = true, publicRatings = false, coverScores = false, friendMarks = true),
            appFeatures(FULL, showPublicRatings = false, showCoverScores = true, showFriendMarks = true),
        )
    }

    @Test
    fun `关闭好友标识只关好友小牌`() {
        assertEquals(
            AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = false),
            appFeatures(FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = false),
        )
    }

    @Test
    fun `专注模式只关社区_好友小牌随社区一起关`() {
        assertEquals(
            AppFeatures(community = false, publicRatings = true, coverScores = true, friendMarks = false),
            appFeatures(FOCUS, showPublicRatings = true, showCoverScores = true, showFriendMarks = true),
        )
    }

    @Test
    fun `全表逐字段断言`() {
        val modes = listOf(FULL, FOCUS)
        val switches = listOf(true, false)
        modes.forEach { mode ->
            switches.forEach { showRatings ->
                switches.forEach { showCover ->
                    switches.forEach { showFriends ->
                        val input = "($mode, showPublicRatings=$showRatings, showCoverScores=$showCover, showFriendMarks=$showFriends)"
                        val actual = appFeatures(mode, showRatings, showCover, showFriends)
                        assertEquals("$input 的 community", mode == FULL, actual.community)
                        assertEquals("$input 的 publicRatings", showRatings, actual.publicRatings)
                        assertEquals("$input 的 coverScores", showRatings && showCover, actual.coverScores)
                        assertEquals("$input 的 friendMarks", mode == FULL && showFriends, actual.friendMarks)
                    }
                }
            }
        }
    }
}
