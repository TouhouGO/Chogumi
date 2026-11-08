package io.github.touhougo.chogumi.core.network

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.OAuthError
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.mayHaveReachedServer
import io.github.touhougo.chogumi.core.network.api.CollectionNotCollectedException
import io.github.touhougo.chogumi.core.network.model.ErrorDetailDto
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.Logger
import io.ktor.http.HttpStatusCode
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException

/**
 * 异常 → 失败原因（[Failure]），**全仓唯一的判定处**。文案不在这里：界面按语言取（core:ui `Failure.text()`）。
 *
 * 服务端错误体（v0 与 p1 都是英文）只用来认 OAuth / p1 错误码，原文从不上屏；认不出的异常记为 [Failure.Unknown]，
 * 并写一行不含消息与参数的诊断（类型链 + 代码位置），排障用。这行经 Ktor 默认 logger 走 SLF4J，
 * 只有调试包带了 SLF4J 实现（app 的 `debugImplementation(libs.slf4j.simple)`），所以只在调试包里看得到。
 */
suspend fun Throwable.toFailure(): Failure = toFailure(Logger.DEFAULT)

internal suspend fun Throwable.toFailure(logger: Logger): Failure = when (this) {
    is FailureException -> failure
    // 数据层拿它回退到 POST 创建收藏；漏到这里时仍是它本来的 404，不是认不出的异常
    is CollectionNotCollectedException -> Failure.Http(HttpStatusCode.NotFound.value)
    is ResponseException -> errorBody().let { body -> rejectionOf(body?.code) ?: Failure.Http(response.status.value, OAuthError.of(body?.error)) }
    is SerializationException -> Failure.BadResponse
    else -> networkFailure() ?: run {
        // 诊断失败不能影响结果
        runCatching { logger.log(unknownErrorDiagnostic()) }
        Failure.Unknown
    }
}

/**
 * 网络层失败的唯一分类（不是网络层失败为 null），[toFailure] 与只读请求的重试（[createHttpClient]）都以它为准：
 * - 超时 → [Failure.Timeout]。OkHttp 引擎的读 / 写超时是 `java.net.SocketTimeoutException`，不是 Ktor 的 `HttpRequestTimeoutException`；
 * - 域名解析不了、连不上（连接超时也算：请求根本没发出） → [Failure.Offline]：本机没网络，重试也还是没有；
 * - 其余 `IOException`（`SSLException`、`EOFException`、`SocketException`）→ [Failure.Interrupted]：已经连上、
 *   却在传输途中被断开。更具体的两类排在前面（同属 IOException 家族）。
 */
internal fun Throwable.networkFailure(): Failure? = when (this) {
    is HttpRequestTimeoutException, is SocketTimeoutException -> Failure.Timeout
    is UnknownHostException, is ConnectException, is ConnectTimeoutException -> Failure.Offline
    is java.io.IOException -> Failure.Interrupted
    else -> null
}

/** 值得重发一次的网络层失败：超时和传输途中被断开。断网（[Failure.Offline]）重试只会让离线提示晚几秒才出。 */
internal fun Throwable.isRetryableNetworkFailure(): Boolean = networkFailure().let { it?.mayHaveReachedServer() == true }

/** 错误体；读不出时为 null。 */
private suspend fun ResponseException.errorBody(): ErrorDetailDto? = runCatching { response.body<ErrorDetailDto>() }.getOrNull()

/**
 * p1 错误码里能直接说清原因的：人机验证没过（`CAPTCHA_ERROR`）、非公开小组没加入（`NOT_JOIN_PRIVATE_GROUP_ERROR`）。
 * 两者服务端都回 401，按状态码会被说成登录失效。其余错误码按状态码处理。
 */
private fun rejectionOf(code: String?): Failure? = when (code) {
    "CAPTCHA_ERROR" -> Failure.Rejected(RejectReason.VERIFICATION_FAILED)
    "NOT_JOIN_PRIVATE_GROUP_ERROR" -> Failure.Rejected(RejectReason.GROUP_MEMBERS_ONLY)
    else -> null
}

/** 未分类异常的诊断指纹：原因链有界且按身份去重，保留原始异常和转换边界的位置；不含任何消息。 */
private fun Throwable.unknownErrorDiagnostic(): String {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val kinds = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && kinds.size < 4 && seen.add(current)) {
        kinds += current.javaClass.name
        current = current.cause
    }
    val handledAt = handlingFrame(Throwable().stackTrace)
    return "Chogumi 未分类异常：类型=${kinds.joinToString(" -> ")}；发生位置=${stackTrace.firstOrNull()}；处理位置=$handledAt"
}

/**
 * 处理位置：第一个既不属于本文件（栈顶那一帧的类，含它的续体）、也不属于库和平台的帧。
 * 不看包名：混淆后应用的类名不再带包名前缀。
 */
internal fun handlingFrame(frames: Array<StackTraceElement>): StackTraceElement? {
    val ownClass = frames.firstOrNull()?.className ?: return null
    return frames.firstOrNull { frame ->
        !frame.className.startsWith(ownClass) && LIBRARY_FRAME_PREFIXES.none { frame.className.startsWith(it) }
    }
}

/** 诊断里的「处理位置」要跳过的帧：协程、Ktor、Kotlin 标准库与平台。 */
private val LIBRARY_FRAME_PREFIXES = listOf("kotlinx.coroutines.", "io.ktor.", "kotlin.", "java.", "javax.", "jdk.", "sun.")
