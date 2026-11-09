package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.ReactionUserBo
import io.github.touhougo.chogumi.core.network.model.p1.ReactionDto
import io.github.touhougo.chogumi.core.network.model.p1.SimpleUserDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** 贴贴名单：服务端给的人按顺序留下（谁贴了的面板就画它），我拿出来记成 selected，同一个人只算一次。 */
class ReactionMapperTest {

    private val dto = ReactionDto(
        users = listOf(
            SimpleUserDto(2, "u2", "甲 &amp; 乙"),
            SimpleUserDto(42, "me", "我"),
            SimpleUserDto(3, "u3", "丙"),
            SimpleUserDto(2, "u2", "甲 &amp; 乙"),
        ),
        value = 79,
    )

    @Test
    fun `名单按服务端顺序留下别人，我记成 selected，重复的人只算一次`() {
        assertEquals(
            CommentReactionBo(79, listOf(ReactionUserBo(2, "u2", "甲 & 乙"), ReactionUserBo(3, "u3", "丙")), selected = true),
            dto.toReactionBo(currentUserId = 42),
        )
        assertEquals(3, dto.toReactionBo(currentUserId = 42).count)
    }

    @Test
    fun `没登录时名单里的每个人都是别人`() {
        val reaction = dto.toReactionBo()
        assertEquals(listOf(2, 42, 3), reaction.others.map { it.id })
        assertEquals(false, reaction.selected)
        assertEquals(3, reaction.count)
    }
}
