package io.github.touhougo.chogumi.core.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginGateTest {

    private val calls = mutableListOf<String>()

    private fun gate(loggedIn: () -> Boolean) = LoginGate(loggedIn) { calls += "login" }

    @Test
    fun `已登录照常执行，不去登录页`() {
        gate { true }.run { calls += "action" }
        assertEquals(listOf("action"), calls)
    }

    @Test
    fun `未登录去登录页，不执行写操作`() {
        gate { false }.run { calls += "action" }
        assertEquals(listOf("login"), calls)
    }

    @Test
    fun `包过门的回调按调用那一刻的登录状态放行，参数原样传下去`() {
        var loggedIn = false
        val react = gate { loggedIn }.guard { id: Int, value: Int? -> calls += "react $id $value" }
        react.action(10, 54)
        loggedIn = true
        react.action(10, null)
        assertEquals(listOf("login", "react 10 null"), calls)
    }

    @Test
    fun `无参与单参数的过门回调同样按当下登录状态放行`() {
        var loggedIn = false
        val g = gate { loggedIn }
        val collect = g.guard { calls += "collect" }
        val pick = g.guard { sort: Int -> calls += "pick $sort" }
        collect.action()
        pick.action(3)
        loggedIn = true
        collect.action()
        pick.action(4)
        assertEquals(listOf("login", "login", "collect", "pick 4"), calls)
    }
}
