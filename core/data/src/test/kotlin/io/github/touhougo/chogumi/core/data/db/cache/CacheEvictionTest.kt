package io.github.touhougo.chogumi.core.data.db.cache

import kotlin.test.Test
import kotlin.test.assertEquals

class CacheEvictionTest {

    private val policy = CacheEvictionPolicy(maxAgeMillis = 100, maxRows = 2)
    private val now = 1_000L

    private fun victims(vararg rows: Pair<Int, Long>, protected: Set<Int> = emptySet()) =
        cacheEvictionVictims(rows.map { CachedRowAge(it.first, it.second) }, { it in protected }, now, policy)

    @Test
    fun `超过保留期的删除，未到期的保留`() =
        assertEquals(setOf(1), victims(1 to 900L, 2 to 901L))

    @Test
    fun `写入时刻未知按已过期处理`() =
        assertEquals(setOf(1), victims(1 to 0L, 2 to 990L))

    @Test
    fun `受保护的行再旧也不删`() =
        assertEquals(emptySet(), victims(1 to 1L, protected = setOf(1)))

    @Test
    fun `超出上限时从最久没用到的开始删`() =
        assertEquals(setOf(1), victims(1 to 950L, 2 to 960L, 3 to 970L))

    @Test
    fun `受保护的行不占上限名额`() =
        assertEquals(emptySet(), victims(1 to 950L, 2 to 960L, 3 to 970L, 4 to 980L, protected = setOf(3, 4)))

    @Test
    fun `旧账号到期才清_当前账号与时刻未知的不清`() = assertEquals(
        setOf(2),
        inactiveAccounts(mapOf(1 to 1L, 2 to 900L, 3 to 901L, 4 to 0L), currentAccountId = 1, nowMillis = now, maxIdleMillis = 100),
    )
}
