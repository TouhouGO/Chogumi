package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import kotlin.test.Test
import kotlin.test.assertEquals

/** 名单还没读到时内容算不算到了（[shownUnder]）。 */
class BlockedUsersShownTest {

    @Test
    fun `名单还没读到_已到的内容仍算等待`() {
        assertEquals(Load.Pending, Load.Ready("楼层").shownUnder(null))
        assertEquals(Load.Pending, Load.Ready("楼层", fromCache = true).shownUnder(null))
    }

    @Test
    fun `名单读到了_内容原样`() {
        assertEquals(Load.Ready("楼层"), Load.Ready("楼层").shownUnder(BlockedUsers.NONE))
        assertEquals(Load.Ready("楼层"), Load.Ready("楼层").shownUnder(BlockedUsers(setOf(9))))
    }

    @Test
    fun `取不到与等待照常传下去_与名单读没读到无关`() {
        val failed = Load.Failed(Failure.Unknown)
        assertEquals(failed, failed.shownUnder(null))
        assertEquals(Load.Pending, Load.Pending.shownUnder(null))
    }
}
