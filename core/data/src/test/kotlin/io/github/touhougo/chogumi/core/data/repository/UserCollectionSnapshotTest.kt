package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** 全量同步替换一个分区（`replaceSnapshot`，生产的默认实现）：收藏多到超过旧系统 SQLite 的宿主参数上限时照样删对。 */
class UserCollectionSnapshotTest {

    private fun row(subjectId: Int, accountId: Int = ACCOUNT, subjectType: Int = ANIME, type: Int = DONE) =
        UserCollectionEntity(subjectId = subjectId, subjectType = subjectType, type = type, updatedAt = 1, accountId = accountId)

    @Test
    fun `一类收藏上千部_全量替换只删掉这次没取回的_别的分区与账号不动`() = runTest {
        val dao = FakeUserCollectionDao()
        val otherType = row(subjectId = 90_001, subjectType = BOOK)
        val otherAccount = row(subjectId = 7, accountId = OTHER_ACCOUNT)
        dao.upsertAll(listOf(otherType, otherAccount))

        dao.replaceSnapshot(ACCOUNT, ANIME, null, (1..1500).map { row(it) })
        dao.replaceSnapshot(ACCOUNT, ANIME, null, (1..1000).map { row(it) })

        val anime = dao.store.values.filter { it.accountId == ACCOUNT && it.subjectType == ANIME }
        assertEquals((1..1000).toSet(), anime.map { it.subjectId }.toSet())
        assertEquals(setOf(otherType, otherAccount), dao.store.values.filterNot { it in anime }.toSet())
    }

    @Test
    fun `取回为空_清空这一分区`() = runTest {
        val dao = FakeUserCollectionDao()
        dao.replaceSnapshot(ACCOUNT, ANIME, DONE, (1..1200).map { row(it) } + row(5000, type = WISH))

        dao.replaceSnapshot(ACCOUNT, ANIME, DONE, emptyList())

        assertEquals(listOf(5000), dao.store.values.map { it.subjectId }, "只清看过，想看那一行不在这个分区里")
    }

    private companion object {
        const val ACCOUNT = 1
        const val OTHER_ACCOUNT = 2
        const val BOOK = 1
        const val ANIME = 2
        const val WISH = 1
        const val DONE = 2
    }
}
