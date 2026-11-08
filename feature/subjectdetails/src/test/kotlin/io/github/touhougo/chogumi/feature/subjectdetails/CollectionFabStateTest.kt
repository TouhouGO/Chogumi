package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetState
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetWrite
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FAB 四态投影的守卫测试。
 *
 * [CollectionFabState] 不是新真源：它由收藏这一区块（[SubjectDetailsUiState.collection]）与面板的写入在途标记投影而来。
 * 这里钉住三件事：
 * 1. 收藏的三拍各对应一态：等待 → LOADING（FAB 可见、不开面板），就绪（含 404 确认没收藏）→ READY，取不到 → SETTLED_NO_DATA；
 * 2. SAVING 优先：保存或移除请求在途时不管收藏处于哪一拍都是 SAVING；
 * 3. 在途标记归位后立刻回到收藏决定的那一态。
 */
class CollectionFabStateTest {

    private val doing = UserCollectionBo(subjectId = 8, subjectType = SubjectType.ANIME, type = CollectionType.DOING)

    private val loads: List<Load<UserCollectionBo?>> = listOf(
        Load.Pending,
        Load.Ready(doing),
        Load.Ready(null),
        Load.Failed(Failure.Offline),
    )

    @Test
    fun `收藏的每一拍各对应一态`() {
        assertEquals(CollectionFabState.LOADING, SubjectDetailsUiState(collection = Load.Pending).fabState)
        assertEquals(CollectionFabState.READY, SubjectDetailsUiState(collection = Load.Ready(doing)).fabState)
        assertEquals(CollectionFabState.READY, SubjectDetailsUiState(collection = Load.Ready(null)).fabState, "404 确认没收藏：以新建打开")
        assertEquals(CollectionFabState.SETTLED_NO_DATA, SubjectDetailsUiState(collection = Load.Failed(Failure.Offline)).fabState)
    }

    @Test
    fun `写入在途时一律为SAVING 归位后回到收藏决定的态`() {
        loads.forEach { load ->
            val idle = SubjectDetailsUiState(collection = load)
            CollectionSheetWrite.entries.forEach { writing ->
                val busy = idle.copy(collectionSheet = CollectionSheetState(writing = writing))
                assertEquals(CollectionFabState.SAVING, busy.fabState, "收藏为 $load 时 $writing 在途必须投影为 SAVING")
                assertEquals(idle.fabState, busy.copy(collectionSheet = CollectionSheetState()).fabState, "在途标记归位后不得滞留 SAVING")
            }
        }
    }
}
