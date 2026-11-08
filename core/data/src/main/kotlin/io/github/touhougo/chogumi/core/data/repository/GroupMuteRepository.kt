package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.community.MutedGroupDao
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupEntity
import io.github.touhougo.chogumi.core.data.token.ViewerSource
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * 屏蔽的小组（本机名单，不同步到服务端，按账号分）：社区 tab 的小组页和超展开按它过滤（`withoutMutedGroups`），
 * 设置里的屏蔽列表逐个取消。换号后名单跟着换。
 */
class GroupMuteRepository(
    private val dao: MutedGroupDao,
    private val viewer: ViewerSource,
) {

    /** 当前账号屏蔽的全部小组，最近屏蔽的在前。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<Load<List<GroupSummaryBo>>> =
        viewer.viewerIds.flatMapLatest { accountId ->
            dao.observeAll(accountId).map { rows ->
                Load.Ready(rows.map { GroupSummaryBo(name = it.name, title = it.title, icon = it.icon, members = it.members) })
            }.failuresAsLoad()
        }

    suspend fun mute(group: GroupSummaryBo, now: Long) {
        val accountId = viewer.viewerIds.first()
        dao.upsert(MutedGroupEntity(accountId, group.name, group.title, group.icon, group.members, mutedAt = now))
    }

    suspend fun unmute(name: String) = dao.delete(viewer.viewerIds.first(), name)
}
