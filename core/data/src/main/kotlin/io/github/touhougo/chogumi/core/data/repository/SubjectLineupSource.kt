package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.model.SubjectLineup
import io.github.touhougo.chogumi.core.model.SubjectType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore

/**
 * 生涯「阵容」的来源：各部作品的角色与制作人员，存在条目详情页的同一份子模块缓存里（[SubjectRepository]）。
 * 本人统计读已缓存的，同步时只给还没缓存的作品补拉。
 */
interface SubjectLineupSource {
    /** 已缓存的各部阵容（[subjects] 是 `subjectId → 类型`）；角色或制作人员还没缓存、或类型不算阵容的作品不在结果里。 */
    suspend fun cachedLineups(subjects: Map<Int, SubjectType>): Map<Int, SubjectLineup>

    /** [subjectIds] 里角色或制作人员还没缓存的那些。 */
    suspend fun missingLineups(subjectIds: List<Int>): List<Int>

    /** 拉一部作品的角色与制作人员进缓存（与条目详情页同一条路径）；失败的不落缓存，下次还在 [missingLineups] 里。 */
    suspend fun fetchLineup(subjectId: Int, session: AccountSession, permits: Semaphore)

    /** 缓存有变化就发一拍：统计据此重算阵容。 */
    fun observeLineupChanges(): Flow<Unit>
}
