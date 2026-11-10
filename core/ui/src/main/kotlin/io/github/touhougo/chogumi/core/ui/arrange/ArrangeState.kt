package io.github.touhougo.chogumi.core.ui.arrange

/** 两类整理共用的面板身份。 */
enum class ArrangeSheet { EDITOR, ADDING }

/** 整理列表、编辑草稿和在途保存的唯一状态形状；领域草稿通过泛型保留。 */
data class ArrangeState<E>(
    val active: Boolean = false,
    val editor: E? = null,
    val adding: Boolean = false,
    val closing: ArrangeSheet? = null,
    val saving: Boolean = false,
)

/** 通用状态机，关闭和换面板递增代次，旧保存结果不能关掉新草稿。 */
class ArrangeMachine<E>(private val read: () -> ArrangeState<E>, private val write: ((ArrangeState<E>) -> ArrangeState<E>) -> Unit) {
    private var round = 0L
    fun enter() = write { it.copy(active = true) }
    fun exit() { ++round; write { ArrangeState() } }
    fun edit(editor: E) { ++round; write { it.copy(editor = editor, closing = null, saving = false) } }
    fun openAdding() = write { it.copy(adding = true, closing = null) }
    fun finishAdding() = write { it.copy(closing = ArrangeSheet.ADDING) }
    fun addingClosed() = write { it.copy(adding = false, closing = it.closing.takeUnless { sheet -> sheet == ArrangeSheet.ADDING }) }
    fun editorClosed() { ++round; write { it.copy(editor = null, saving = false, closing = it.closing.takeUnless { sheet -> sheet == ArrangeSheet.EDITOR }) } }
    fun beginSave(): Long? {
        if (read().saving || read().editor == null || read().closing == ArrangeSheet.EDITOR) return null
        write { it.copy(saving = true) }
        return round
    }
    fun completeSave(token: Long, success: Boolean): Boolean {
        if (token != round) return false
        write { it.copy(saving = false, closing = if (success) ArrangeSheet.EDITOR else null) }
        return true
    }
}
