package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionModifyTest {
    private val original = UserCollectionBo(
        subjectId = 1, subjectType = SubjectType.ANIME, type = CollectionType.DOING,
        rate = 7, tags = listOf("原标签"), epStatus = 5, comment = "原吐槽", isPrivate = false,
    )
    private val wholeForm = CollectionModify(
        type = CollectionType.DOING, rate = 7, epStatus = 5, volStatus = 0,
        comment = "原吐槽", private = false, tags = listOf("原标签"),
    )

    @Test
    fun `只改状态时不带上没动过的吐槽与评分`() {
        assertEquals(
            CollectionModify(type = CollectionType.DONE),
            wholeForm.copy(type = CollectionType.DONE).changedFrom(original),
        )
    }

    @Test
    fun `什么都没改得到空的部分更新`() {
        assertEquals(CollectionModify(), wholeForm.changedFrom(original))
    }

    @Test
    fun `吐槽只差首尾空白不算改动，清空吐槽算改动`() {
        assertEquals(CollectionModify(), wholeForm.copy(comment = " 原吐槽\n").changedFrom(original))
        assertEquals(CollectionModify(comment = ""), wholeForm.copy(comment = "").changedFrom(original))
    }

    @Test
    fun `尚未收藏时整张表单发出，但为 0 的进度不发`() {
        // wholeForm 的卷数进度是 0：新建时不发，免得服务端另记一条「读到 0 卷」的动态
        assertEquals(wholeForm.copy(volStatus = null), wholeForm.changedFrom(null))
    }

    @Test
    fun `新收藏书籍且进度都是 0 时不带任何进度字段`() {
        val newBook = CollectionModify(type = CollectionType.DOING, rate = 0, epStatus = 0, volStatus = 0, comment = "", private = false, tags = emptyList())
        val sent = newBook.changedFrom(null)
        assertEquals(null, sent.epStatus)
        assertEquals(null, sent.volStatus)
        assertEquals(CollectionType.DOING, sent.type)
    }
}
