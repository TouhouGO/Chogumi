package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 换掉「我」的贴贴、楼层树里改贴贴、评论区决定可贴集与回复去处。 */
class ReactionsTest {

    private val mine = CommentReactionBo(0, 2, selected = true)
    private val other = CommentReactionBo(54, 1)

    @Test
    fun `换贴：原来那个减一、新的加一并标成我的`() {
        assertEquals(listOf(CommentReactionBo(0, 1), CommentReactionBo(54, 2, selected = true)), listOf(mine, other).withMyReaction(54))
    }

    @Test
    fun `新表情追加在末尾，减到零的整项去掉`() {
        val result = listOf(CommentReactionBo(0, 1, selected = true), other).withMyReaction(79)
        assertEquals(listOf(other, CommentReactionBo(79, 1, selected = true)), result)
    }

    @Test
    fun `取消：只撤掉我的`() {
        assertEquals(listOf(CommentReactionBo(0, 1), other), listOf(mine, other).withMyReaction(null))
        assertEquals("没贴过时取消不改任何项", listOf(other), listOf(other).withMyReaction(null))
    }

    @Test
    fun `楼层树里按楼 id 改主楼或楼中楼`() {
        fun item(id: Int) = CommentItemBo(id, 0, 0, 1, "", "", null, "", 0L, CommentState.NORMAL)
        val threads = listOf(CommentThreadBo(item(1), listOf(item(2))), CommentThreadBo(item(3)))
        for (target in listOf(1, 2, 3)) {
            val patched = threads.withReactions(target, listOf(other))
            assertEquals("目标楼 $target 的贴贴", listOf(other), patched.findItem(target)?.reactions)
            for (untouched in listOf(1, 2, 3) - target) {
                assertEquals("其他楼 $untouched 不变", threads.findItem(untouched), patched.findItem(untouched))
            }
        }
        assertEquals(threads, threads.withReactions(99, listOf(other)))
        assertNull(threads.findItem(99))
    }

    @Test
    fun `评论区：讨论回复与吐槽各有可贴集，留言没有贴贴`() {
        assertEquals(COMMON_REACTIONS, reactionsOf(CommentSurface.Topic(TopicType.SUBJECT, 1)))
        assertEquals(COMMON_REACTIONS, reactionsOf(CommentSurface.Topic(TopicType.GROUP, 1)))
        // 单集评论与讨论回复同用通用组（服务端 like.ts：EpisodeReply 不在吐槽专用组里）
        assertEquals(COMMON_REACTIONS, reactionsOf(CommentSurface.Episode(1)))
        assertEquals(SUBJECT_COLLECT_REACTIONS, reactionsOf(CommentSurface.SubjectComments(1)))
        assertTrue(COMMON_REACTIONS.containsAll(SUBJECT_COLLECT_REACTIONS))
        listOf(
            CommentSurface.Blog(1),
            CommentSurface.Index(1),
            CommentSurface.Mono(MonoType.PERSON, 1),
            CommentSurface.Timeline(1L),
        ).forEach {
            assertTrue(reactionsOf(it).isEmpty())
        }
    }

    @Test
    fun `有回复接口的评论区都在 App 里回复：话题与对象的评论区分两层，动态平铺，作品吐槽不能回复`() {
        listOf(
            CommentSurface.Topic(TopicType.SUBJECT, 7),
            CommentSurface.Topic(TopicType.GROUP, 8),
            CommentSurface.Blog(5),
            CommentSurface.Index(3),
            CommentSurface.Mono(MonoType.CHARACTER, 8),
            CommentSurface.Mono(MonoType.PERSON, 9),
            CommentSurface.Episode(122548),
        ).forEach { assertEquals("$it", ReplyStyle.THREADED, replyStyleOf(it)) }
        // 动态的回复服务端只存一层：平铺，回复某一条是新的一条、开头 @ 那个人
        assertEquals(ReplyStyle.FLAT, replyStyleOf(CommentSurface.Timeline(42L)))
        // 吐槽挂在收藏上，服务端没有回复吐槽的接口
        assertNull(replyStyleOf(CommentSurface.SubjectComments(1)))
    }
}
