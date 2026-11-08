package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * 收藏面板的数据端（core:data 的实现，全 App 一份）：详情页、进度页、首页、有海报的页面打开的收藏面板，
 * 以及不开面板的「标为看过」，都只经这里取一部作品、存一张表。
 */
interface CollectionEditing {
    /** 面板要编辑的那一部：作品、收藏、章节与逐集状态。 */
    fun observeWork(subjectId: Int): Flow<Load<CollectionWork>>

    /** 移除一部作品的收藏，与同一部的其他写入串行。 */
    suspend fun remove(subjectId: Int): Outcome<Unit>

    /** 保存面板上的修改：与这一部的其他写入（长按标记、章节进度）按先后串行。 */
    suspend fun save(work: CollectionWork, edits: CollectionEdits): CollectionSaveResult
}
