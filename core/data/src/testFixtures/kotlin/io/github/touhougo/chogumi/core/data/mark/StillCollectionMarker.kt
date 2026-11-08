package io.github.touhougo.chogumi.core.data.mark

import io.github.touhougo.chogumi.core.model.CollectionMarkFailure
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.MarkOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 页面 ViewModel 单测里的长按标记端：没有收藏、不提交、不失败。测的不是长按标记的页面只需要把它交给构造参数；
 * 标记本身的行为见 `CollectionMarkCoordinatorTest`。
 */
class StillCollectionMarker : CollectionMarker {
    override val marks: StateFlow<CollectionMarks> = MutableStateFlow(CollectionMarks())

    override val failures: Flow<CollectionMarkFailure> = emptyFlow()

    override fun mark(origin: MarkOrigin, subjectId: Int, type: CollectionType) = Unit

    override fun undo(subjectId: Int) = Unit
}
