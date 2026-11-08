package io.github.touhougo.chogumi.core.foundation

/**
 * 一次性操作（一次请求、一次写入）的结果：成功或失败，没有「加载中」——一次性操作只有这两种结局。
 * 先缓存、后网络的加载流见 [Load]。
 */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>

    data class Failed(val failure: Failure) : Outcome<Nothing>
}

/** 成功的值；失败时为 null。 */
fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Success)?.value

/** 失败的原因；成功时为 null。 */
fun Outcome<*>.failureOrNull(): Failure? = (this as? Outcome.Failed)?.failure

/** 成功的值换一种形状；失败原样传下去。 */
inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failed -> this
}
