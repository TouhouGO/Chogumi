package io.github.touhougo.chogumi.core.data.util

import io.github.touhougo.chogumi.core.data.token.AccountSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一份按账号分区的本机缓存（绝交名单、好友名单、NSFW 偏好）的读与写：写在服务端做成之后，同一账号写之前发出的读取
 * 既不落进缓存，也不交给写之后才来要的调用方。
 *
 * 写做成时经 [write] 推进该账号的写代数（同时记下写的结果，有的话）。读取经 [read]：
 * - 同一会话、同一写代数的读取同时只发一次，后来的调用方等同一次的结果；写之后来要的调用方属于新的一代，不接写之前那次
 *   （那次可能已经落了缓存、只是还没交出）。
 * - 读到后这个账号的写代数没动才落缓存并交出；途中有写做成就作废这次结果、按新的一代再读，晚到的旧读取不盖掉刚写下的缓存。
 *   判断与写缓存在同一把锁里，读与写不会交错。另一个账号的写不碰这次读取。
 */
internal class WriteFence<T> {
    private val mutex = Mutex()
    private val writes = HashMap<Int, Long>()
    private val reads = SharedFetch<Pair<AccountSession, Long>, T>()

    /** [session] 的写在服务端做成：推进这个账号的写代数，[publish] 把写的结果记进缓存（只推进时不传）。 */
    suspend fun write(session: AccountSession, publish: suspend () -> Unit = {}) = mutex.withLock {
        val account = session.account()
        writes[account] = writes.generation(account) + 1
        publish()
    }

    /** 读取 [session] 的 [fetch] 并经 [publish] 落缓存；交出的总是这个账号最后一次写之后读到的结果。 */
    suspend fun read(session: AccountSession, fetch: suspend () -> T, publish: suspend (T) -> Unit): T {
        val account = session.account()
        val generation = mutex.withLock { writes.generation(account) }
        return reads.fetch(session to generation) {
            val value = fetch()
            val current = mutex.withLock { writes.generation(account).also { if (it == generation) publish(value) } }
            // 途中有写做成：这次作废，按新的一代再读（写之后来要的调用方已经在读的话，接它那一次）
            if (current == generation) value else read(session, fetch, publish)
        }
    }

    private fun AccountSession.account(): Int = requireNotNull(userId)

    private fun Map<Int, Long>.generation(account: Int): Long = this[account] ?: 0L
}
