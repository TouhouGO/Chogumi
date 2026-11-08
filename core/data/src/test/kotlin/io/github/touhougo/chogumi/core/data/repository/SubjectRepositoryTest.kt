package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class SubjectRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private fun makeClient(content: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val engine = MockEngine {
            respond(
                content = content,
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        return resources.apiClient(engine)
    }

    @Test
    fun `详情、章节与各分区的读写和映射跑在注入的计算调度器上`() = runTest {
        val client = makeClient("""{"data":[],"total":0,"limit":100,"offset":0}""")
        fun repo(dispatcher: CountingDispatcher) = testSubjectRepository(
            resources,
            SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client),
            FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources), computationDispatcher = dispatcher,
        )
        val subject = CountingDispatcher().also { repo(it).getSubject(8).toList() }
        val episodes = CountingDispatcher().also { repo(it).getEpisodes(8).toList() }
        val characters = CountingDispatcher().also { repo(it).getCharacters(8).toList() }
        assertTrue(subject.dispatches > 0, "详情没有切到计算调度器")
        assertTrue(episodes.dispatches > 0, "章节没有切到计算调度器")
        assertTrue(characters.dispatches > 0, "分区没有切到计算调度器")
    }

    @Test
    fun `getSubject 网络成功落缓存并返回`() = runTest {
        val client = makeClient(
            """{"id":8,"type":2,"name":"こんにちは","name_cn":"你好","summary":"",
                "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
                "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,
                "meta_tags":[],"tags":[],
                "rating":{"rank":1,"total":5,"count":{},"score":8.0},
                "collection":{"wish":1,"collect":2,"doing":3,"on_hold":0,"dropped":0}}""",
        )
        val subjectDao = FakeSubjectDao()
        val episodeDao = FakeEpisodeDao()
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), subjectDao, episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources))
        val results = mutableListOf<Load<*>>()
        repo.getSubject(8).collect { results.add(it) }
        assertEquals(2, results.size)
        assertTrue(results[1] is Load.Ready)
        assertEquals(8, subjectDao.store[8]?.id)
    }

    @Test
    fun `getSubject 过期缓存先展示 随后网络失败仍上报且缓存不变`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            requests.record(request)
            respond("down", HttpStatusCode.ServiceUnavailable)
        })
        val subjectDao = FakeSubjectDao()
        val saved = SubjectEntity(id = 8, type = 2, name = "n", nameCn = "中", updatedAt = 1L)
        subjectDao.upsert(saved)
        val repo = testSubjectRepository(
            resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), subjectDao,
            FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources), clock = TestClock(CacheTtl.SUBJECT_DETAIL + 1L),
        )
        val results = mutableListOf<Load<*>>()
        repo.getSubject(8).collect { results.add(it) }
        assertEquals(
            listOf<Load<*>>(Load.Ready(SubjectBo(8, SubjectType.ANIME, "n", "中"), fromCache = true), Load.Failed(Failure.Http(503))),
            results,
        )
        assertEquals(listOf("/v0/subjects/8"), requests.map { it.url.encodedPath })
        assertEquals(saved, subjectDao.store[8])
    }

    @Test
    fun `getEpisodes 网络成功替换本地章节`() = runTest {
        val client = makeClient(
            """{"data":[{"id":1,"subject_id":8,"type":0,"name":"ep1","name_cn":"","sort":1.0,"ep":1,
                "airdate":"","comment":0,"duration":"","desc":"","disc":0,"duration_seconds":0}],
                "total":1,"limit":100,"offset":0}""",
        )
        val episodeDao = FakeEpisodeDao()
        episodeDao.upsertAll(listOf(EpisodeEntity(id = 99, subjectId = 8, type = 0, name = "旧", nameCn = "", sort = 99.0)))
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), FakeSubjectDao(), episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources))
        val results = mutableListOf<Load<*>>()
        repo.getEpisodes(8).collect { results.add(it) }
        val last = results.last() as Load.Ready<*>
        @Suppress("UNCHECKED_CAST")
        val episodes = last.value as List<io.github.touhougo.chogumi.core.model.EpisodeBo>
        assertEquals(1, episodes.size)
        assertEquals("ep1", episodes.first().name)
        assertEquals(1, episodeDao.store.size)
    }

    private fun generateEpisodeJson(id: Int, sort: Double): String =
        """{"id":$id,"subject_id":8,"type":0,"name":"ep$id","name_cn":"","sort":$sort,"ep":$id,"airdate":"","comment":0,"duration":"","desc":"","disc":0,"duration_seconds":0}"""

    @Test
    fun `getEpisodes 跨页拼接全量获取`() = runTest {
        val callCount = AtomicInteger()
        val engine = MockEngine { request ->
            callCount.incrementAndGet()
            val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
            val limit = request.url.parameters["limit"]?.toIntOrNull() ?: 100
            val (items, total) = if (offset == 0) {
                val list = (1..100).map { generateEpisodeJson(it, it.toDouble()) }
                list to 120
            } else {
                val list = (101..120).map { generateEpisodeJson(it, it.toDouble()) }
                list to 120
            }
            respond(
                content = """{"data":[${items.joinToString(",")}], "total":$total, "limit":$limit, "offset":$offset}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val episodeDao = FakeEpisodeDao()
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), FakeSubjectDao(), episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources))
        val results = mutableListOf<Load<*>>()
        repo.getEpisodes(8).collect { results.add(it) }
        val last = results.last() as Load.Ready<*>
        @Suppress("UNCHECKED_CAST")
        val episodes = last.value as List<io.github.touhougo.chogumi.core.model.EpisodeBo>
        assertEquals(2, callCount.get())
        assertEquals(120, episodes.size)
        assertEquals(120, episodeDao.store.size)
        assertEquals(1, episodes.first().id)
        assertEquals(120, episodes.last().id)
    }

    @Test
    fun `getEpisodes 恰好整除不触发多余请求`() = runTest {
        val callCount = AtomicInteger()
        val engine = MockEngine { request ->
            callCount.incrementAndGet()
            val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
            val limit = request.url.parameters["limit"]?.toIntOrNull() ?: 100
            val list = (1..100).map { generateEpisodeJson(it, it.toDouble()) }
            respond(
                content = """{"data":[${list.joinToString(",")}], "total":100, "limit":$limit, "offset":$offset}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val episodeDao = FakeEpisodeDao()
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), FakeSubjectDao(), episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources))
        val results = mutableListOf<Load<*>>()
        repo.getEpisodes(8).collect { results.add(it) }
        val last = results.last() as Load.Ready<*>
        @Suppress("UNCHECKED_CAST")
        val episodes = last.value as List<io.github.touhougo.chogumi.core.model.EpisodeBo>
        assertEquals(1, callCount.get())
        assertEquals(100, episodes.size)
        assertEquals(100, episodeDao.store.size)
    }


    @Test
    fun `getSubject 时间戳为 0 的存量行走一次后台刷新后自愈并获得真实时间戳`() = runTest {
        val client = makeClient(
            """{"id":8,"type":2,"name":"こんにちは","name_cn":"你好","summary":"",
                "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
                "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,
                "meta_tags":[],"tags":[],
                "rating":{"rank":1,"total":5,"count":{},"score":8.0},
                "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}""",
        )
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "旧", nameCn = "", updatedAt = 0L))
        val clock = TestClock(time = 50_000L)
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources), clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getSubject(8).collect { results.add(it) }

        assertEquals(2, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertTrue(results[1] is Load.Ready && !(results[1] as Load.Ready<*>).fromCache)
        assertEquals(50_000L, subjectDao.store[8]?.updatedAt)
    }

    @Test
    fun `getSubject 新鲜缓存直接直出不打网络`() = runTest {
        val networkCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val engine = MockEngine {
            networkCalled.set(true)
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val subjectDao = FakeSubjectDao()
        val clock = TestClock(time = 10_000_000L)
        // 10 分钟前写入，TTL 为 24 小时
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "新鲜", nameCn = "", updatedAt = 10_000_000L - 10 * 60 * 1000L))
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources), clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getSubject(8).collect { results.add(it) }

        assertEquals(1, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertEquals(false, networkCalled.get())
    }

    @Test
    fun `getEpisodes 时间戳为 0 的存量行走一次后台刷新后获得真实时间戳`() = runTest {
        val client = makeClient(
            """{"data":[{"id":1,"subject_id":8,"type":0,"name":"ep1","name_cn":"","sort":1.0,"ep":1,
                "airdate":"","comment":0,"duration":"","desc":"","disc":0,"duration_seconds":0}],
                "total":1,"limit":100,"offset":0}""",
        )
        val episodeDao = FakeEpisodeDao()
        episodeDao.upsertAll(listOf(EpisodeEntity(id = 99, subjectId = 8, type = 0, name = "旧", nameCn = "", sort = 99.0, updatedAt = 0L)))
        val clock = TestClock(time = 60_000L)
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), FakeSubjectDao(), episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources), clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getEpisodes(8).collect { results.add(it) }

        assertEquals(2, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertTrue(results[1] is Load.Ready && !(results[1] as Load.Ready<*>).fromCache)
        assertEquals(60_000L, episodeDao.store.values.first().updatedAt)
    }

    @Test
    fun `getEpisodes 取回的章节没变时正文不重写，新鲜期照样往前走`() = runTest {
        val requests = AtomicInteger()
        val client = resources.apiClient(
            MockEngine {
                requests.incrementAndGet()
                respond(
                    """{"data":[{"id":1,"subject_id":8,"type":0,"name":"ep1","name_cn":"","sort":1.0,"ep":1,
                        "airdate":"","comment":0,"duration":"","desc":"","disc":0,"duration_seconds":0}],
                        "total":1,"limit":100,"offset":0}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            },
        )
        val episodeDao = FakeEpisodeDao()
        val firstFetch = 1_000L
        val clock = TestClock(time = firstFetch)
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), FakeSubjectDao(), episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources), clock = clock)
        repo.getEpisodes(8).collect { }
        assertEquals(1, requests.get())

        // 过了新鲜期：旧缓存先出、网络再取一次，取回的内容和库里的一样
        clock.time = firstFetch + CacheTtl.EPISODES + 1
        repo.getEpisodes(8).collect { }
        assertEquals(2, requests.get())
        assertEquals(firstFetch, episodeDao.store.values.single().updatedAt, "内容没变，章节行不该重写")

        // 新鲜期从第二次取回算起：再读直接出缓存，不打网络
        repo.getEpisodes(8).collect { }
        assertEquals(2, requests.get(), "第二次取回之后仍被当成过期，又打了网络")
    }

    @Test
    fun `getEpisodes 新鲜缓存直接直出不打网络`() = runTest {
        val networkCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val engine = MockEngine {
            networkCalled.set(true)
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val episodeDao = FakeEpisodeDao()
        val clock = TestClock(time = 10_000_000L)
        // 10 分钟前写入，TTL 为 24 小时
        val fetchedAt = 10_000_000L - 10 * 60 * 1000L
        episodeDao.replaceAll(8, listOf(EpisodeEntity(id = 1, subjectId = 8, type = 0, name = "新鲜", nameCn = "", sort = 1.0, updatedAt = fetchedAt)), fetchedAt)
        val repo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), FakeSubjectDao(), episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources), clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getEpisodes(8).collect { results.add(it) }

        assertEquals(1, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertEquals(false, networkCalled.get())
    }

    @Test
    fun `getRelations 一页回不满时游标按请求跨度推进，不按返回条数`() = runTest {
        val offsets = ConcurrentRequestRecorder()
        // 第一页服务端跳过了一条（只回 99 条），total 是过滤前的 150：下一页仍从 100 起
        val client = relationsClient(offsets) { offprint, offset ->
            when (offprint) {
                "true" -> """{"data":[],"total":0}""" to HttpStatusCode.OK
                else -> {
                    val ids = when (offset) {
                        0 -> 1..99
                        100 -> 101..150
                        else -> error("按返回条数推进了游标：offset=$offset")
                    }
                    """{"data":[${ids.joinToString(",") { relationItem(it, relationId = it % 3 + 1) }}],"total":150}""" to HttpStatusCode.OK
                }
            }
        }

        val result = relationsRepo(client).getRelations(8).last()

        assertTrue(result is Load.Ready, "应成功：$result")
        assertEquals(listOf(0, 100), offsets.filter { it.url.parameters["offprint"] != "true" }.map { it.url.parameters["offset"]?.toIntOrNull() ?: 0 }.sorted())
        assertEquals((1..99).toList() + (101..150).toList(), result.value.map { it.id })
        assertEquals(result.value.map { it.id % 3 + 1 }, result.value.map { it.relationId })
    }

    /** 关联项 JSON（测试共用）：relationId 用来区分两路，order 用于组内排序。 */
    private fun relationItem(id: Int, relationId: Int, order: Int = 0) =
        """{"subject":{"id":$id,"name":"s$id","nameCN":"","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false},
            "relation":{"id":$relationId,"en":"","cn":"","jp":"","desc":""},"order":$order}"""

    private fun relationsClient(requests: ConcurrentRequestRecorder? = null, handler: (offprint: String?, offset: Int) -> Pair<String, HttpStatusCode>): HttpClient {
        val engine = MockEngine { request ->
            requests?.record(request)
            val (body, status) = handler(
                request.url.parameters["offprint"],
                request.url.parameters["offset"]?.toIntOrNull() ?: 0,
            )
            respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        return resources.apiClient(engine)
    }

    private fun relationsRepo(client: HttpClient) =
        testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))

    @Test
    fun `getRelations 两路合并：单行本与非单行本都在结果里`() = runTest {
        val calls = ConcurrentRequestRecorder()
        val client = relationsClient(calls) { offprint, offset ->
            when (offprint) {
                "false" -> if (offset == 0) {
                    """{"data":[${relationItem(1, 2)},${relationItem(2, 3)}],"total":2}""" to HttpStatusCode.OK
                } else {
                    """{"data":[],"total":2}""" to HttpStatusCode.OK
                }
                "true" -> if (offset == 0) {
                    """{"data":[${relationItem(100, 1003)},${relationItem(101, 1003)}],"total":2}""" to HttpStatusCode.OK
                } else {
                    """{"data":[],"total":2}""" to HttpStatusCode.OK
                }
                else -> error("offprint 必须显式传递，收到 $offprint")
            }
        }

        val result = relationsRepo(client).getRelations(8).last()

        assertTrue(result is Load.Ready, "应成功：$result")
        val relations = result.value
        assertEquals(listOf(1, 2, 100, 101), relations.map { it.id })
        assertEquals(listOf(2, 3, 1003, 1003), relations.map { it.relationId })
        // 两路都必须显式带 offprint：不传等同 false，省略就永远拿不到单行本
        assertEquals(setOf("false", "true"), calls.map { it.url.parameters["offprint"] }.toSet())
    }

    @Test
    fun `getRelations 单行本一路多页（大于 100）也能取全`() = runTest {
        val offprintCalls = ConcurrentRequestRecorder()
        val client = relationsClient(offprintCalls) { offprint, offset ->
            when (offprint) {
                "false" -> """{"data":[${relationItem(1, 2)}],"total":1}""" to HttpStatusCode.OK
                "true" -> {
                    val (items, total) = if (offset == 0) {
                        (1001..1100).map { relationItem(it, 1003) } to 120
                    } else {
                        (1101..1120).map { relationItem(it, 1003) } to 120
                    }
                    """{"data":[${items.joinToString(",")}],"total":$total}""" to HttpStatusCode.OK
                }
                else -> error("offprint 必须显式传递，收到 $offprint")
            }
        }

        val result = relationsRepo(client).getRelations(8).last()

        assertTrue(result is Load.Ready, "应成功：$result")
        val relations = result.value
        assertEquals(2, offprintCalls.count { it.url.parameters["offprint"] == "true" }, "单行本 120 条应分两页取")
        assertEquals(121, relations.size)
        val offprintItems = relations.filter { it.relationId == 1003 }
        assertEquals(120, offprintItems.size)
        assertEquals(1001, offprintItems.first().id)
        assertEquals(1120, offprintItems.last().id)
    }

    @Test
    fun `getRelations 单行本一路失败则整体 Error，不得静默吞掉`() = runTest {
        val client = relationsClient { offprint, _ ->
            when (offprint) {
                "false" -> """{"data":[${relationItem(1, 2)}],"total":1}""" to HttpStatusCode.OK
                "true" -> "boom" to HttpStatusCode.InternalServerError
                else -> error("offprint 必须显式传递，收到 $offprint")
            }
        }

        val result = relationsRepo(client).getRelations(8).last()

        assertTrue(result is Load.Failed, "单行本那一路失败必须整体报错（区块显示重试），实际：$result")
    }

    @Test
    fun `getRelations 非单行本一路失败同样整体 Error`() = runTest {
        val client = relationsClient { offprint, _ ->
            when (offprint) {
                "false" -> "boom" to HttpStatusCode.InternalServerError
                "true" -> """{"data":[${relationItem(100, 1003)}],"total":1}""" to HttpStatusCode.OK
                else -> error("offprint 必须显式传递，收到 $offprint")
            }
        }

        val result = relationsRepo(client).getRelations(8).last()

        assertTrue(result is Load.Failed, "非单行本那一路失败同样必须整体报错，实际：$result")
    }

    @Test
    fun `getRelations 两路都返回同一条目时按 id 去重`() = runTest {
        val client = relationsClient { offprint, _ ->
            when (offprint) {
                "false" -> """{"data":[${relationItem(7, 2)}],"total":1}""" to HttpStatusCode.OK
                "true" -> """{"data":[${relationItem(7, 1003)}],"total":1}""" to HttpStatusCode.OK
                else -> error("offprint 必须显式传递，收到 $offprint")
            }
        }

        val result = relationsRepo(client).getRelations(8).last()

        assertTrue(result is Load.Ready, "应成功：$result")
        val relations = result.value
        assertEquals(1, relations.size)
        assertEquals(7, relations.single().id)
    }

    /** 角色项 JSON（测试共用）：type 是在作品里的定位，声优没有中文名时用原名。 */
    private fun characterItem(id: Int, type: Int = 2) =
        """{"character":{"id":$id,"name":"c$id","nameCN":"角色$id","role":1,"info":"","comment":0,"lock":false,"nsfw":false},
            "casts":[{"person":{"id":${id + 10000},"name":"cv$id","nameCN":"","type":1,"info":"","career":["seiyu"],
              "comment":0,"lock":false,"nsfw":false},"relation":0,"summary":""}],"type":$type,"order":0}"""

    @Test
    fun `getCharacters 一次请求带齐中文名、定位与声优`() = runTest {
        val offsets = ConcurrentRequestRecorder()
        val client = relationsClient(offsets) { _, _ ->
            """{"data":[${characterItem(1, type = 1)},${characterItem(2)}],"total":2}""" to HttpStatusCode.OK
        }

        val result = relationsRepo(client).getCharacters(8).last()

        assertTrue(result is Load.Ready, "应成功：$result")
        assertEquals(listOf(0), offsets.map { it.url.parameters["offset"]?.toIntOrNull() ?: 0 }, "一页取完只发一个请求")
        val (main, supporting) = result.value
        assertEquals("角色1", io.github.touhougo.chogumi.core.model.displayNameOf(main))
        assertEquals(io.github.touhougo.chogumi.core.model.CastRole.MAIN, main.role)
        assertEquals(io.github.touhougo.chogumi.core.model.CastRole.SUPPORTING, supporting.role)
        assertEquals("cv1", main.actors.single().name)
    }

    @Test
    fun `getCharacters 多页时其余各页一起发并按顺序拼接`() = runTest {
        val offsets = ConcurrentRequestRecorder()
        val client = relationsClient(offsets) { _, offset ->
            val ids = when (offset) {
                0 -> 1..100
                100 -> 101..200
                else -> 201..230
            }
            """{"data":[${ids.joinToString(",") { characterItem(it) }}],"total":230}""" to HttpStatusCode.OK
        }

        val result = relationsRepo(client).getCharacters(8).last()

        assertTrue(result is Load.Ready, "应成功：$result")
        assertEquals((1..230).toList(), result.value.map { it.id })
        assertEquals(setOf(0, 100, 200), offsets.map { it.url.parameters["offset"]?.toIntOrNull() ?: 0 }.toSet())
        assertEquals(3, offsets.count())
    }

    @Test
    fun `登录态下条目详情各公开读接口携带 Authorization 头，未登录态不携带`() = runTest {
        val emptyListJson = """{"data":[],"total":0,"limit":20,"offset":0}"""
        val rawListJson = """[]"""
        val capturedRequests = ConcurrentRequestRecorder()
        fun capturedAuth(path: String) = capturedRequests.last { it.url.encodedPath == path }.headers[io.ktor.http.HttpHeaders.Authorization]
        val engine = MockEngine { request ->
            capturedRequests.record(request)
            val content = when {
                request.url.encodedPath.endsWith("/persons") -> rawListJson
                request.url.encodedPath.endsWith("/episodes") || request.url.encodedPath.endsWith("/relations") ||
                    request.url.encodedPath.endsWith("/characters") -> emptyListJson
                else -> """{"id":8,"type":2,"name":"n","name_cn":"你好","summary":"","images":{"large":"","common":"","medium":"","small":"","grid":""},"series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,"meta_tags":[],"tags":[],"rating":{"rank":1,"total":5,"count":{},"score":8.0},"collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = backgroundScope)
        val repo = testSubjectRepository(
            resources,
            SubjectApi(client),
            io.github.touhougo.chogumi.core.network.api.NextApi(client),
            FakeSubjectDao(),
            FakeEpisodeDao(), FakeSubjectSectionDao(),
            testNsfwFilter(resources),
            tokenStore = tokenStore,
        )

        // 1. 未登录状态
        repo.getSubject(8, refresh = true).collect {}
        kotlin.test.assertNull(capturedAuth("/v0/subjects/8"))

        repo.getEpisodes(8, refresh = true).collect {}
        kotlin.test.assertNull(capturedAuth("/v0/episodes"))

        repo.getRelations(8).last()
        kotlin.test.assertNull(capturedAuth("/p1/subjects/8/relations"))

        repo.getCharacters(8).last()
        kotlin.test.assertNull(capturedAuth("/p1/subjects/8/characters"))

        repo.getPersons(8).last()
        kotlin.test.assertNull(capturedAuth("/v0/subjects/8/persons"))

        // 2. 登录状态
        val session = tokenStore.beginSession("test_token_xyz")
        tokenStore.completeSession(session, userId = 1, username = "testuser")

        repo.getSubject(8, refresh = true).collect {}
        assertEquals("Bearer test_token_xyz", capturedAuth("/v0/subjects/8"))

        repo.getEpisodes(8, refresh = true).collect {}
        assertEquals("Bearer test_token_xyz", capturedAuth("/v0/episodes"))

        repo.getRelations(8).last()
        assertEquals("Bearer test_token_xyz", capturedAuth("/p1/subjects/8/relations"))

        repo.getCharacters(8).last()
        assertEquals("Bearer test_token_xyz", capturedAuth("/p1/subjects/8/characters"))

        repo.getPersons(8).last()
        assertEquals("Bearer test_token_xyz", capturedAuth("/v0/subjects/8/persons"))
    }

    @Test
    fun `getSubject 调用期间换号以换号失败收尾且网络数据不落库`() = runTest {
        val subjectDao = FakeSubjectDao()
        val tokenStore = testTokenStore(scope = backgroundScope)
        val sessionA = tokenStore.beginSession("token_a")
        tokenStore.completeSession(sessionA, userId = 1, username = "usera")

        val engine = MockEngine {
            // 网络请求期间换号
            val sessionB = tokenStore.beginSession("token_b")
            tokenStore.completeSession(sessionB, userId = 2, username = "userb")
            respond(
                content = """{"id":88,"type":2,"name":"n88","name_cn":"88","summary":"","images":{"large":"","common":"","medium":"","small":"","grid":""},"series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,"meta_tags":[],"tags":[],"rating":{"rank":1,"total":5,"count":{},"score":8.0},"collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val repo = testSubjectRepository(
            resources,
            SubjectApi(client),
            io.github.touhougo.chogumi.core.network.api.NextApi(client),
            subjectDao,
            FakeEpisodeDao(), FakeSubjectSectionDao(),
            testNsfwFilter(resources),
            tokenStore = tokenStore,
        )

        kotlin.test.assertEquals(
            Load.Failed(Failure.SessionChanged),
            repo.getSubject(88, refresh = true).toList().last(),
        )
        // 关键断言：条目数据不得残留在 subjectDao
        kotlin.test.assertNull(subjectDao.get(88))
    }
}
