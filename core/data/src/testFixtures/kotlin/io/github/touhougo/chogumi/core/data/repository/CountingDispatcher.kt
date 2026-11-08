package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

/** 数自己被调度了几次的调度器，活儿交给 [Dispatchers.Default]：测「这段工作确实切到了注入的调度器上」。 */
class CountingDispatcher : CoroutineDispatcher() {
    private val count = AtomicInteger()

    /** 到目前为止被调度的次数。 */
    val dispatches: Int get() = count.get()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        count.incrementAndGet()
        Dispatchers.Default.dispatch(context, block)
    }
}
