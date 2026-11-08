package io.github.touhougo.chogumi.shell

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.SignedInAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 外壳的会话从一个状态变到另一个时，[AccountPageScope] 里页面私有的可保存状态留不留。
 * 作用域按外壳实际传入的 [ShellState.account] 取，和 [AppShell] 走同一条路。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AccountPageScopeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var shell by mutableStateOf(ShellState(session = signedIn(ACCOUNT_A)))

    /** 在 [ShellState] 为 [shell] 时给页面写下一份私有状态。 */
    private fun showPageWithPrivateState() {
        composeRule.setContent {
            AccountPageScope(shell.account) {
                var privateValue by rememberSaveable { mutableStateOf(FRESH_PAGE) }
                Column {
                    Text(privateValue, modifier = Modifier.testTag(PRIVATE_STATE))
                    Button(onClick = { privateValue = WRITTEN }, modifier = Modifier.testTag(SET_STATE)) {
                        Text("set")
                    }
                }
            }
        }
        composeRule.onNodeWithTag(SET_STATE).performClick()
        composeRule.onNodeWithTag(PRIVATE_STATE).assertTextEquals(WRITTEN)
    }

    @Test
    fun `已登录直接换成另一个号 丢掉上一个号的页面状态`() {
        showPageWithPrivateState()

        shell = ShellState(session = signedIn(ACCOUNT_B))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PRIVATE_STATE).assertTextEquals(FRESH_PAGE)
    }

    @Test
    fun `登出 丢掉账号的页面状态`() {
        showPageWithPrivateState()

        shell = ShellState(session = SessionState.Unauthenticated)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PRIVATE_STATE).assertTextEquals(FRESH_PAGE)
    }

    @Test
    fun `userId 不变时用户名与外壳状态再变 页面状态保留`() {
        showPageWithPrivateState()

        shell = ShellState(session = signedIn(ACCOUNT_A.copy(username = "fixture_user_renamed")), unreadCount = 3)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PRIVATE_STATE).assertTextEquals(WRITTEN)
    }

    private companion object {
        val ACCOUNT_A = SignedInAccount(7, "fixture_user")
        val ACCOUNT_B = SignedInAccount(8, "other")
        const val FRESH_PAGE = "new page"
        const val WRITTEN = "account A message"
        const val PRIVATE_STATE = "privateState"
        const val SET_STATE = "setState"

        fun signedIn(account: SignedInAccount) = SessionState.Authenticated(account)
    }
}
