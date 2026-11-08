package io.github.touhougo.chogumi.shell

import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.SignedInAccount
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动时整屏显示什么（启动屏、慢启动提示、恢复失败、页面），只由 [ShellState] 判定。 */
class ShellStateTest {

    @Test
    fun `恢复中先留启动屏 过了慢启动时限换成慢启动提示`() {
        val restoring = ShellState()
        assertTrue(restoring.initializing)
        assertFalse(restoring.slowStart)

        val overdue = restoring.copy(restoreOverdue = true)
        assertFalse(overdue.initializing)
        assertTrue(overdue.slowStart)
    }

    @Test
    fun `恢复完或读失败后 过了时限也不再显示慢启动提示`() {
        val signedIn = ShellState(session = SessionState.Authenticated(SignedInAccount(1, "fixture_user")), restoreOverdue = true)
        assertFalse(signedIn.slowStart)
        assertFalse(signedIn.initializing)

        val failed = ShellState(restoreFailed = true, restoreOverdue = true)
        assertFalse(failed.slowStart)
        assertFalse(failed.initializing)
    }
}
