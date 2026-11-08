package io.github.touhougo.chogumi.core.data.token

import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class SessionRequestHelperTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private fun newStore(): TokenStore = testTokenStore(resources)

    @Test
    fun `未登录时 block 收到 token 为 null 且正常返回`() = runTest {
        val s = newStore()
        var receivedSession: AccountSession? = null
        var receivedToken: String? = "initial"

        val result = s.withSessionToken { session, token ->
            receivedSession = session
            receivedToken = token
            "success"
        }

        assertEquals("success", result)
        assertEquals(false, receivedSession?.hasToken)
        assertNull(receivedToken)
    }

    @Test
    fun `登录时 block 收到有效 token`() = runTest {
        val s = newStore()
        val sessionA = s.beginSession("test_token_123")
        s.completeSession(sessionA, userId = 1, username = "testuser")

        var receivedSession: AccountSession? = null
        var receivedToken: String? = null

        val result = s.withSessionToken { session, token ->
            receivedSession = session
            receivedToken = token
            42
        }

        assertEquals(42, result)
        assertEquals(true, receivedSession?.hasToken)
        assertEquals("test_token_123", receivedToken)
    }

    @Test
    fun `调用期间换号抛出 SessionChangedException 且结果不落地`() = runTest {
        val s = newStore()
        val sessionA = s.beginSession("account_a_token")
        s.completeSession(sessionA, userId = 1, username = "usera")

        // block 返回了结果也拿不到：withSessionToken 抛出，调用方接在它后面的写库语句不会执行
        assertFailsWith<SessionChangedException> {
            s.withSessionToken { _, _ ->
                // 模拟在异步网络请求或处理期间发生了换号
                val sessionB = s.beginSession("account_b_token")
                s.completeSession(sessionB, userId = 2, username = "userb")
                "result_of_account_a"
            }
        }
    }

    @Test
    fun `传入 knownSession 时以此会话校验，若此前已换号则抛出 SessionChangedException`() = runTest {
        val s = newStore()
        val sessionA = s.beginSession("token_a")
        s.completeSession(sessionA, userId = 1, username = "usera")

        // 外部取了 sessionA
        val capturedSession = s.session()

        // 模拟在发起 withSessionToken 之前就发生了换号
        val sessionB = s.beginSession("token_b")
        s.completeSession(sessionB, userId = 2, username = "userb")

        var blockExecuted = false
        assertFailsWith<SessionChangedException> {
            s.withSessionToken(knownSession = capturedSession) { _, _ ->
                blockExecuted = true
                "should_not_reach"
            }
        }
        assertFalse(blockExecuted, "会话已失效时不得执行请求 block")
    }

    @Test
    fun `调用期间换号后的失败也换成 SessionChangedException`() = runTest {
        val s = newStore()
        s.completeSession(s.beginSession("token_a"), userId = 1, username = "usera")

        assertFailsWith<SessionChangedException> {
            s.withSessionToken { _, _ ->
                s.completeSession(s.beginSession("token_b"), userId = 2, username = "userb")
                // 旧账号的请求失败了：这个原因不该提示到新账号上
                throw IllegalStateException("旧账号的 400")
            }
        }
    }

    @Test
    fun `普通取消原样传播`() = runTest {
        val s = newStore()
        s.completeSession(s.beginSession("token_a"), userId = 1, username = "usera")
        val cancellation = CancellationException("页面关了")

        val thrown = assertFailsWith<CancellationException> {
            s.withSessionToken { _, _ ->
                s.completeSession(s.beginSession("token_b"), userId = 2, username = "userb")
                throw cancellation
            }
        }
        assertSame(cancellation, thrown)
    }
}
