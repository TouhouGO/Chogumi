package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.ViewerSource
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.model.TopicReplyDraft
import io.github.touhougo.chogumi.core.model.TopicType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** 草稿与屏蔽名单按账号分：换号后看不到上一个账号的，换回来还在。 */
class CommunityLocalAccountTest {
    private class SwitchableViewer(first: Int) : ViewerSource {
        val id = MutableStateFlow(first)
        override val viewerIds = id
    }

    private val viewer = SwitchableViewer(7)
    private val mutes = GroupMuteRepository(FakeMutedGroupDao(), viewer)

    @Test
    fun `话题回复草稿不跨账号`() = runTest {
        val tokens = testTokenStore(backgroundScope)
        suspend fun switchTo(id: Int) {
            tokens.completeSession(tokens.beginSession("test-token"), id, "user$id")
        }
        val drafts = testDraftRepository(backgroundScope, tokens = tokens)
        switchTo(7)
        val owner = drafts.boundDraftOwner()
        drafts.save(owner, TopicReplyDraft(TopicType.GROUP, 3, "标题", 0, 0, null, null, null, null, "甲的话", 1), now = 10)

        switchTo(8)
        assertNull(drafts.get(drafts.boundDraftOwner(), TopicType.GROUP, 3, 0))
        assertEquals(Load.Ready(emptyList()), drafts.observeAll().first())

        switchTo(7)
        assertEquals("甲的话", drafts.get(drafts.boundDraftOwner(), TopicType.GROUP, 3, 0)?.text)
    }

    @Test
    fun `新帖草稿不跨账号，标题与正文都空时删掉`() = runTest {
        val tokens = testTokenStore(backgroundScope)
        suspend fun switchTo(id: Int) {
            tokens.completeSession(tokens.beginSession("test-token"), id, "user$id")
        }
        val drafts = testDraftRepository(backgroundScope, tokens = tokens)
        switchTo(7)
        val owner = drafts.boundDraftOwner()
        drafts.savePost(owner, PostDraftKind.GROUP_TOPIC, "a", "标题", "正文", now = 10)

        assertEquals("标题", drafts.getPost(owner, PostDraftKind.GROUP_TOPIC, "a")?.title)
        assertNull(drafts.getPost(owner, PostDraftKind.TIMELINE, ""))
        switchTo(8)
        assertNull(drafts.getPost(drafts.boundDraftOwner(), PostDraftKind.GROUP_TOPIC, "a"))

        switchTo(7)
        drafts.savePost(drafts.boundDraftOwner(), PostDraftKind.GROUP_TOPIC, "a", " ", "", now = 20)
        assertNull(drafts.getPost(drafts.boundDraftOwner(), PostDraftKind.GROUP_TOPIC, "a"))
    }

    @Test
    fun `屏蔽名单不跨账号`() = runBlocking {
        mutes.mute(GroupSummaryBo("a", "A", null, 1), now = 5)

        viewer.id.value = 8
        assertEquals(Load.Ready(emptyList()), mutes.observeAll().first())
        mutes.unmute("a")

        viewer.id.value = 7
        assertEquals(listOf("a"), (mutes.observeAll().first() as Load.Ready).value.map { it.name })
    }
}
