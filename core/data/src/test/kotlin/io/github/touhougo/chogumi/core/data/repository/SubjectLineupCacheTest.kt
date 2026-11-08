package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.subject.SubjectSection
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionEntity
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 生涯阵容读真实仓库：固定容量按最近访问淘汰，正文版本变化重新解析，并发轮次不相互驱逐。 */
class SubjectLineupCacheTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val sections = FakeSubjectSectionDao()
    @Volatile private var pauseRead = false
    private val readStarted = CompletableDeferred<Unit>()
    private val releaseRead = CompletableDeferred<Unit>()
    private val gatedSections = object : SubjectSectionDao by sections {
        override suspend fun get(subjectId: Int, section: String, viewerId: Int): SubjectSectionEntity? {
            if (pauseRead && subjectId == 1 && section == SubjectSection.CHARACTERS.name) {
                readStarted.complete(Unit)
                releaseRead.await()
            }
            return sections.get(subjectId, section, viewerId)
        }
    }
    private val client = resources.apiClient { error("这里只读缓存，不发请求") }
    private val repo = testSubjectRepository(
        resources,
        SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), gatedSections, testNsfwFilter(resources),
        tokenStore = testTokenStore(resources, userId = VIEWER),
    )

    @Test
    fun `角色或制作人员缺一样就算没缓存`() = runTest {
        cache(1, SubjectSection.CHARACTERS, characters("花泽香菜"))
        cache(1, SubjectSection.PERSONS, STUDIO)
        cache(2, SubjectSection.CHARACTERS, characters("早见沙织"))
        cache(3, SubjectSection.PERSONS, STUDIO, viewerId = 0)
        assertEquals(listOf(2, 3), repo.missingLineups(listOf(1, 2, 3)))
    }

    @Test
    fun `读出主角声优与制作公司_缓存换了重新解析`() = runTest {
        cache(1, SubjectSection.CHARACTERS, characters("花泽香菜"), updatedAt = 1L)
        cache(1, SubjectSection.PERSONS, STUDIO, updatedAt = 1L)
        val first = repo.cachedLineups(mapOf(1 to SubjectType.ANIME)).getValue(1)
        assertEquals(listOf("花泽香菜"), first.voiceActors.map { it.name })
        assertEquals(listOf("京都动画"), first.studios.map { it.name })
        cache(1, SubjectSection.CHARACTERS, characters("早见沙织"), updatedAt = 2L)
        assertEquals(listOf("早见沙织"), repo.cachedLineups(mapOf(1 to SubjectType.ANIME)).getValue(1).voiceActors.map { it.name })
    }

    @Test
    fun `阵容超过固定预算时淘汰最久未访问而非限制结果`() = runTest {
        seed(1..513)
        val all = repo.cachedLineups((1..513).associateWith { SubjectType.ANIME })
        assertEquals(513, all.size, "memo预算不限制统计结果")
        val before = sections.gets.get()
        repo.cachedLineups(mapOf(1 to SubjectType.ANIME))
        assertEquals(2, sections.gets.get() - before, "超过512份预算时最早的一部必须重读两个模块")
    }

    @Test
    fun `最近访问的阵容留下未访问的先被淘汰`() = runTest {
        seed(1..513)
        repo.cachedLineups((1..512).associateWith { SubjectType.ANIME })
        repo.cachedLineups(mapOf(1 to SubjectType.ANIME))
        repo.cachedLineups(mapOf(513 to SubjectType.ANIME))
        val beforeRecent = sections.gets.get()
        repo.cachedLineups(mapOf(1 to SubjectType.ANIME))
        assertEquals(0, sections.gets.get() - beforeRecent, "刚访问的第1部仍在")
        val beforeOld = sections.gets.get()
        repo.cachedLineups(mapOf(2 to SubjectType.ANIME))
        assertEquals(2, sections.gets.get() - beforeOld, "第2部最久没用，重读正文")
    }

    @Test
    fun `并发读取不同集合保持各自结果且不相互驱逐`() = runTest {
        seed(1..2)
        pauseRead = true
        val first = async(Dispatchers.Default) { repo.cachedLineups(mapOf(1 to SubjectType.ANIME)) }
        awaitUntil("第一轮阵容正文读取已开始") { readStarted.isCompleted }
        val second = async(Dispatchers.Default) { repo.cachedLineups(mapOf(2 to SubjectType.ANIME)) }
        releaseRead.complete(Unit)
        assertEquals(listOf("花泽香菜"), first.await().getValue(1).voiceActors.map { it.name })
        assertEquals(listOf("京都动画"), second.await().getValue(2).studios.map { it.name })
        val before = sections.gets.get()
        assertEquals(setOf(1, 2), repo.cachedLineups(mapOf(1 to SubjectType.ANIME, 2 to SubjectType.ANIME)).keys)
        assertEquals(0, sections.gets.get() - before, "两轮并发完成后各自memo都保留，不再按最后输入集合驱逐")
    }

    @Test
    fun `不算阵容的类型不在结果里`() = runTest {
        seed(1..1)
        assertEquals(emptyMap(), repo.cachedLineups(mapOf(1 to SubjectType.BOOK)))
    }

    private suspend fun seed(ids: IntRange) {
        for (id in ids) {
            cache(id, SubjectSection.CHARACTERS, characters("花泽香菜"))
            cache(id, SubjectSection.PERSONS, STUDIO)
        }
    }

    private suspend fun cache(subjectId: Int, section: SubjectSection, json: String, viewerId: Int = VIEWER, updatedAt: Long = 1L) =
        sections.seed(SubjectSectionEntity(subjectId, section.name, viewerId, json, updatedAt))

    private fun characters(actor: String) = Json.encodeToString(
        ListSerializer(RelatedCharacterBo.serializer()),
        listOf(RelatedCharacterBo(id = 1, name = "主角", summary = "", type = 1, role = CastRole.MAIN, actors = listOf(RelatedPersonBo(id = 10, name = actor, type = 1)))),
    )

    private companion object {
        const val VIEWER = 7
        val STUDIO = Json.encodeToString(
            ListSerializer(RelatedPersonBo.serializer()),
            listOf(RelatedPersonBo(id = 20, name = "京都动画", type = 3, relation = "动画制作")),
        )
    }
}
