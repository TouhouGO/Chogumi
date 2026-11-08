package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.model.ShelfEntry
import io.github.touhougo.chogumi.core.model.SubjectType

/** 页面测试用的整理动作：什么都不做，单测与设备测试里不测整理的页面共用。 */
object NoArrangeActions : ArrangeListActions {
    override fun enter() = Unit
    override fun exit() = Unit
    override fun openAdding() = Unit
    override fun move(type: SubjectType, ids: List<String>) = Unit
    override fun remove(type: SubjectType, id: String) = Unit
    override fun openEditor(type: SubjectType, entry: ShelfEntry) = Unit
}
