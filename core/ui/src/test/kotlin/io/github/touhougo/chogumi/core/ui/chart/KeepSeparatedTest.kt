package io.github.touhougo.chogumi.core.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Test

/** [keepSeparated]：先到先得，互相压住（含间隔）的后来者被丢掉。 */
class KeepSeparatedTest {

    @Test
    fun `互不相交的标签全留`() {
        assertEquals(listOf(true, true, true), keepSeparated(listOf(0f..10f, 20f..30f, 40f..50f), gap = 4f))
    }

    @Test
    fun `重要的先留，压住它的后来者被丢`() {
        assertEquals(listOf(true, false, true), keepSeparated(listOf(0f..20f, 15f..30f, 40f..50f), gap = 4f))
    }

    @Test
    fun `间隔不足 gap 也算压住，恰好够就留`() {
        assertEquals(listOf(true, false), keepSeparated(listOf(0f..10f, 12f..20f), gap = 4f))
        assertEquals(listOf(true, true), keepSeparated(listOf(0f..10f, 14f..20f), gap = 4f))
    }

    @Test
    fun `被丢掉的标签不再占位`() {
        assertEquals(listOf(true, false, true), keepSeparated(listOf(0f..10f, 5f..40f, 20f..30f), gap = 4f))
    }
}
