package io.github.touhougo.chogumi.core.data.util

import io.github.touhougo.chogumi.core.data.token.SessionChangedException
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest

/** 本机存储异常只有一种分类：一次性存取与观察流对同一个异常给出同一个失败。 */
class StorageFailureTest {

    private val cases = listOf(
        IOException("DataStore 读写失败") to Failure.Storage,
        IllegalStateException("Room 连接已关闭") to Failure.Storage,
        FailureException(Failure.Http(404)) to Failure.Http(404),
        SessionChangedException() to Failure.SessionChanged,
    )

    @Test
    fun `一次性存取与观察流对同一个异常给出同一个失败`() = runTest {
        for ((error, expected) in cases) {
            val once = storageOutcomeOf<Unit> { throw error }
            val observed = flow<Unit> { throw error }.storageAsLoad().last()
            assertEquals(Outcome.Failed(expected), once, "一次性存取：$error")
            assertEquals(Load.Failed(expected), observed, "观察流：$error")
        }
    }
}
