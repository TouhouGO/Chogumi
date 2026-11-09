package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户卡片开着的状态不属于某一层楼：楼滚出列表（离开组合）、页面重建后楼层还没回来，卡片都不能丢；
 * 楼层数据回来后按楼 id 找到那个人，原样再画出来。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class UserCardAboveListTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val threads = (1..FLOORS).map { id -> CommentThreadBo(anchor = comment(id), replies = emptyList()) }

    @Test
    fun 开着卡片时那层楼滚出列表_卡片还在() {
        lateinit var listState: LazyListState
        lateinit var scrollTo: (Int) -> Unit
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    scrollTo = { index -> scope.launch { listState.scrollToItem(index) } }
                    FloorsScreen(threads, listState)
                }
            }
        }
        openCardOf(1)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(signOf(1)).assertExists()

        composeRule.runOnIdle { scrollTo(FLOORS - 1) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 1 层的内容").assertDoesNotExist()

        composeRule.onNodeWithText(signOf(1)).assertExists()
    }

    @Test
    fun 重建后楼层还没回来时不画卡片_回来后原样画出() {
        var loaded by mutableStateOf(true)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    FloorsScreen(if (loaded) threads else emptyList(), rememberLazyListState())
                }
            }
        }
        openCardOf(3)
        composeRule.onNodeWithText(signOf(3)).assertExists()

        composeRule.runOnIdle { loaded = false }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText(signOf(3)).assertDoesNotExist()

        composeRule.runOnIdle { loaded = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(signOf(3)).assertExists()
    }

    @Test
    fun 卡片里点查看主页_去主页的回调带用户名_卡片仍开着() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    FloorsScreen(threads, rememberLazyListState(), onUserClick = { opened += it })
                }
            }
        }
        openCardOf(2)
        composeRule.onNodeWithText("查看主页").performClick()

        composeRule.runOnIdle { assertEquals(listOf("user2"), opened) }
        composeRule.onNodeWithText(signOf(2)).assertExists()
    }

    @Test
    fun 卡片里点查看主页_没有用户名时去主页的回调带数字uid() {
        val unnamed = comment(9).copy(creatorUsername = "")
        val opened = mutableListOf<String>()
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    FloorsScreen(listOf(CommentThreadBo(unnamed, emptyList())), rememberLazyListState(), onUserClick = { opened += it })
                }
            }
        }

        openCardOf(1)
        composeRule.onNodeWithText("查看主页").performClick()

        composeRule.runOnIdle { assertEquals(listOf("9"), opened) }
    }

    @Test
    fun 卡片状态只存一个id_重建后原样还在() {
        lateinit var state: UserCardState
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { state = rememberUserCardState() }
        composeRule.runOnIdle { state.open(42) }
        val saverScope = SaverScope { true }
        composeRule.runOnIdle {
            val saved: Any? = with(UserCardState.Saver) { saverScope.save(state) }
            assertEquals("存档载荷只有楼层 id", 42, saved)
        }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle { assertEquals(42, state.openId) }
        composeRule.runOnIdle { state.close() }
        composeRule.runOnIdle { assertNull(with(UserCardState.Saver) { saverScope.save(state) }) }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertNull(state.openId) }
    }

    /** 屏幕层的写法：卡片状态与卡片在列表之上，行只拿到「点了头像」。 */
    @Composable
    private fun FloorsScreen(threads: List<CommentThreadBo>, listState: LazyListState, onUserClick: (String) -> Unit = {}) {
        val userCard = rememberUserCardState()
        LazyColumn(state = listState) {
            itemsIndexed(threads, key = { _, thread -> thread.anchor.id }) { index, thread ->
                CommentThreadItem(thread = thread, floorNumber = "#${index + 1}", onAvatarClick = userCard::open, onReactionUsers = { _, _ -> })
            }
        }
        CommentUserCard(userCard, threads, onUserClick, onModerate = null)
    }

    /** 没有回复去处的楼里，头像是屏上唯一可点的东西：第 [id] 层楼（自上而下）的头像就是第 [id] 个可点节点。 */
    private fun openCardOf(id: Int) {
        composeRule.onAllNodes(hasClickAction())[id - 1].performClick()
    }

    private fun signOf(id: Int) = "签名$id"

    private fun comment(id: Int) = CommentItemBo(
        id = id,
        mainId = id,
        relatedId = 0,
        creatorId = id,
        creatorNickname = "用户$id",
        creatorUsername = "user$id",
        creatorAvatar = null,
        creatorSign = signOf(id),
        content = "第 $id 层的内容",
        createdAt = 0L,
        state = CommentState.NORMAL,
    )

    private companion object {
        const val FLOORS = 30
    }
}
