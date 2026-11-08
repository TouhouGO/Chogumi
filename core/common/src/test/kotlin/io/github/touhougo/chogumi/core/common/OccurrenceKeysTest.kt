package io.github.touhougo.chogumi.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OccurrenceKeysTest {
    @Test
    fun `重复的值按出现次序编码`() {
        assertEquals(listOf("1:a:1", "1:b:1", "1:a:2", "1:a:3"), occurrenceKeys(listOf("a", "b", "a", "a")) { it })
    }

    @Test
    fun `任意文本与重复值组合的 key 互不碰撞`() {
        val values = listOf("", ":", "#", "a", "a#2", "1:a:2", "中文😀", "x".repeat(10_000))
        val keys = occurrenceKeys(values + values) { it }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `不同值重排或筛选时 key 不变`() {
        val values = listOf("a", "a#2", "", ":", "中文😀")
        val keysByValue = values.zip(occurrenceKeys(values) { it }).toMap()
        val selected = listOf("中文😀", "a#2", "a")
        assertEquals(selected.map { keysByValue.getValue(it) }, occurrenceKeys(selected) { it })
    }

    @Test
    fun `同值按出现次序区分_其他值不影响其 key`() {
        val keys = occurrenceKeys(listOf("a", "a", "a")) { it }
        val interleaved = occurrenceKeys(listOf("a", "b", "a", "a")) { it }
        assertEquals(keys, listOf(interleaved[0], interleaved[2], interleaved[3]))
        assertNotEquals(keys[0], keys[1])
        assertNotEquals(keys[1], keys[2])
    }

    @Test
    fun `文本相同的不同类型值按文本合并_按出现次序区分`() {
        assertEquals(listOf("1:1:1", "1:1:2"), occurrenceKeys(listOf<Any>(1, "1")) { it })
    }
}
