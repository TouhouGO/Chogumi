package io.github.touhougo.chogumi.core.ui.arrange



/** 两类整理共用的面板身份。 */
enum class ArrangeSheet { EDITOR, ADDING }

/**
 * 整理列表、编辑草稿和在途保存的唯一状态形状；领域草稿通过泛型保留。
 * [closing] 是正在滑下去的面板：保存成功时可能是两层一起。
 */
data class ArrangeState<E>(
    val active: Boolean = false,
    val editor: E? = null,
    val adding: Boolean = false,
    val closing: Set<ArrangeSheet> = emptySet(),
    val saving: Boolean = false,
) {
    /** [sheet] 这一层被要求收起（交给面板的 `hideRequested`）。 */
    fun hides(sheet: ArrangeSheet): Boolean = sheet in closing
}

/**
 * 通用状态机，关闭和换面板递增代次，旧保存结果不能关掉新草稿。
 * 三层：整理页本身、「添加模块」面板、编辑面板。编辑面板保存成功总是回到整理页本身：从「添加模块」里新建的，
 * 两层一起收起，页面上的列表就是保存成功的样子；下滑、点遮罩或返回只关最上面一层。
 */
class ArrangeMachine<E>(private val read: () -> ArrangeState<E>, private val write: ((ArrangeState<E>) -> ArrangeState<E>) -> Unit) {
    private var round = 0L
    fun enter() = write { it.copy(active = true) }
    fun exit() { ++round; write { ArrangeState() } }
    fun edit(editor: E) { ++round; write { it.copy(editor = editor, closing = emptySet(), saving = false) } }
    fun openAdding() = write { it.copy(adding = true, closing = emptySet()) }
    fun finishAdding() = write { it.copy(closing = it.closing + ArrangeSheet.ADDING) }
    fun addingClosed() = write { it.copy(adding = false, closing = it.closing - ArrangeSheet.ADDING) }
    fun editorClosed() { ++round; write { it.copy(editor = null, saving = false, closing = it.closing - ArrangeSheet.EDITOR) } }
    fun beginSave(): Long? {
        if (read().saving || read().editor == null || read().hides(ArrangeSheet.EDITOR)) return null
        write { it.copy(saving = true) }
        return round
    }
    fun completeSave(token: Long, success: Boolean): Boolean {
        if (token != round) return false
        write { state ->
            val closing = when {
                !success -> emptySet()
                state.adding -> setOf(ArrangeSheet.EDITOR, ArrangeSheet.ADDING)
                else -> setOf(ArrangeSheet.EDITOR)
            }
            state.copy(saving = false, closing = closing)
        }
        return true
    }
}
