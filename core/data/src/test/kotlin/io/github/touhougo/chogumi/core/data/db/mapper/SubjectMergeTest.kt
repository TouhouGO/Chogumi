package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectInfoboxBo
import io.github.touhougo.chogumi.core.model.SubjectType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SubjectMergeTest {
    @Test
    fun `摘要合并只记最近用到的时刻，不延长详情时间`() {
        val detail = SubjectEntity(id = 8, type = 2, name = "n", nameCn = "", updatedAt = 100, touchedAt = 100)
        val nextDay = 100 + TOUCH_STEP_MILLIS
        val summary = SubjectSnapshot(detail.copy(updatedAt = nextDay), setOf(SubjectField.NAME))

        val merged = summary.mergeInto(detail)

        assertEquals(100, merged.updatedAt)
        assertEquals(nextDay, merged.touchedAt)
        assertEquals(nextDay, summary.mergeInto(null).touchedAt, "新行同样记下写入时刻")
    }

    @Test
    fun `同一天里再写到内容没变的行，合并结果与库里那一行相等`() {
        val stored = SubjectEntity(id = 8, type = 2, name = "n", nameCn = "", updatedAt = 0, touchedAt = 1_000)
        val sameDay = SubjectSnapshot(stored.copy(updatedAt = 1_000 + TOUCH_STEP_MILLIS - 1), setOf(SubjectField.NAME))

        assertEquals(stored, sameDay.mergeInto(stored))
    }

    @Test
    fun `摘要缺失字段不覆盖详情且不延长详情时间`() = runTest {
        val dao = FakeSubjectDao()
        dao.upsert(
            SubjectBo(
                8,
                SubjectType.ANIME,
                "旧名",
                "",
                summary = "完整简介",
                eps = 26,
                platform = "TV",
                infobox = listOf(SubjectInfoboxBo("制作", listOf("制作方"))),
            ).toEntity(updatedAt = 1),
        )
        val patch = SubjectSnapshot(
            SubjectBo(8, SubjectType.ANIME, "新名", "").toEntity(System.currentTimeMillis()),
            setOf(SubjectField.NAME, SubjectField.NAME_CN),
        )
        dao.merge(patch)
        val saved = dao.get(8)!!
        assertEquals("新名", saved.name)
        assertEquals("完整简介", saved.summary)
        assertEquals(26, saved.eps)
        assertEquals("TV", saved.platform)
        assertEquals(listOf(SubjectInfoboxBo("制作", listOf("制作方"))), saved.toBo().infobox)
        assertEquals(1, saved.updatedAt)
    }

    @Test
    fun `完整响应明确空值可以清空旧详情`() = runTest {
        val dao = FakeSubjectDao()
        dao.upsert(
            SubjectBo(
                8,
                SubjectType.ANIME,
                "旧名",
                "",
                summary = "旧简介",
                eps = 26,
                platform = "TV",
                infobox = listOf(SubjectInfoboxBo("制作", listOf("制作方"))),
            ).toEntity(System.currentTimeMillis()),
        )
        dao.merge(
            SubjectSnapshot(
                SubjectBo(8, SubjectType.ANIME, "新名", "", platform = "", infobox = emptyList()).toEntity(System.currentTimeMillis()),
                detail = true,
            ),
        )
        assertEquals("", dao.get(8)?.summary)
        assertEquals(null, dao.get(8)?.eps)
        assertEquals("", dao.get(8)?.platform)
        assertEquals(emptyList(), dao.get(8)?.toBo()?.infobox)
    }

    @Test
    fun `评分分布只由详情写入，摘要合并不清掉它`() {
        val detail = SubjectEntity(id = 8, type = 2, name = "n", nameCn = "", updatedAt = 100, ratingCountsJson = "[1,2,3,4,5,6,7,8,9,10]")
        val summary = SubjectSnapshot(detail.copy(ratingCountsJson = "[]"), setOf(SubjectField.NAME, SubjectField.RATING_SCORE))
        assertEquals("[1,2,3,4,5,6,7,8,9,10]", summary.mergeInto(detail).ratingCountsJson)

        val fresh = SubjectSnapshot(detail.copy(ratingCountsJson = "[0,0,0,0,0,0,0,0,0,1]"), detail = true)
        assertEquals("[0,0,0,0,0,0,0,0,0,1]", fresh.mergeInto(detail).ratingCountsJson)
    }
}
