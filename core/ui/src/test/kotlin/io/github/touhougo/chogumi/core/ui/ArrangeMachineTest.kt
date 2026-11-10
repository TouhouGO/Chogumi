package io.github.touhougo.chogumi.core.ui

import io.github.touhougo.chogumi.core.ui.arrange.ArrangeMachine
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeSheet
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrangeMachineTest {
    private var state = ArrangeState<String>()
    private val machine = ArrangeMachine(read = { state }, write = { state = it(state) })

    @Test
    fun `旧保存结果不能关闭后来开的草稿且重复保存被挡`() {
        machine.enter()
        machine.edit("甲")
        val first = machine.beginSave()!!
        assertNull(machine.beginSave())
        machine.edit("乙")
        assertFalse(machine.completeSave(first, true))
        assertEquals("乙", state.editor)
        val second = machine.beginSave()!!
        assertTrue(machine.completeSave(second, false))
        assertTrue(state.closing.isEmpty())
        assertEquals("乙", state.editor)
        val retry = machine.beginSave()!!
        assertTrue(machine.completeSave(retry, true))
        assertEquals(setOf(ArrangeSheet.EDITOR), state.closing)
        machine.editorClosed()
        assertNull(state.editor)
        assertTrue(state.active)
    }

    @Test
    fun `从添加模块新建保存成功后两层一起收起_回到整理页`() {
        machine.enter()
        machine.openAdding()
        machine.edit("新模块")
        assertTrue(machine.completeSave(machine.beginSave()!!, true))
        assertTrue(state.hides(ArrangeSheet.EDITOR))
        assertTrue(state.hides(ArrangeSheet.ADDING), "保存成功不停在「添加模块」")
        machine.editorClosed()
        machine.addingClosed()
        assertNull(state.editor)
        assertFalse(state.adding)
        assertTrue(state.closing.isEmpty())
        assertTrue(state.active)
    }

    @Test
    fun `从添加模块新建保存失败两层都留着`() {
        machine.enter()
        machine.openAdding()
        machine.edit("新模块")
        assertTrue(machine.completeSave(machine.beginSave()!!, false))
        assertTrue(state.closing.isEmpty())
        assertEquals("新模块", state.editor)
        assertTrue(state.adding)
    }

    @Test
    fun `新建时下滑关掉编辑面板只回到添加模块`() {
        machine.enter()
        machine.openAdding()
        machine.edit("新模块")
        machine.editorClosed()
        assertNull(state.editor)
        assertTrue(state.adding)
        assertFalse(state.hides(ArrangeSheet.ADDING))
    }

    @Test
    fun `从整理页编辑已有模块保存只收编辑面板`() {
        machine.enter()
        machine.edit("已有模块")
        assertTrue(machine.completeSave(machine.beginSave()!!, true))
        assertEquals(setOf(ArrangeSheet.EDITOR), state.closing)
        assertFalse(state.adding)
    }
}
