package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectCollectionCountTest {

    @Test
    fun `五态各取各的计数，未知为 0`() {
        val subject = SubjectBo(
            id = 1, type = SubjectType.ANIME, name = "", nameCn = "",
            collectionWish = 1, collectionCollect = 2, collectionDoing = 3, collectionOnHold = 4, collectionDropped = 5,
        )
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            CollectionType.entries.map { subject.collectionCountOf(it) },
        )
    }
}
