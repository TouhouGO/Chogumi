package io.github.touhougo.chogumi.feature.monodetails

import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.feature.monodetails.ui.workRowKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkRowKeysTest {
    private fun work(id: Int) = MonoWorkBo(id = id, name = "w$id", nameCn = "", image = null, subjectType = SubjectType.ANIME)

    @Test
    fun `key 只看作品 id，换排序同一部作品的 key 不变`() {
        val a = listOf(work(1), work(2), work(3))
        val keysByWork = a.zip(workRowKeys(a)).toMap()
        val reversed = a.reversed()
        assertEquals(reversed.map { keysByWork.getValue(it) }, workRowKeys(reversed))
    }

    @Test
    fun `同一部作品出现多次时按出现次序编码，key 不重复`() {
        assertEquals(listOf("6:work-7:1", "6:work-8:1", "6:work-7:2"), workRowKeys(listOf(work(7), work(8), work(7))))
    }
}
