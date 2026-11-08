package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionProgress
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget

/**
 * 面板上正在改的表：没保存的修改（[edits]，不含吐槽）、吐槽与新标签的输入框。吐槽留在输入框自己的状态里，
 * 不经页面状态绕一圈（输入法正在组词时被回写会打断组词）；交出去时（[snapshot]）才并进修改里。
 *
 * @param savedComment 面板打开时已保存的吐槽：输入框和它一样就算没改。
 */
@Stable
internal class CollectionFormState(initial: CollectionEdits, private val savedComment: String, tagDraft: String = "") {
    var edits by mutableStateOf(initial.copy(comment = null))
        private set
    val comment = TextFieldState(initial.comment ?: savedComment)
    val tagInput = TextFieldState(tagDraft)

    fun edit(change: CollectionEdits.() -> CollectionEdits) {
        edits = edits.change()
    }

    fun snapshot(): CollectionEdits = edits.copy(comment = comment.text.toString().takeIf { it != savedComment })

    /** 放弃没保存的修改：回到已保存的样子。 */
    fun discard() {
        edits = CollectionEdits()
        comment.setTextAndPlaceCursorAtEnd(savedComment)
        tagInput.clearText()
    }

    companion object {
        /** 转屏、进程被回收后原样恢复：修改逐项拆成能存进 Bundle 的值。 */
        val Saver: Saver<CollectionFormState, Any> = listSaver(
            save = { state ->
                val edits = state.edits
                listOf(
                    edits.type?.value,
                    edits.rate,
                    edits.progress?.let(::progressToList),
                    edits.tags?.let { ArrayList(it) },
                    state.comment.text.toString(),
                    edits.isPrivate,
                    edits.finishAll,
                    state.savedComment,
                    state.tagInput.text.toString(),
                )
            },
            restore = { saved ->
                CollectionFormState(
                    initial = CollectionEdits(
                        type = (saved[0] as Int?)?.let(CollectionType::from),
                        rate = saved[1] as Int?,
                        progress = (saved[2] as List<*>?)?.let(::progressFromList),
                        tags = (saved[3] as List<*>?)?.map { it as String },
                        comment = saved[4] as String,
                        isPrivate = saved[5] as Boolean?,
                        finishAll = saved[6] as Boolean,
                    ),
                    savedComment = saved[7] as String,
                    tagDraft = saved[8] as String,
                )
            },
        )

        private fun progressToList(progress: CollectionProgress): ArrayList<Any> = when (progress) {
            CollectionProgress.None -> arrayListOf(NONE)
            is CollectionProgress.Episodes -> when (val upTo = progress.upTo) {
                EpisodeProgressTarget.Empty -> arrayListOf(EPISODES_EMPTY)
                is EpisodeProgressTarget.Episode -> arrayListOf(EPISODES, upTo.id, upTo.sort)
            }
            is CollectionProgress.Book -> arrayListOf(BOOK, progress.chapters, progress.volumes)
        }

        private fun progressFromList(saved: List<*>): CollectionProgress = when (saved[0]) {
            EPISODES -> CollectionProgress.Episodes(EpisodeProgressTarget.Episode(saved[1] as Int, saved[2] as Double))
            EPISODES_EMPTY -> CollectionProgress.Episodes(EpisodeProgressTarget.Empty)
            BOOK -> CollectionProgress.Book(saved[1] as Int, saved[2] as Int)
            else -> CollectionProgress.None
        }

        private const val NONE = 0
        private const val EPISODES = 1
        private const val BOOK = 2
        private const val EPISODES_EMPTY = 3
    }
}
