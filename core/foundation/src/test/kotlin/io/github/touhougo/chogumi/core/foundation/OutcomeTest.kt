package io.github.touhougo.chogumi.core.foundation

import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomeTest {

    @Test
    fun `map 只换成功的值 失败原样传下去`() {
        assertEquals(Outcome.Success(4), Outcome.Success(2).map { it * 2 })
        assertEquals(Outcome.Failed(Failure.Offline), (Outcome.Failed(Failure.Offline) as Outcome<Int>).map { it * 2 })
    }
}
