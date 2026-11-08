package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.bbcode.quoteOf
import io.github.touhougo.chogumi.core.common.bbcode.splitLeadingQuote
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.TopicType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 点了一层楼要回复谁（帖子正文是话题本身，主楼挂自己，楼中楼挂所在主楼并引用它；平铺的评论区开头 @ 那个人）、草稿怎么存回来、改自己的楼改的是哪段。 */
class ReplyTargetTest {

    private fun item(id: Int, nickname: String, content: String) = CommentItemBo(
        id = id, mainId = 1, relatedId = 0, creatorId = id, creatorNickname = nickname, creatorUsername = "user$id",
        creatorAvatar = null, content = content, createdAt = 0L, state = CommentState.NORMAL,
    )

    @Test
    fun `帖子正文就是回复话题本身，新开一层`() {
        assertEquals(ReplyTarget.Topic, replyTargetOf(item(10, "楼主", "正文"), "", anchorId = 10, asPost = true, threaded = true))
        assertEquals("同意", ReplyTarget.Topic.contentOf("同意"))
    }

    @Test
    fun `主楼挂在它自己下面，不引用`() {
        val target = replyTargetOf(item(20, "路人", "[b]观点[/b]"), "#2", anchorId = 20, asPost = false, threaded = true)
        assertEquals(20, target.replyTo)
        assertEquals("#2", target.floor)
        assertEquals(QuoteHeader("路人", "观点"), target.preview)
        assertNull(target.quote)
        assertEquals("同意", target.contentOf("同意"))
    }

    @Test
    fun `楼中楼挂在所在主楼下，正文开头引用它，引用能被楼层拆回来`() {
        val target = replyTargetOf(item(21, "", "楼中楼的话"), "#2-1", anchorId = 20, asPost = false, threaded = true)
        assertEquals(20, target.replyTo)
        val content = target.contentOf("同意")
        assertEquals("[quote][b]user21[/b] 说: 楼中楼的话[/quote]\n同意", content, "昵称为空时用用户名")
        val split = splitLeadingQuote(content)
        assertEquals(QuoteHeader("user21", "楼中楼的话"), split.quote)
        assertEquals("同意", split.body)
    }

    @Test
    fun `平铺的评论区：回复哪一条都新开一条，开头 @ 那个人`() {
        val anchor = replyTargetOf(item(30, "路人", "第一条"), "#3", anchorId = 30, asPost = false, threaded = false)
        assertEquals(0, anchor.replyTo, "动态的回复只有一层，不挂在被回复的那条下面")
        assertEquals(30, anchor.postId, "草稿仍按回复的那一条存")
        assertEquals(QuoteHeader("路人", "第一条"), anchor.preview)
        assertEquals("[user=user30]路人[/user] 同意", anchor.contentOf("同意"))
        val noUsername = item(31, "", "没有用户名").copy(creatorUsername = "")
        assertEquals("[user=31]#31[/user] 好", replyTargetOf(noUsername, "#4", 31, asPost = false, threaded = false).contentOf("好"))
    }

    @Test
    fun `草稿存下回复对象，还原回来一模一样`() {
        val target = replyTargetOf(item(21, "路人", "楼中楼的话"), "#2-1", anchorId = 20, asPost = false, threaded = true)
        val draft = target.draftOf(TopicType.GROUP, 5, "话题", "写了一半")
        assertEquals(21, draft.postId, "草稿按回复的那层楼存")
        assertEquals(target, draft.replyTarget())
        assertEquals(ReplyTarget.Topic, ReplyTarget.Topic.draftOf(TopicType.SUBJECT, 1, "话题", "x").replyTarget())
    }

    @Test
    fun `改楼中楼：引用头原样留着、画成引用条，编辑框里只有后面的正文`() {
        val content = quoteOf("路人", "楼中楼的话") + "我的回复"
        val target = editTargetOf(item(22, "我", content), "#2-2")
        assertEquals("我的回复", target.body)
        assertEquals(QuoteHeader("路人", "楼中楼的话"), target.quote)
        assertEquals("#2-2", target.floor)
        assertEquals(quoteOf("路人", "楼中楼的话") + "改过的", target.contentOf("改过的"))
    }

    @Test
    fun `改没有引用的楼：正文整段进编辑框，帖子正文没有楼层号`() {
        val target = editTargetOf(item(10, "我", "正文"), "")
        assertEquals("正文", target.body)
        assertNull(target.quote)
        assertNull(target.floor)
        assertEquals("新正文", target.contentOf("新正文"))
    }
}
