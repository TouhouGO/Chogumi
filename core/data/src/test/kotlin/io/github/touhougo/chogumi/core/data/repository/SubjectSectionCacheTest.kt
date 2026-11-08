package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSection
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionEntity
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * 条目详情子模块（关联、角色、制作人员）的本地缓存：三者共用 [SubjectRepository] 里同一条缓存通道，
 * 这里用制作人员一路覆盖新鲜期内不发请求、过期后台刷新、强刷回源、旧缓存解析失败按未命中处理。
 */
class SubjectSectionCacheTest {
    @get:org.junit.Rule
    val resources = TestResources()


    /** 假服务端跑在发请求的线程上：计数用原子量，处理器读的值标 volatile。 */
    private val hits = AtomicInteger()
    @Volatile private var version = 1
    @Volatile private var clock = 10_000L

    private val engine = MockEngine {
        hits.incrementAndGet()
        respond(
            content = """[{"id":1,"name":"监督$version","type":1,"career":["producer"],"relation":"导演","eps":""}]""",
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }

    private val client = resources.apiClient(engine)

    private val sections = FakeSubjectSectionDao()

    private val repo = testSubjectRepository(
        resources,
        SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), sections, testNsfwFilter(resources),
        clock = AppClock { clock },
    )

    private suspend fun persons(refresh: Boolean = false) = repo.getPersons(8, refresh).toList()

    private fun List<Load<List<RelatedPersonBo>>>.names() =
        filterIsInstance<Load.Ready<List<RelatedPersonBo>>>().map { it.value.single().name }

    @Test
    fun `新鲜期内重进直接用本地缓存不发请求`() = runTest {
        persons()
        version = 2
        val second = persons()

        assertEquals(1, hits.get(), "新鲜期内不应再请求")
        assertEquals(listOf("监督1"), second.names())
        assertTrue((second.single() as Load.Ready).fromCache)
    }

    @Test
    fun `过期后先回显旧缓存再后台刷新`() = runTest {
        persons()
        version = 2
        clock += CacheTtl.SUBJECT_DETAIL

        assertEquals(listOf("监督1", "监督2"), persons().names())
        assertEquals(2, hits.get())
    }

    @Test
    fun `refresh 为 true 时不读缓存直接回源`() = runTest {
        persons()
        version = 2

        val refreshed = persons(refresh = true)

        assertEquals(listOf("监督2"), refreshed.names())
        assertEquals(2, hits.get())
    }

    @Test
    fun `缓存解析失败按未命中处理并重新拉取`() = runTest {
        sections.seed(SubjectSectionEntity(8, SubjectSection.PERSONS.name, 0, json = "{旧格式", updatedAt = clock))

        val result = persons()

        assertEquals(1, hits.get())
        assertEquals("监督1", assertIs<Load.Ready<List<RelatedPersonBo>>>(result.last()).value.single().name)
    }
}
