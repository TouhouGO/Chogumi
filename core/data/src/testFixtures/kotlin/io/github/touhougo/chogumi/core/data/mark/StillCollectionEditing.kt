package io.github.touhougo.chogumi.core.data.mark

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 页面 ViewModel 单测里的收藏面板端：取不到作品、不保存。测的不是收藏面板的页面只需要把它交给构造参数；
 * 面板的取数与保存用真的实现（`CollectionEditingImpl`），见详情页、进度页、首页的 ViewModel 单测与 `CollectionMarkCoordinatorTest`。
 */
class StillCollectionEditing : CollectionEditing {
    override suspend fun remove(subjectId: Int): Outcome<Unit> = error("此测试不经收藏面板移除")

    override fun observeWork(subjectId: Int): Flow<Load<CollectionWork>> = emptyFlow()

    override suspend fun save(work: CollectionWork, edits: CollectionEdits): CollectionSaveResult =
        error("页面单测不经收藏面板保存")
}
