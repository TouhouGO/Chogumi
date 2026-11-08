package io.github.touhougo.chogumi.core.data.util

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.toLoad
import io.github.touhougo.chogumi.core.data.token.SessionChangedException
import io.github.touhougo.chogumi.core.network.toFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 数据层里「一个异常算什么失败」的唯一判定，[outcomeOf] 与 [failuresAsLoad] 都经这里：
 *
 * - 途中换了账号（[SessionChangedException]）→ [Failure.SessionChanged]；
 * - 其余取消照常抛出，协程该停就停；
 * - 别的异常经 [toFailure] 变成失败原因（「异常 → 失败」唯一的映射）。
 */
suspend fun failureOf(e: Exception): Failure = when (e) {
    is SessionChangedException -> Failure.SessionChanged
    is CancellationException -> throw e
    else -> e.toFailure()
}

/** 跑一次请求 / 写入，结果收成 [Outcome]——调用处不各写 try / catch。失败原因见 [failureOf]。 */
suspend inline fun <T> outcomeOf(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: Exception) {
    Outcome.Failed(failureOf(e))
}

/**
 * 这个写接口在服务端稳定 5xx（Bangumi 那边的故障，与请求形态无关）：改用网页做同一件事（[onWeb]：带网页会话打开操作链接，
 * 再回读接口确认）。网页也没做成时，界面要能照着做：没有网页会话（要账号密码登录）、途中没登录或换了号、网络不通，原样给出；
 * 其余（网页报错、回读仍未生效）换成「去网页端操作」的 [reason]。不是 5xx 的失败原样。
 *
 * 超时不补：超时后不知道服务端做没做，而这里的网页那一步是「点一次操作链接」（解除好友、绝交、取消收藏角色、删动态），
 * 服务端已经做成时再点会报错或找不到那一条，把做成的事报成失败。网页那一步本身可重复、最后回读终态的写用 [onWebIfUnconfirmed]。
 */
suspend fun <T> Outcome<T>.onWebIfServerError(reason: RejectReason, onWeb: suspend () -> T): Outcome<T> =
    onWebWhen(reason, onWeb) { it.isServerError() }

/**
 * 写接口没确认做成时改用网页做同一件事：服务端 5xx，或请求超时（[Failure.Timeout]：不知道服务端收没收到）。只给网页那一步
 * 重复做也没有副作用、做完回读终态才算成的写用：设开关（个人设置表单整张提交、把这一项设成目标值），重做一遍结果一样，
 * 回读说了算。超时可能出在网络上而不是服务端（弱网、模拟器解析不了域名），所以不能只认 5xx。网页那一步的失败怎么给出，
 * 同 [onWebIfServerError]。
 */
suspend fun <T> Outcome<T>.onWebIfUnconfirmed(reason: RejectReason, onWeb: suspend () -> T): Outcome<T> =
    onWebWhen(reason, onWeb) { it.isServerError() || it == Failure.Timeout }

private fun Failure.isServerError(): Boolean = this is Failure.Http && status in 500..599

private suspend fun <T> Outcome<T>.onWebWhen(reason: RejectReason, onWeb: suspend () -> T, unconfirmed: (Failure) -> Boolean): Outcome<T> {
    val failure = (this as? Outcome.Failed)?.failure
    if (failure == null || !unconfirmed(failure)) return this
    return when (val web = outcomeOf { onWeb() }) {
        is Outcome.Success -> web
        is Outcome.Failed -> if (web.failure.keptFromWeb()) web else Outcome.Failed(Failure.Rejected(reason))
    }
}

/** 网页那一步的失败里，换成「去网页端操作」反而误导的那些。 */
private fun Failure.keptFromWeb(): Boolean = when (this) {
    Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED),
    Failure.NotLoggedIn,
    Failure.SessionChanged,
    Failure.Offline,
    Failure.Timeout,
    Failure.Interrupted,
    -> true
    else -> false
}

/**
 * 发言类写接口被 403 拒绝（服务端的 `NOT_ALLOWED`）：失败原因换成说得清的 [reason]。别的失败原样。
 * 发帖、回复、发动态 → [RejectReason.POSTING_NOT_ALLOWED]（账号被禁言、话题已关闭）；
 * 改自己的楼 → [RejectReason.EDIT_NOT_ALLOWED]（楼下已有回复、话题已关闭）。
 */
fun <T> Outcome<T>.forbiddenAs(reason: RejectReason): Outcome<T> {
    val failure = (this as? Outcome.Failed)?.failure
    return if (failure is Failure.Http && failure.status == 403) Outcome.Failed(Failure.Rejected(reason)) else this
}

/**
 * 加载流的失败收尾：上游抛出的异常收成最后一拍 [Load.Failed]（原因见 [failureOf]）。
 * 用 `catch` 算子，不在 `flow { }` 里包 try：那样连下游收集者抛的异常也会被接住再 emit，违反流的异常透明。
 */
fun <T> Flow<Load<T>>.failuresAsLoad(): Flow<Load<T>> = catch { e ->
    if (e !is Exception) throw e
    emit(Load.Failed(failureOf(e)))
}

/**
 * 本机存储（Room、DataStore）异常的唯一分类，观察流（[storageFailuresAsLoad]）与一次性存取（[storageOutcomeOf]）共用：
 * 取消照常抛出；换号与已经带着原因的 [FailureException] 保留原因（见 [failureOf]）；其余都是 [Failure.Storage]——
 * DataStore 的 `IOException` 与 Room 的 `SQLiteException` 一样，不当成网络中断，也不当成认不出的错误。
 */
suspend fun storageFailureOf(e: Exception): Failure = when (e) {
    is SessionChangedException, is FailureException -> failureOf(e)
    is CancellationException -> throw e
    else -> Failure.Storage
}

/** 本机存取收成一次性结果，失败原因见 [storageFailureOf]。[block] 只能是本机存取：网络请求的失败会被认成存储失败。 */
suspend inline fun <T> storageOutcomeOf(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: Exception) {
    Outcome.Failed(storageFailureOf(e))
}

/**
 * 本机存储观察流的失败收尾：异常收成一拍 [Load.Failed]（原因见 [storageFailureOf]），取消照常抛出。
 *
 * [keepAliveOnFailure] 供按账号切换的长期观察使用：失败后停在流里等账号变化，换号时外层还能发空拍清掉旧账号数据；
 * 同一账号的恢复仍由收集方的显式重试重新订阅，不在这里忙循环。
 */
fun <T> Flow<Load<T>>.storageFailuresAsLoad(keepAliveOnFailure: Boolean = false): Flow<Load<T>> = catch { e ->
    if (e !is Exception) throw e
    emit(Load.Failed(storageFailureOf(e)))
    if (keepAliveOnFailure) awaitCancellation()
}

/** 原始本机存储流包成 [Load]，异常规则见 [storageFailuresAsLoad]。 */
fun <T> Flow<T>.storageAsLoad(keepAliveOnFailure: Boolean = false): Flow<Load<T>> =
    map<T, Load<T>> { Load.Ready(it) }.storageFailuresAsLoad(keepAliveOnFailure)

/** 一次性调用当作只有一拍的加载流：页面区块不分一次性还是缓存流，统一按流收（StateViewModel.collectSection）。 */
fun <T> loadOnce(block: suspend () -> T): Flow<Load<T>> = loadOutcome { outcomeOf { block() } }

/** 已经返回 [Outcome] 的一次性调用当作只有一拍的加载流（同 [loadOnce]）。 */
fun <T> loadOutcome(block: suspend () -> Outcome<T>): Flow<Load<T>> = flow { emit(block().toLoad()) }
