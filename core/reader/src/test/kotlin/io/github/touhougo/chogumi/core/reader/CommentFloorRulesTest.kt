package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import io.github.touhougo.chogumi.core.ui.composables.othersRatingStars

class CommentFloorRulesTest {

    private val on = AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true)
    private val off = AppFeatures(community = true, publicRatings = false, coverScores = false, friendMarks = true)

    @Test
    fun `吐槽星级：10 分制折半，差 0_25 颗以内算满，不画半星`() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5), (1..10).map { othersRatingStars(it, on) })
    }

    @Test
    fun `吐槽星级：公共评分关掉或没打分时整行不画`() {
        assertNull(othersRatingStars(8, off))
        assertNull(othersRatingStars(0, on))
    }

    private fun item(id: Int, nickname: String, content: String) = CommentItemBo(
        id = id, mainId = 1, relatedId = 0, creatorId = id, creatorNickname = nickname, creatorUsername = "u$id",
        creatorAvatar = null, content = content, createdAt = 0L, state = CommentState.NORMAL,
    )

    @Test
    fun `引用反查按楼层顺序摊平：主楼后紧跟它的楼中楼，串 id 取主楼`() {
        val threads = listOf(
            CommentThreadBo(
                anchor = item(1, "甲", "第一楼"),
                replies = listOf(item(2, "乙", "[quote][b]甲[/b] 说: 第一楼[/quote]回复")),
            ),
            CommentThreadBo(
                anchor = item(3, "丙", "第二楼"),
                replies = listOf(item(4, "丁", "[quote][b]乙[/b] 说: 回复[/quote]跨串引用")),
            ),
        )
        assertEquals(mapOf(2 to 1, 4 to 2), commentQuoteTargets(threads))
    }
}
