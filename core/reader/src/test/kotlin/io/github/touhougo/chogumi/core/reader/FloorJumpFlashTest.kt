package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 引用跳转的闪烁跟着每一次跳转计时：亮着时再点同一楼的引用，从第二次跳转起再亮满一整段，
 * 不因为目标 id 没变就沿用第一次的计时、刚滚到就熄。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FloorJumpFlashTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 亮着时再跳同一楼从第二次跳转起重新计时() {
        lateinit var jump: FloorJump
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val listState = rememberLazyListState()
            jump = rememberFloorJump(listState, firstReplyItem = 0)
            LazyColumn(state = listState) { items(40) { Text("楼 $it") } }
        }
        composeRule.runOnIdle { jump.jumpTo(floorIndex = 30, targetId = 7) }
        composeRule.mainClock.advanceTimeBy(FLASH_MILLIS - 200)
        composeRule.runOnIdle { jump.jumpTo(floorIndex = 30, targetId = 7) }

        composeRule.mainClock.advanceTimeBy(FLASH_MILLIS - 200)
        composeRule.runOnIdle { assertEquals("第二次跳转后还没到一整段，仍亮着", 7, jump.flashingId) }

        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle { assertNull("第二次跳转满一整段后熄灭", jump.flashingId) }
    }
}
