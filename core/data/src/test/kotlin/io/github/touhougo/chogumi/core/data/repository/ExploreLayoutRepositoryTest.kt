package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfEntity
import io.github.touhougo.chogumi.core.data.db.explore.toShelfRows
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.WorkShelfEntry
import io.github.touhougo.chogumi.core.model.WorkShelfSource
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.pickWord
import io.github.touhougo.chogumi.core.model.withHidden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * 探索页布局仓库：没动过跟默认、第一次改动整份写进去、图钉、移除、改名、恢复默认与读不懂的行。
 * 布局怎么变由 core:model 的纯函数测（ExploreShelfPinTest），这里测存取。
 */
class ExploreLayoutRepositoryTest {

    private val dao = FakeExploreShelfDao()
    private val repository = ExploreLayoutRepository(dao)
    private val anime = SubjectType.ANIME

    private val custom = SearchCriteria(subjectType = anime, sort = SearchSort.COLLECTS)
        .pickWord("奇幻", WordPick.ADD)
        .pickWord("百合", WordPick.ADD)

    @Test
    fun `没动过的分类读出默认布局_不写表`() = runTest {
        for (type in ContentDomains) assertEquals(defaultShelfLayout(type), repository.observe(type).first())
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `每个分类的默认布局存进去再读出来不变`() = runTest {
        for (type in ContentDomains) {
            dao.replaceType(type.value, defaultShelfLayout(type).toShelfRows(type))
            assertEquals(defaultShelfLayout(type), repository.observe(type).first(), "$type 存取后变了")
        }
    }

    @Test
    fun `图钉_第一次改动把默认布局整份写进去_新货架在最前`() = runTest {
        repository.pin(custom, "奇幻 × 百合")

        val layout = repository.observe(anime).first()
        assertEquals(WorkShelfEntry("custom:1", WorkShelfSource.Custom(custom, "奇幻 × 百合"), WorkShelfStyle.POSTER_ROW), layout.first())
        assertEquals(defaultShelfLayout(anime), layout.drop(1))
        assertEquals(layout.indices.toList(), dao.rows.value.map { it.position })
        assertEquals(defaultShelfLayout(SubjectType.BOOK), repository.observe(SubjectType.BOOK).first(), "别的分类不受影响")
    }

    @Test
    fun `没有变化的改动不写表`() = runTest {
        repository.pin(custom, "一")
        val writes = dao.writes

        repository.pin(custom, "二")
        repository.edit(anime) { it }

        assertEquals(writes, dao.writes)
    }

    @Test
    fun `移除与改名落表`() = runTest {
        repository.hide(anime, "preset:奇幻")
        repository.rename(anime, "preset:恋爱", "恋爱喜剧")

        val layout = repository.observe(anime).first()
        assertTrue(layout.single { it.id == "preset:奇幻" }.hidden)
        assertEquals("恋爱喜剧", ((layout.single { it.id == "preset:恋爱" } as WorkShelfEntry).source as WorkShelfSource.Preset).name)
    }

    @Test
    fun `恢复默认_删掉存下来的那份`() = runTest {
        repository.hide(anime, "preset:奇幻")
        repository.hide(SubjectType.BOOK, "fixed:RANKING")

        repository.reset(anime)

        assertEquals(defaultShelfLayout(anime), repository.observe(anime).first())
        assertTrue(dao.rows.value.all { it.subjectType == SubjectType.BOOK.value }, "只删这个分类")
    }

    @Test
    fun `读不懂的行跳过_其余照常`() = runTest {
        repository.pin(custom, "一")
        dao.rows.value = dao.rows.value.map { if (it.position == 1) it.copy(entry = """{"type":"未来的货架"}""") else it }

        val layout = repository.observe(anime).first()

        assertEquals(defaultShelfLayout(anime).size, layout.size)
        assertEquals("custom:1", layout.first().id)
    }

    @Test
    fun `存下来的一行就是那条货架的 JSON_位置是列表顺序`() = runTest {
        repository.edit(anime) { defaultShelfLayout(anime).take(2).withHidden("fixed:RANKING") }

        val rows: List<ExploreShelfEntity> = dao.rows.value
        assertEquals(listOf(0, 1), rows.map { it.position })
        assertTrue(rows[1].entry.contains("\"hidden\":true"), rows[1].entry)
    }
}
