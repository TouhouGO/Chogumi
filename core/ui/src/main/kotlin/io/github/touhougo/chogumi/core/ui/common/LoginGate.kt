package io.github.touhougo.chogumi.core.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * 写操作的登录门，全仓唯一：已登录照常执行，未登录去登录页（收藏、贴贴、标进度、绝交、报告、发动态、看通知……）。
 * 页面用 `appEntries` 传下来的 `isLoggedIn` 与 `onLogin` 取得它（[rememberLoginGate]）；ViewModel 不判登录，
 * 楼层这类共用组件也不接收登录态，只拿已经过门的回调（[Gated]）。
 */
@Immutable
class LoginGate internal constructor(private val isLoggedIn: () -> Boolean, private val onLogin: () -> Unit) {
    /** 已登录执行 [action]，未登录去登录页。 */
    fun run(action: () -> Unit) {
        if (isLoggedIn()) action() else onLogin()
    }

    /** 过门的无参回调。 */
    fun guard(action: () -> Unit): Gated<() -> Unit> = Gated(GatedSource(this, action)) { run(action) }

    /** 过门的单参数回调。 */
    fun <A> guard(action: (A) -> Unit): Gated<(A) -> Unit> = Gated(GatedSource(this, action)) { a -> run { action(a) } }

    /** 过门的双参数回调（如贴贴：楼 id + 表情）。 */
    fun <A, B> guard(action: (A, B) -> Unit): Gated<(A, B) -> Unit> = Gated(GatedSource(this, action)) { a, b -> run { action(a, b) } }

    /** 过门的三参数回调（如发回复：草稿 + 令牌 + 发出后的落点）。 */
    fun <A, B, C> guard(action: (A, B, C) -> Unit): Gated<(A, B, C) -> Unit> =
        Gated(GatedSource(this, action)) { a, b, c -> run { action(a, b, c) } }
}

/** 把已过门的三参数回调的最后一个参数固定下来，仍是过了门的回调。 */
fun <A, B, C> Gated<(A, B, C) -> Unit>.withLast(last: C): Gated<(A, B) -> Unit> = Gated(GatedSource(this, last)) { a, b -> action(a, b, last) }

/**
 * 已经过了登录门的写操作回调，只有 [LoginGate] 造得出来。共用组件的写操作参数一律声明成它：
 * 页面直接把 ViewModel 方法传进去就编译不过，漏门在编译期暴露，不靠门禁去猜。调用写 `onX.action(…)`。
 */
@Immutable
class Gated<out F : Function<Unit>> internal constructor(private val source: GatedSource, val action: F) {
    /**
     * 同一道门、同一个原始回调（同一个 ViewModel 方法引用）造出的两个相等：页面每次重组都重新过门，
     * 不相等的话收它的组件（章节网格、楼层）每一次重组都跟着整块重组。
     */
    override fun equals(other: Any?): Boolean = other is Gated<*> && other.source == source

    override fun hashCode(): Int = source.hashCode()
}

/** [Gated] 的来历：哪道门（或哪个已过门的回调）、包的是什么。 */
internal data class GatedSource(val gate: Any, val action: Any?)

/** 页面里的登录门：登录状态变了不必重建回调，门总按当前状态放行。 */
@Composable
fun rememberLoginGate(isLoggedIn: Boolean, onLogin: () -> Unit): LoginGate {
    val loggedIn = rememberUpdatedState(isLoggedIn)
    val login = rememberUpdatedState(onLogin)
    return remember { LoginGate({ loggedIn.value }, { login.value() }) }
}
