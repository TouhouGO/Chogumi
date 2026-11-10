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
    @Test
    fun `旧保存结果不能关闭后来开的草稿且重复保存被挡`() {
        var state = ArrangeState<String>()
        val machine = ArrangeMachine(read = { state }, write = { state = it(state) })
        machine.enter()
        machine.edit("甲")
        val first = machine.beginSave()!!
        assertNull(machine.beginSave())
        machine.edit("乙")
        assertFalse(machine.completeSave(first, true))
        assertEquals("乙", state.editor)
        val second = machine.beginSave()!!
        assertTrue(machine.completeSave(second, false))
        assertNull(state.closing)
        assertEquals("乙", state.editor)
        val retry = machine.beginSave()!!
        assertTrue(machine.completeSave(retry, true))
        assertEquals(ArrangeSheet.EDITOR, state.closing)
        machine.editorClosed()
        assertNull(state.editor)
        assertTrue(state.active)
    }
}
