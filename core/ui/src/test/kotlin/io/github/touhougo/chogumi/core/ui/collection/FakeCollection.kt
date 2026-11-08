package io.github.touhougo.chogumi.core.ui.collection

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionMarkFailure
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.MarkOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** 记下页面交给数据端的每一笔；[marks] 由用例直接给。 */
internal class RecordingMarker : CollectionMarker {
    override val marks = MutableStateFlow(CollectionMarks())
    override val failures: Flow<CollectionMarkFailure> = emptyFlow()
    val marked = mutableListOf<Pair<Int, CollectionType>>()
    val undone = mutableListOf<Int>()

    override fun mark(origin: MarkOrigin, subjectId: Int, type: CollectionType) {
        marked += subjectId to type
    }

    override fun undo(subjectId: Int) {
        undone += subjectId
    }
}

/** 记下「管理」去取的是哪一部；海报长按不经它移除或保存。 */
internal class RecordingEditing : CollectionEditing {
    val fetched = mutableListOf<Int>()

    override fun observeWork(subjectId: Int): Flow<Load<CollectionWork>> {
        fetched += subjectId
        return emptyFlow()
    }

    override suspend fun remove(subjectId: Int): Outcome<Unit> = error("海报长按不经收藏面板移除")

    override suspend fun save(work: CollectionWork, edits: CollectionEdits): CollectionSaveResult = error("海报长按不经收藏面板保存")
}
