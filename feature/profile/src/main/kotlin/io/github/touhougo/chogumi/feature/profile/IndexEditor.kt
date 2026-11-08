package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.INDEX_TITLE_MAX_LENGTH
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.model.IndexDetailsBo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 目录名称输入的唯一规则，新建 / 编辑面板与收录面板里的就地新建共用：框里最多 [INDEX_TITLE_MAX_LENGTH] 字，
 * 多打的不收（[accepts]）；写了非空白的名称才能建、能存（[isComplete]）。
 */
internal object IndexTitleRule {
    fun accepts(text: String): Boolean = text.length <= INDEX_TITLE_MAX_LENGTH

    fun isComplete(text: String): Boolean = text.isNotBlank() && accepts(text)
}

/** 新建 / 编辑目录面板里填的内容：名称、介绍、是否私密（只有自己看得到）。 */
data class IndexDraft(val title: String = "", val description: String = "", val isPrivate: Boolean = false)

/**
 * 新建 / 编辑目录面板的状态（面板关着时页面状态里是 null）。
 *
 * @property indexId 编辑哪个目录；null 是新建。
 * @property original 打开时的内容：编辑时一点没改不能保存。
 * @property saved 写成功：面板滑下去，滑完才回到关着（写失败时面板与填的内容都留着）。
 */
data class IndexEditorState(
    val indexId: Int?,
    val original: IndexDraft,
    val draft: IndexDraft = original,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val canSave: Boolean
        get() = !saving && !saved && IndexTitleRule.isComplete(draft.title) && draft != original
}

/**
 * 新建 / 编辑目录的唯一实现：目录列表页的「新建」与目录页 ⋮ 的「编辑」共用，各自的 ViewModel 持有一个。
 * 面板状态经 [read] / [write] 存在 ViewModel 的页面状态里；失败经 ViewModel 自己的 [failures] 提示，填的内容留着。
 *
 * [afterSave] 是写成功后 ViewModel 自己要做的事（重取目录头部），面板滑走了也照做；
 * [save] 的 `onSaved` 是界面的事（进入新目录），只在面板还是这一次打开的那个时才做。
 */
class IndexEditor(
    private val scope: CoroutineScope,
    private val repository: IndexRepository,
    private val failures: FailureNotices,
    private val read: () -> IndexEditorState?,
    private val write: ((IndexEditorState?) -> IndexEditorState?) -> Unit,
    private val afterSave: (indexId: Int) -> Unit = {},
) {
    /** 每次打开、关闭加一：旧一次的写入结果不改后来打开的面板。 */
    private var generation = 0L

    fun openNew() = open(IndexEditorState(indexId = null, original = IndexDraft()))

    fun openEdit(details: IndexDetailsBo) =
        open(IndexEditorState(indexId = details.id, original = IndexDraft(details.title, details.description, details.isPrivate)))

    private fun open(state: IndexEditorState) {
        generation++
        write { state }
    }

    /** 面板里改了内容；在途或已写成功时不收，名称超过上限的输入不收（[IndexTitleRule]）。 */
    fun change(draft: IndexDraft) = write { state ->
        state?.takeUnless { it.saving || it.saved || !IndexTitleRule.accepts(draft.title) }?.copy(draft = draft) ?: state
    }

    /** 面板滑走（取消，或写成功后滑完）：回到关着。 */
    fun close() {
        generation++
        write { null }
    }

    fun save(onSaved: (indexId: Int) -> Unit = {}) {
        val current = read() ?: return
        if (!current.canSave) return
        val owner = generation
        val draft = current.draft
        write { it?.copy(saving = true) }
        scope.launch {
            val result = when (val id = current.indexId) {
                null -> repository.createIndex(draft.title, draft.description, draft.isPrivate)
                else -> repository.updateIndex(id, draft.title, draft.description, draft.isPrivate).map { id }
            }
            when (result) {
                is Outcome.Success -> {
                    afterSave(result.value)
                    if (owner != generation) return@launch
                    write { it?.copy(saving = false, saved = true) }
                    onSaved(result.value)
                }
                is Outcome.Failed -> {
                    if (owner == generation) write { it?.copy(saving = false) }
                    failures.post(result.failure)
                }
            }
        }
    }
}
