package io.github.touhougo.chogumi.core.ui.collection

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.MarkOrigin
import io.github.touhougo.chogumi.core.res.R
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 海报长按标记在页面状态里的一份，只由 [PosterMarkController] 改：海报上「我」的收藏状态与待提交的那一笔（[marks]），
 * 以及环上「管理」打开的收藏面板（[sheet]）。
 *
 * @property today 业务日（[io.github.touhougo.chogumi.core.foundation.AppTime.today]）：面板进度尺上哪些集还没播。
 *   控制器建起来就填上，之前为 null（面板这时也开不了）。
 */
data class PosterMarkState(
    val marks: CollectionMarks = CollectionMarks(),
    val sheet: CollectionSheetState = CollectionSheetState(),
    val today: LocalDate? = null,
)

/**
 * 页面里的海报长按标记——有海报的页面（探索、搜索、每日放送、详情页关联、角色人物、资料页）各一份，与 [CollectionSheetController]
 * 同一个用法：状态经 [read] / [write] 读写页面状态里的 [PosterMarkState]，ViewModel 的状态仍只有一份。
 *
 * 标记与提交都交给 [marker]（全 App 一个协调器）；本页发起的标记提交失败时，经本页的 [failures] 提示一次
 * （「收藏保存失败：原因」，与收藏面板保存失败同一句），别的页面发起的不在这里提示。「管理」打开与列表行长按同一个收藏面板，
 * 取数与保存同经 [editing]；[today] 跟着写进页面状态。
 */
class PosterMarkController(
    scope: CoroutineScope,
    private val read: () -> PosterMarkState,
    private val write: ((PosterMarkState) -> PosterMarkState) -> Unit,
    failures: FailureNotices,
    private val marker: CollectionMarker,
    editing: CollectionEditing,
    today: StateFlow<LocalDate>,
) {
    private val origin = MarkOrigin()

    private val sheet = CollectionSheetController(
        scope = scope,
        read = { read().sheet },
        write = { change -> write { it.copy(sheet = change(it.sheet)) } },
        failures = failures,
        source = CollectionWorkSource.Fetch(editing::observeWork),
        save = editing::save,
        remove = editing::remove,
    )

    init {
        write { it.copy(today = today.value) }
        scope.launch { today.collect { day -> write { it.copy(today = day) } } }
        scope.launch { marker.marks.collect { marks -> write { it.copy(marks = marks) } } }
        scope.launch {
            marker.failures.filter { it.origin === origin }.collect { failures.post(R.string.collection_save_failed, it.failure) }
        }
    }

    /** 把 [subjectId] 标为 [type]（进 4 秒可撤销的窗口）。 */
    fun mark(subjectId: Int, type: CollectionType) = marker.mark(origin, subjectId, type)

    /** 撤销 [subjectId] 待提交的那一笔。 */
    fun undo(subjectId: Int) = marker.undo(subjectId)

    /** 环上的「管理」：打开 [subjectId] 的收藏面板。 */
    fun manage(subjectId: Int) = sheet.open(subjectId)

    fun removeSheet() {
        read().sheet.subjectId?.let(marker::undo)
        sheet.remove()
    }

    fun saveSheet(edits: CollectionEdits) = sheet.save(edits)

    fun closeSheet(edits: CollectionEdits) = sheet.close(edits)

    fun retrySheetEpisodes() = sheet.retryEpisodes()
}
