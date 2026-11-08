package io.github.touhougo.chogumi.feature.home

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.progress.CommitGate
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeEntity
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.FakeAiringTimeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeHomeProgressDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.FaultablePreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.HomeRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testAiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testHomeRepository
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.foundation.BANGUMI_ZONE
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.AiringMinute
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.HomeProgressItemBo
import io.github.touhougo.chogumi.core.model.HomeSection
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.ToWatchLimit
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.BANGUMI_DATA_URL
import io.github.touhougo.chogumi.core.network.api.BangumiDataApi
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HomeViewModelTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()

    /** 进度提交的停手窗口：默认一点就结束；要看「窗口里」的用例先关上，断言完再打开。 */
    private val commitGate = CommitGate(open = true)

    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val viewModels = mutableListOf<HomeViewModel>()
    private val computationSchedulers = mutableListOf<TestCoroutineScheduler>()
    private val testPrefsList = mutableListOf<TestPreferencesRepository>()

    // 固定测试时间：2026-09-29
    private val initialTime = LocalDate.of(2026, 9, 29).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val testClock = TestClock(initialTime)

    @BeforeTest
    fun setUp() {
        testClock.time = initialTime
        main.install()
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            val teardown = async(Dispatchers.Default) { main.uninstall(viewModels.map { it.viewModelScope } + scope) }
            awaitUntil("所有真实与可控调度器上的协程已收尾") {
                computationSchedulers.forEach { it.runCurrent() }
                teardown.isCompleted
            }
            teardown.await()
        }
        testPrefsList.forEach { it.close() }

    }

    private val p1HomeJson: String
        get() {
            val nowSec = testClock.time / 1000L
            return """
                {
                  "progress": [
                    {
                      "subject": {
                        "id": 1,
                        "name": "連載未追平",
                        "nameCN": "连载未追平",
                        "type": 2,
                        "eps": 3,
                        "volumes": 0,
                        "series": false,
                        "doing": 10,
                        "airDate": "2026-09-20",
                        "weekday": 7,
                        "images": {
                          "large": "l1.jpg",
                          "common": "c1.jpg",
                          "medium": "m1.jpg",
                          "small": "s1.jpg",
                          "grid": "g1.jpg"
                        },
                        "info": "",
                        "metaTags": [],
                        "rating": {"rank": 1, "count": [], "score": 8.0, "total": 10},
                        "locked": false,
                        "nsfw": false
                      },
                      "interest": {
                        "id": 100,
                        "rate": 0,
                        "type": 3,
                        "comment": "",
                        "tags": [],
                        "epStatus": 1,
                        "volStatus": 0,
                        "private": false,
                        "updatedAt": $nowSec
                      },
                      "percent": 0.33,
                      "todayOnAir": false,
                      "lastUnwatchedEp": {"id": 102, "sort": 2.0},
                      "eps": [
                        {
                          "id": 101, "subjectID": 1, "sort": 1.0, "type": 0, "disc": 0,
                          "name": "ep1", "nameCN": "", "duration": "24m", "airdate": "2026-09-20", "comment": 0, "desc": "",
                          "collection": {"status": 2, "updatedAt": ${nowSec - 86400}}
                        },
                        {
                          "id": 102, "subjectID": 1, "sort": 2.0, "type": 0, "disc": 0,
                          "name": "ep2", "nameCN": "", "duration": "24m", "airdate": "2026-09-27", "comment": 0, "desc": "",
                          "collection": null
                        },
                        {
                          "id": 103, "subjectID": 1, "sort": 3.0, "type": 0, "disc": 0,
                          "name": "ep3", "nameCN": "", "duration": "24m", "airdate": "2026-10-04", "comment": 0, "desc": "",
                          "collection": null
                        }
                      ]
                    },
                    {
                      "subject": {
                        "id": 2,
                        "name": "連載已追平",
                        "nameCN": "连载已追平",
                        "type": 2,
                        "eps": 3,
                        "volumes": 0,
                        "series": false,
                        "doing": 10,
                        "airDate": "2026-09-20",
                        "weekday": 7,
                        "images": {
                          "large": "l2.jpg",
                          "common": "c2.jpg",
                          "medium": "m2.jpg",
                          "small": "s2.jpg",
                          "grid": "g2.jpg"
                        },
                        "info": "",
                        "metaTags": [],
                        "rating": {"rank": 1, "count": [], "score": 8.0, "total": 10},
                        "locked": false,
                        "nsfw": false
                      },
                      "interest": {
                        "id": 200,
                        "rate": 0,
                        "type": 3,
                        "comment": "",
                        "tags": [],
                        "epStatus": 2,
                        "volStatus": 0,
                        "private": false,
                        "updatedAt": $nowSec
                      },
                      "percent": 0.66,
                      "todayOnAir": false,
                      "lastUnwatchedEp": {"id": 203, "sort": 3.0},
                      "eps": [
                        {
                          "id": 201, "subjectID": 2, "sort": 1.0, "type": 0, "disc": 0,
                          "name": "ep1", "nameCN": "", "duration": "24m", "airdate": "2026-09-20", "comment": 0, "desc": "",
                          "collection": {"status": 2, "updatedAt": ${nowSec - 86400}}
                        },
                        {
                          "id": 202, "subjectID": 2, "sort": 2.0, "type": 0, "disc": 0,
                          "name": "ep2", "nameCN": "", "duration": "24m", "airdate": "2026-09-27", "comment": 0, "desc": "",
                          "collection": {"status": 2, "updatedAt": $nowSec}
                        },
                        {
                          "id": 203, "subjectID": 2, "sort": 3.0, "type": 0, "disc": 0,
                          "name": "ep3", "nameCN": "", "duration": "24m", "airdate": "2026-10-04", "comment": 0, "desc": "",
                          "collection": null
                        }
                      ]
                    },
                    {
                      "subject": {
                        "id": 3,
                        "name": "完結漫畫",
                        "nameCN": "完结漫画",
                        "type": 1,
                        "eps": 0,
                        "volumes": 0,
                        "series": false,
                        "doing": 10,
                        "airDate": "",
                        "weekday": 0,
                        "images": {
                          "large": "l3.jpg",
                          "common": "c3.jpg",
                          "medium": "m3.jpg",
                          "small": "s3.jpg",
                          "grid": "g3.jpg"
                        },
                        "info": "",
                        "metaTags": [],
                        "rating": {"rank": 1, "count": [], "score": 8.0, "total": 10},
                        "locked": false,
                        "nsfw": false
                      },
                      "interest": {
                        "id": 300,
                        "rate": 0,
                        "type": 3,
                        "comment": "",
                        "tags": [],
                        "epStatus": 5,
                        "volStatus": 2,
                        "private": false,
                        "updatedAt": ${nowSec + 10}
                      },
                      "percent": 0.0,
                      "todayOnAir": false,
                      "lastUnwatchedEp": null,
                      "eps": []
                    }
                  ],
                  "timeline": [],
                  "groupTopics": [],
                  "famousGroups": [],
                  "hotSubjectTopics": [],
                  "calendar": {}
                }
            """.trimIndent()
        }

    private val friendsResponseJson: String
        get() {
            val nowSec = testClock.time / 1000L
            return """
                {
                  "data": [
                    {
                      "user": {
                        "id": 1001,
                        "username": "alice",
                        "nickname": "爱丽丝",
                        "avatar": {
                          "small": "https://example.com/s.jpg",
                          "medium": "https://example.com/m.jpg",
                          "large": "https://example.com/a.jpg"
                        },
                        "group": 10,
                        "sign": "",
                        "joinedAt": 0,
                        "isFriend": false
                      },
                      "subject": {
                        "id": 1,
                        "name": "連載未追平",
                        "nameCN": "连载未追平",
                        "type": 2,
                        "images": {
                          "large": "l1.jpg",
                          "common": "c1.jpg",
                          "medium": "m1.jpg",
                          "small": "s1.jpg",
                          "grid": "g1.jpg"
                        },
                        "info": "",
                        "metaTags": [],
                        "rating": {"rank": 1, "count": [], "score": 8.0, "total": 10},
                        "locked": false,
                        "nsfw": false
                      },
                      "collectionType": 3,
                      "updatedAt": ${nowSec - 3600}
                    }
                  ],
                  "total": 1
                }
            """.trimIndent()
        }

    @Test
    fun `首页加载流程：从 p1 home 获取并在各区块正确分组`() = runBlocking {
        val harness = createHarness { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(
                    content = p1HomeJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/fixture_user/collections" -> respond(
                    content = """{"data":[],"total":0,"limit":50,"offset":0}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/p1/me/friends/subject-collections" -> respond(
                    content = friendsResponseJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }

        // 等待数据加载完成
        awaitUntil("待看与好友在看加载出来") {
            harness.vm.uiState.value.toWatch.isNotEmpty() && harness.vm.uiState.value.friends is Load.Ready
        }

        val state = harness.vm.uiState.value

        // 1. 待看 (TO_WATCH)：条目 1 连载中且已播未看，而条目 2 已追平不在此区
        assertEquals(1, state.toWatch.size)
        assertEquals(1, state.toWatch.first().card.subject.id)
        assertEquals(1, state.toWatch.first().airedUnwatchedCount)
        assertEquals(ProgressCount.Known(null, 1, 3), state.toWatch.first().card.progressCount)

        // 2. 追更时间表：条目 1 和条目 2 都在连载中，下一个播出日是 2026-10-04 (5 天后)
        assertEquals(1, state.scheduleGroups.size)
        val scheduleGroup = state.scheduleGroups.first()
        assertEquals(5L, scheduleGroup.daysUntil)
        assertEquals(2, scheduleGroup.items.size)

        // 3. 继续：非连载的书籍条目 3
        assertEquals(1, state.continueItems.size)
        assertEquals(3, state.continueItems.first().collection.subjectId)
        // 收藏状态与话数从收藏表读（/p1/home 的 interest 已合并进去）
        assertEquals(5, state.continueItems.first().collection.epStatus)
        assertEquals(ProgressCount.Known(BookUnit.EP, 5, 0), state.continueItems.first().card.progressCount)
        // 继续区只有书籍一个分类 → chip 行为空（「全部」与它内容相同，不画 chip）
        assertTrue(state.continueChips.isEmpty())

        // 4. 好友在看：社区开启时聚合到了条目 1 的动态
        val friends = state.friends.valueOrNull().orEmpty()
        assertEquals(1, friends.size)
        assertEquals(1, friends.first().subject.id)
        assertEquals("爱丽丝", friends.first().friends.first().nickname)
    }

    @Test
    fun `好友在看已有内容时下拉五类全部失败 内容留着 失败另发一条带重试的提示`() = runBlocking {
        var friendsFail = false
        // MockEngine 在多个线程上并发回调五类请求：计数必须原子
        val friendsRequests = AtomicInteger()
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(p1HomeJson, HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                "/p1/me/friends/subject-collections" -> {
                    friendsRequests.incrementAndGet()
                    if (friendsFail) respond("{}", HttpStatusCode.InternalServerError)
                    else respond(friendsResponseJson, HttpStatusCode.OK, json)
                }
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }
        awaitUntil("好友在看首轮到了") { harness.vm.uiState.value.friends is Load.Ready }
        awaitUntil("首页首轮落定") { !harness.vm.uiState.value.refreshing && harness.vm.uiState.value.toWatchFrozen }
        assertNull(harness.vm.failures.current.value)

        friendsFail = true
        val before = friendsRequests.get()
        withContext(main.dispatcher) { harness.vm.refresh() }
        awaitUntil("失败提示发出") { harness.vm.failures.current.value != null }

        assertEquals(before + ContentDomains.size, friendsRequests.get(), "下拉刷新重取五类")
        val friends = harness.vm.uiState.value.friends
        assertTrue(friends is Load.Ready, "已有内容留着：$friends")
        assertEquals(1, friends.value.single().subject.id)
        assertNotNull(harness.vm.failures.current.value?.retry, "提示带重试")
        Unit
    }

    /** 条目 1 的服务端状态：只看过第 1 集。 */
    private val subject1StatesJson = """
        {"total":1,"limit":100,"offset":0,"data":[{"episode":{"id":101,"type":0,"sort":1.0,"name":"","name_cn":"","duration":"","airdate":"","comment":0,"desc":"","disc":0},"type":2,"updated_at":1000}]}
    """.trimIndent()

    private val subject1EpisodesJson = """
        {"data":[
          {"id":101,"subject_id":1,"type":0,"name":"ep1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":1.0,"airdate":"2026-09-20"},
          {"id":102,"subject_id":1,"type":0,"name":"ep2","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":2.0,"airdate":"2026-09-27"},
          {"id":103,"subject_id":1,"type":0,"name":"ep3","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":3.0,"airdate":"2026-10-04"}],
         "total":3,"limit":100,"offset":0}
    """.trimIndent()

    @Test
    fun `EP按钮点击立刻乐观更新 停手后只提交一次 成功不提示`() = runBlocking {
        val batchCommitCount = AtomicInteger()
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(p1HomeJson, HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                "/p1/me/friends/subject-collections" -> respond(friendsResponseJson, HttpStatusCode.OK, json)
                "/v0/episodes" -> respond(subject1EpisodesJson, HttpStatusCode.OK, json)
                "/v0/users/-/collections/1/episodes" -> if (request.method == HttpMethod.Get) {
                    respond(subject1StatesJson, HttpStatusCode.OK, json)
                } else {
                    batchCommitCount.incrementAndGet()
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }

        awaitUntil("待看条目加载") { harness.vm.uiState.value.toWatch.isNotEmpty() }
        commitGate.close()
        withContext(main.dispatcher) { harness.vm.onEpButtonTap(harness.vm.uiState.value.toWatch.first().card) }

        // 乐观：第 2 集立刻算看过，条目追平后离开「待看」，时间表里仍在
        awaitUntil("乐观更新生效") {
            val state = harness.vm.uiState.value
            state.pendingVisuals[1]?.count == 1 && state.toWatch.isEmpty() &&
                state.scheduleGroups.flatMap { it.items }.any { it.subject.id == 1 }
        }
        assertEquals(0, batchCommitCount.get(), "停手窗口内不得发出写入")

        commitGate.open()
        awaitUntil("停手后提交一次") { batchCommitCount.get() == 1 && harness.vm.uiState.value.pendingVisuals.isEmpty() }
        assertNull(harness.vm.failures.current.value)
    }

    @Test
    fun `书籍EP按钮乐观加一话 停手后PATCH一次`() = runBlocking {
        val patchBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness { request ->
            when {
                request.url.encodedPath == "/p1/home" -> respond(p1HomeJson, HttpStatusCode.OK, json)
                request.url.encodedPath == "/v0/users/fixture_user/collections" ->
                    respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                request.url.encodedPath == "/p1/me/friends/subject-collections" -> respond(friendsResponseJson, HttpStatusCode.OK, json)
                request.method == HttpMethod.Patch && request.url.encodedPath == "/v0/users/-/collections/3" -> {
                    patchBodies.add(request.body.toByteArray().decodeToString())
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }

        awaitUntil("继续条目加载") { harness.vm.uiState.value.continueItems.isNotEmpty() }
        val book = harness.vm.uiState.value.continueItems.first { it.collection.subjectId == 3 }
        commitGate.close()
        withContext(main.dispatcher) { harness.vm.onEpButtonTap(book.card) }

        awaitUntil("话数乐观加一") {
            harness.vm.uiState.value.continueItems.first { it.collection.subjectId == 3 }.collection.epStatus == 6
        }
        assertTrue(patchBodies.isEmpty(), "停手窗口内不得发出写入")
        commitGate.open()
        awaitUntil("停手后提交一次") { patchBodies.size == 1 }
        assertTrue(patchBodies[0].contains(""""ep_status":6"""), patchBodies[0])
    }

    @Test
    fun `无缓存且首页请求失败 整页状态是失败而不是空内容`() = runBlocking {
        val harness = createHarness { respond(content = "{}", status = HttpStatusCode.InternalServerError) }
        awaitUntil("失败后结束骨架") { harness.vm.uiState.value.progress is Load.Failed }
        assertTrue(harness.vm.uiState.value.toWatch.isEmpty())
        // 整页已是重试页：「也在进行」的失败不再叠一条提示条（失败态不会再变回去，这里断言不依赖时序）
        assertNull(harness.vm.failures.current.value)
    }

    @Test
    fun `加一期间待看顺序冻结 下拉刷新后按最新相关时刻重排`() = runBlocking {
        val nowSec = testClock.time / 1000L
        val weekAgoSec = nowSec - 7L * 24 * 60 * 60
        val initialHome = p1HomeJson
            .replaceFirst("\"collection\": {\"status\": 2, \"updatedAt\": ${nowSec - 86400}}", "\"collection\": null")
            .replaceFirst("\"collection\": {\"status\": 2, \"updatedAt\": $nowSec}", "\"collection\": null")
            .replaceFirst("\"updatedAt\": $nowSec", "\"updatedAt\": $weekAgoSec")
            .replaceFirst("\"updatedAt\": $nowSec", "\"updatedAt\": $weekAgoSec")
        // 刷新回来的数据里 1 号的第一集已在服务端标为看过（此刻），2 号原样：按最新相关时刻 1 号该排到前面。
        val refreshedHome = initialHome.replaceFirst(
            "\"collection\": null",
            "\"collection\": {\"status\": 2, \"updatedAt\": $nowSec}",
        )
        val noneEpisodeStates = """{"total":3,"limit":100,"offset":0,"data":[
            {"episode":{"id":101,"type":0,"sort":1.0,"name":"e1","name_cn":"","duration":"","airdate":"2026-09-20","comment":0,"desc":"","disc":0},"type":0,"updated_at":0},
            {"episode":{"id":102,"type":0,"sort":2.0,"name":"e2","name_cn":"","duration":"","airdate":"2026-09-27","comment":0,"desc":"","disc":0},"type":0,"updated_at":0},
            {"episode":{"id":103,"type":0,"sort":3.0,"name":"e3","name_cn":"","duration":"","airdate":"2026-10-04","comment":0,"desc":"","disc":0},"type":0,"updated_at":0}
        ]}"""
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val homeRequests = AtomicInteger()
        val batchCommits = AtomicInteger()
        val refreshRequested = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val harness = createHarness(community = false) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> {
                    val call = homeRequests.incrementAndGet()
                    if (call == 1) {
                        respond(initialHome, HttpStatusCode.OK, json)
                    } else {
                        refreshRequested.complete(Unit)
                        releaseRefresh.await()
                        respond(refreshedHome, HttpStatusCode.OK, json)
                    }
                }
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                "/v0/episodes" -> respond(subject1EpisodesJson, HttpStatusCode.OK, json)
                "/v0/users/-/collections/1/episodes" -> if (request.method == HttpMethod.Get) {
                    respond(noneEpisodeStates, HttpStatusCode.OK, json)
                } else {
                    batchCommits.incrementAndGet()
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }

        awaitUntil("两部待看按最近相关时刻初次排序") {
            harness.vm.uiState.value.toWatch.map { it.card.subject.id } == listOf(2, 1)
        }
        val firstOrder = harness.vm.uiState.value.toWatch.map { it.card.subject.id }
        withContext(main.dispatcher) {
            harness.vm.onEpButtonTap(harness.vm.uiState.value.toWatch.single { it.card.subject.id == 1 }.card)
        }

        awaitUntil("加一提交完成但原位置保持") {
            batchCommits.get() == 1 && harness.vm.uiState.value.pendingVisuals.isEmpty() &&
                harness.vm.uiState.value.toWatch.map { it.card.subject.id } == firstOrder &&
                harness.vm.uiState.value.toWatch.single { it.card.subject.id == 1 }.airedUnwatchedCount == 1
        }
        assertEquals(listOf(2, 1), harness.vm.uiState.value.toWatch.map { it.card.subject.id })

        withContext(main.dispatcher) { harness.vm.refresh() }
        awaitUntil("下拉刷新请求开始") { refreshRequested.isCompleted }
        harness.episodeWatchStateDao.upsertAll(listOf(
            EpisodeWatchStateEntity(
                episodeId = 101, subjectId = 1, accountId = ACCOUNT_ID,
                status = EpisodeStatus.DONE.value, updatedAt = testClock.time + 1_000L,
            ),
        ))
        val prematureOrder = withTimeoutOrNull(500L) {
            harness.vm.uiState.first { state -> state.toWatch.map { it.card.subject.id } != listOf(2, 1) }
        }
        assertNull(prematureOrder, "刷新结果到达前不能用旧首页快照冻结新逐集时间")
        releaseRefresh.complete(Unit)
        awaitUntil("下拉刷新重新按最新相关时刻排序") {
            harness.vm.uiState.value.toWatch.map { it.card.subject.id } == listOf(1, 2)
        }
    }

    @Test
    fun `缓存先按最近相关时刻显示 网络结果到达后按网络顺序冻结`() = runBlocking {
        val nowSec = testClock.time / 1000L
        val weekAgoSec = nowSec - 7L * 24 * 60 * 60
        // 网络结果：条目 2 昨天看过第 1 集（recency=昨天），条目 1 一集没看（recency=09-27 播出日）→ [2, 1]
        val networkHome = p1HomeJson
            .replaceFirst("\"collection\": {\"status\": 2, \"updatedAt\": ${nowSec - 86400}}", "\"collection\": null")
            .replaceFirst("\"collection\": {\"status\": 2, \"updatedAt\": $nowSec}", "\"collection\": null")
            .replaceFirst("\"updatedAt\": $nowSec", "\"updatedAt\": $weekAgoSec")
            .replaceFirst("\"updatedAt\": $nowSec", "\"updatedAt\": $weekAgoSec")
        // 缓存（上次打开时的快照）：条目 1 的收藏时间在两小时后，最近相关时刻最新 → [1, 2]
        val cachedHome = networkHome.replaceFirst("\"updatedAt\": $weekAgoSec", "\"updatedAt\": ${nowSec + 2 * 60 * 60}")
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val homeRequests = AtomicInteger()
        val releaseNetwork = CompletableDeferred<Unit>()
        val harness = createHarness(
            community = false,
            beforeViewModel = { repository ->
                repository.getHomeProgress().first { it is Load.Ready && !it.fromCache }
                // 缓存过期：VM 进首页时先回显缓存，再后台请求网络
                testClock.time += CacheTtl.HOME_PROGRESS + 1
            },
        ) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> {
                    val call = homeRequests.incrementAndGet()
                    if (call == 1) {
                        respond(cachedHome, HttpStatusCode.OK, json)
                    } else {
                        releaseNetwork.await()
                        respond(networkHome, HttpStatusCode.OK, json)
                    }
                }
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }

        awaitUntil("缓存那一拍按缓存的最近相关时刻显示") {
            harness.vm.uiState.value.toWatch.map { it.card.subject.id } == listOf(1, 2)
        }
        releaseNetwork.complete(Unit)
        awaitUntil("网络结果到达后按网络的最近相关时刻重排") {
            harness.vm.uiState.value.toWatch.map { it.card.subject.id } == listOf(2, 1)
        }

        // 冻结后条目 1 刚看过（等同点了 +1）：最近相关时刻变成最新，但位置不动
        harness.episodeWatchStateDao.upsertAll(listOf(
            EpisodeWatchStateEntity(
                episodeId = 101, subjectId = 1, accountId = ACCOUNT_ID,
                status = EpisodeStatus.DONE.value, updatedAt = testClock.time + 60_000L,
            ),
        ))
        awaitUntil("逐集状态回写到首页") {
            harness.vm.uiState.value.toWatch.single { it.card.subject.id == 1 }.airedUnwatchedCount == 1
        }
        assertEquals(listOf(2, 1), harness.vm.uiState.value.toWatch.map { it.card.subject.id })
    }

    @Test
    fun `继续区分类消失退回全部后 分类再次出现不自动恢复旧筛选`() = runBlocking {
        val nowSec = testClock.time / 1000L
        val homeWithFinishedAnime = """
            {
              "progress": [
                {
                  "subject": {
                    "id": 1, "name": "完结动画", "nameCN": "完结动画", "type": 2, "eps": 2, "volumes": 0,
                    "series": false, "doing": 10, "airDate": "2026-09-20", "weekday": 7,
                    "images": {"large": "l1.jpg", "common": "c1.jpg", "medium": "m1.jpg", "small": "s1.jpg", "grid": "g1.jpg"},
                    "info": "", "metaTags": [], "rating": {"rank": 1, "count": [], "score": 8.0, "total": 10},
                    "locked": false, "nsfw": false
                  },
                  "interest": {
                    "id": 100, "rate": 0, "type": 3, "comment": "", "tags": [],
                    "epStatus": 2, "volStatus": 0, "private": false, "updatedAt": $nowSec
                  },
                  "percent": 1.0, "todayOnAir": false, "lastUnwatchedEp": null,
                  "eps": [
                    {
                      "id": 101, "subjectID": 1, "sort": 1.0, "type": 0, "disc": 0,
                      "name": "ep1", "nameCN": "", "duration": "24m", "airdate": "2026-09-20", "comment": 0, "desc": "",
                      "collection": {"status": 2, "updatedAt": ${nowSec - 86400}}
                    },
                    {
                      "id": 102, "subjectID": 1, "sort": 2.0, "type": 0, "disc": 0,
                      "name": "ep2", "nameCN": "", "duration": "24m", "airdate": "2026-09-27", "comment": 0, "desc": "",
                      "collection": {"status": 2, "updatedAt": $nowSec}
                    }
                  ]
                },
                {
                  "subject": {
                    "id": 3, "name": "完結漫畫", "nameCN": "完结漫画", "type": 1, "eps": 0, "volumes": 0,
                    "series": false, "doing": 10, "airDate": "", "weekday": 0,
                    "images": {"large": "l3.jpg", "common": "c3.jpg", "medium": "m3.jpg", "small": "s3.jpg", "grid": "g3.jpg"},
                    "info": "", "metaTags": [], "rating": {"rank": 1, "count": [], "score": 8.0, "total": 10},
                    "locked": false, "nsfw": false
                  },
                  "interest": {
                    "id": 300, "rate": 0, "type": 3, "comment": "", "tags": [],
                    "epStatus": 5, "volStatus": 2, "private": false, "updatedAt": ${nowSec + 10}
                  },
                  "percent": 0.0, "todayOnAir": false, "lastUnwatchedEp": null, "eps": []
                }
              ],
              "timeline": [], "groupTopics": [], "famousGroups": [], "hotSubjectTopics": [], "calendar": {}
            }
        """.trimIndent()
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness(community = false) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(homeWithFinishedAnime, HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }

        // 初始：继续区有动画（已播全看且无未来集）与书籍两个分类
        awaitUntil("继续区出现动画与书籍两个分类") {
            harness.vm.uiState.value.continueChips == listOf(SubjectType.ANIME, SubjectType.BOOK) &&
                harness.vm.uiState.value.continueItems.size == 2
        }

        // 用户点选「动画」
        harness.vm.onContinueFilterChange(SubjectType.ANIME)
        awaitUntil("生效过滤为动画") {
            harness.vm.uiState.value.continueFilter == SubjectType.ANIME &&
                harness.vm.uiState.value.continueItems.map { it.card.subject.id } == listOf(1)
        }

        // 动画第 2 集被改为未看 → 归入待看区，从继续区消失
        harness.episodeWatchStateDao.upsertAll(
            listOf(
                EpisodeWatchStateEntity(
                    episodeId = 102,
                    subjectId = 1,
                    accountId = ACCOUNT_ID,
                    status = EpisodeStatus.NONE.value,
                    updatedAt = testClock.time + 1000L,
                ),
            ),
        )

        // 生效过滤退回全部（null），展示全部继续条目（此时仅剩书籍 3）
        awaitUntil("动画被待看吸走后退回全部") {
            harness.vm.uiState.value.continueFilter == null &&
                harness.vm.uiState.value.continueItems.map { it.card.subject.id } == listOf(3)
        }

        // 动画第 2 集重新标为已看 → 动画重新回到继续区
        harness.episodeWatchStateDao.upsertAll(
            listOf(
                EpisodeWatchStateEntity(
                    episodeId = 102,
                    subjectId = 1,
                    accountId = ACCOUNT_ID,
                    status = EpisodeStatus.DONE.value,
                    updatedAt = testClock.time + 2000L,
                ),
            ),
        )

        awaitUntil("动画重新回到继续区") {
            harness.vm.uiState.value.continueChips == listOf(SubjectType.ANIME, SubjectType.BOOK)
        }

        // 关键断言：不得自动复活旧请求，生效过滤仍必须为 null（全部），展示全部条目
        assertNull(harness.vm.uiState.value.continueFilter, "分类重现后不得自动恢复旧筛选，必须保持全部")
        assertEquals(listOf(3, 1), harness.vm.uiState.value.continueItems.map { it.card.subject.id })
    }

    @Test
    fun `在途的旧收敛结果不能覆盖用户随后选择的书籍`() = runBlocking {
        val scheduler = StandardTestDispatcher()
        val snapshots = AtomicInteger()
        val dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> =
                super.observeCurrent(accountId).onEach { snapshots.incrementAndGet() }
        }
        val harness = createHarness(community = false, collectionDao = dao, computationDispatcher = scheduler) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(p1HomeJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("初次准备与接受已完成") {
            scheduler.scheduler.runCurrent()
            harness.vm.uiState.value.toWatchFrozen
        }
        // 捕获一次明确的接受事件；Deferred 完成后不会再退回未完成，不能用读取中间快照当屏障。
        val acceptedBook = scope.async { harness.vm.uiState.first { it.continueFilter == SubjectType.BOOK } }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = scope.launch(main.dispatcher) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "测试主线程闸门未释放" }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val beforeOld = snapshots.get()
            harness.vm.onContinueFilterChange(SubjectType.GAME)
            awaitUntil("不存在分类的旧结果已在后台准备") { scheduler.scheduler.runCurrent(); snapshots.get() > beforeOld }
            val beforeNew = snapshots.get()
            harness.vm.onContinueFilterChange(SubjectType.BOOK)
            awaitUntil("书籍的新结果已在后台准备") { scheduler.scheduler.runCurrent(); snapshots.get() > beforeNew }
        } finally {
            release.countDown()
            blocker.join()
        }
        awaitUntil("书籍请求已被界面接受") { scheduler.scheduler.runCurrent(); acceptedBook.isCompleted }
        acceptedBook.await()
        // 后台按顺序发布，书籍被接受说明此前旧请求已经处理完。
        withContext(main.dispatcher) { }
        assertEquals(SubjectType.BOOK, harness.vm.uiState.value.continueFilter)
        assertTrue(harness.vm.uiState.value.continueItems.all { it.card.subject.type == SubjectType.BOOK })
        assertTrue(harness.vm.uiState.value.continueItems.isNotEmpty())
    }

    // ---------- 区块合成（纯函数） ----------

    private val today = LocalDate.of(2026, 9, 29)

    private fun book(id: Int, updatedAt: Long) = HomeProgressItemBo(
        subject = SubjectBo(id = id, type = SubjectType.BOOK, name = "b$id", nameCn = ""),
        eps = emptyList(),
        updatedAt = updatedAt,
    )

    private fun anime(id: Int, eps: List<EpisodeBo>, updatedAt: Long) = HomeProgressItemBo(
        subject = SubjectBo(id = id, type = SubjectType.ANIME, name = "a$id", nameCn = ""),
        eps = eps,
        updatedAt = updatedAt,
    )

    private fun real(id: Int, eps: List<EpisodeBo>, updatedAt: Long) = HomeProgressItemBo(
        subject = SubjectBo(id = id, type = SubjectType.REAL, name = "r$id", nameCn = ""),
        eps = eps,
        updatedAt = updatedAt,
    )

    private fun episode(id: Int, sort: Double, airdate: String) = EpisodeBo(
        id = id, subjectId = id / 100, type = 0, name = "e$id", nameCn = "", sort = sort, airdate = airdate,
    )

    private fun collection(id: Int, type: SubjectType, status: CollectionType = CollectionType.DOING) =
        UserCollectionBo(subjectId = id, subjectType = type, type = status)

    @Test
    fun `继续按首页快照时间倒序并截断到上限`() {
        val items = (1..7).map { book(it, updatedAt = it.toLong()) }
        val collections = (1..7).map { collection(it, SubjectType.BOOK) }
        val all = sectionsOf(items, collections, emptyMap(), null, today)
        assertEquals(listOf(7, 6, 5, 4, 3), all.continueItems.map { it.collection.subjectId })
        assertEquals(CONTINUE_LIMIT, all.continueItems.size)
    }

    @Test
    fun `继续 chip 由实际条目派生 被待看吸走的动画不出现`() {
        // 动画有已播未看正片 → 整部归待看，不再落进继续；书籍与已完结三次元留在继续
        val absorbedAnime = anime(1, listOf(episode(1001, 1.0, "2026-09-20")), updatedAt = 30)
        val bookItem = book(2, updatedAt = 20)
        val finishedReal = real(3, listOf(episode(3001, 1.0, "2026-09-01")), updatedAt = 10)
        val items = listOf(absorbedAnime, bookItem, finishedReal)
        val collections = items.map { collection(it.subject.id, it.subject.type) }

        val sections = sectionsOf(
            items,
            collections,
            mapOf(3 to mapOf(3001 to EpisodeStatus.DONE)),
            null,
            today,
        )

        assertEquals(listOf(2, 3), sections.continueItems.map { it.collection.subjectId })
        // 顺序沿用 ContentDomains（动画、书籍、游戏、音乐、三次元）；被吸走的动画不在 chip 里
        assertEquals(listOf(SubjectType.BOOK, SubjectType.REAL), sections.continueChips)
    }

    @Test
    fun `继续 chip 由实际条目派生 只进时间表的连载动画不出现`() {
        // 连载已追平（下一集未播）→ 仅时间表；继续区里没有动画
        val scheduleOnlyAnime = anime(
            1,
            listOf(episode(1001, 1.0, "2026-09-20"), episode(1002, 2.0, "2026-10-06")),
            updatedAt = 30,
        )
        val bookItem = book(2, updatedAt = 20)
        val finishedReal = real(3, emptyList(), updatedAt = 10)
        val items = listOf(scheduleOnlyAnime, bookItem, finishedReal)
        val collections = items.map { collection(it.subject.id, it.subject.type) }

        val sections = sectionsOf(items, collections, mapOf(1 to mapOf(1001 to EpisodeStatus.DONE)), null, today)

        assertEquals(listOf(2, 3), sections.continueItems.map { it.collection.subjectId })
        assertFalse(sections.continueChips.contains(SubjectType.ANIME))
        assertEquals(listOf(SubjectType.BOOK, SubjectType.REAL), sections.continueChips)
    }

    @Test
    fun `继续只剩一个分类时 chip 清单为空`() {
        val items = listOf(book(1, updatedAt = 10), book(2, updatedAt = 20))
        val collections = items.map { collection(it.subject.id, SubjectType.BOOK) }

        val sections = sectionsOf(items, collections, emptyMap(), null, today)

        assertTrue(sections.continueChips.isEmpty())
        assertEquals(listOf(2, 1), sections.continueItems.map { it.collection.subjectId })
    }

    @Test
    fun `继续选中的分类消失时生效过滤退回全部`() {
        // 请求里选了「动画」，但继续区里已被待看吸走、一个动画都不剩
        val items = (1..3).map { book(it, updatedAt = it.toLong()) }
        val collections = (1..3).map { collection(it, SubjectType.BOOK) }

        val sections = sectionsOf(items, collections, emptyMap(), SubjectType.ANIME, today)

        assertNull(sections.continueFilter)
        assertEquals(listOf(3, 2, 1), sections.continueItems.map { it.collection.subjectId })
    }

    @Test
    fun `继续选中的分类仍在时生效过滤保留并只列该分类`() {
        val bookItem = book(1, updatedAt = 10)
        val finishedReal = real(2, emptyList(), updatedAt = 20)
        val items = listOf(bookItem, finishedReal)
        val collections = items.map { collection(it.subject.id, it.subject.type) }

        val sections = sectionsOf(items, collections, emptyMap(), SubjectType.REAL, today)

        assertEquals(SubjectType.REAL, sections.continueFilter)
        assertEquals(listOf(2), sections.continueItems.map { it.collection.subjectId })
        assertEquals(listOf(SubjectType.BOOK, SubjectType.REAL), sections.continueChips)
    }

    @Test
    fun `书籍条目恒在继续区 不进待看与时间表`() {
        val bookItem = book(1, updatedAt = 10)

        val sections = sectionsOf(listOf(bookItem), listOf(collection(1, SubjectType.BOOK)), emptyMap(), null, today)

        assertEquals(listOf(1), sections.continueItems.map { it.collection.subjectId })
        assertTrue(sections.toWatch.isEmpty())
        assertTrue(sections.scheduleGroups.isEmpty())
    }

    @Test
    fun `待看超限的条目回落继续区`() {
        // 三部同样新鲜的已播未看：轮播只放得下前两部，第三部没在顶部出现 → 在「继续」里可找到
        fun fresh(id: Int, type: SubjectType, epId: Int) = HomeProgressItemBo(
            subject = SubjectBo(id = id, type = type, name = "s$id", nameCn = ""),
            eps = listOf(episode(epId, 1.0, "2026-09-28")),
            updatedAt = LocalDate.of(2026, 9, 28).atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli(),
        )
        val items = listOf(fresh(1, SubjectType.ANIME, 1001), fresh(2, SubjectType.ANIME, 2001), fresh(3, SubjectType.REAL, 3001))
        val collections = items.map { collection(it.subject.id, it.subject.type) }

        val sections = sectionsOf(items, collections, emptyMap(), null, today, toWatchLimit = 2)

        assertEquals(listOf(1, 2), sections.toWatch.map { it.card.subject.id })
        // 没在顶部出现的第三部成为继续候选；chip 从候选派生（此时只剩三次元一个分类 → 不画）
        assertEquals(listOf(3), sections.continueItems.map { it.collection.subjectId })
        assertTrue(sections.continueChips.isEmpty())
        assertFalse(sections.continueCollapsed)
    }

    @Test
    fun `冷却出局的待看条目回落继续区`() {
        // 已播未看但最近相关时刻在 10 天冷却线之外 → 不进顶部待看；又无未来集不进时间表 → 只能在继续
        val stale = LocalDate.of(2026, 9, 10).atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli()
        val item = anime(7, listOf(episode(7001, 1.0, "2026-09-05")), updatedAt = stale)
        val sections = sectionsOf(listOf(item), listOf(collection(7, SubjectType.ANIME)), emptyMap(), null, today)

        assertTrue(sections.toWatch.isEmpty())
        assertEquals(listOf(7), sections.continueItems.map { it.collection.subjectId })
        assertTrue(sections.scheduleGroups.isEmpty())
    }

    @Test
    fun `待看与时间表的重叠条目不回落继续 只扣除一次`() {
        // 两部都有已播未看 + 未来集：limit 只放得下一部；另一部虽没进轮播，但仍在时间表里展示 → 不进继续
        fun dual(id: Int, epId: Int, updatedAt: Long) = anime(
            id,
            listOf(episode(epId, 1.0, "2026-09-28"), episode(epId + 1, 2.0, "2026-10-06")),
            updatedAt = updatedAt,
        )
        val fresh = LocalDate.of(2026, 9, 28).atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli()
        val items = listOf(dual(1, 1001, fresh), dual(2, 2001, fresh))
        val collections = items.map { collection(it.subject.id, SubjectType.ANIME) }

        val sections = sectionsOf(items, collections, emptyMap(), null, today, toWatchLimit = 1)

        assertEquals(listOf(1), sections.toWatch.map { it.card.subject.id })
        // 第 2 部在时间表（两部的未来集同一天，两组都有）
        assertEquals(setOf(1, 2), sections.scheduleGroups.flatMap { it.items }.map { it.subject.id }.toSet())
        assertTrue(sections.continueItems.isEmpty())
        assertTrue(sections.continueCollapsed)
    }

    @Test
    fun `所有候选都被顶部覆盖时收成查看全部入口`() {
        val fresh = LocalDate.of(2026, 9, 28).atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli()
        val item = anime(9, listOf(episode(9001, 1.0, "2026-09-28")), updatedAt = fresh)
        val sections = sectionsOf(listOf(item), listOf(collection(9, SubjectType.ANIME)), emptyMap(), null, today)

        assertTrue(sections.continueItems.isEmpty())
        assertTrue(sections.continueChips.isEmpty())
        assertTrue(sections.continueCollapsed)
    }

    @Test
    fun `顶部与候选都为空时不收成查看全部入口`() {
        val sections = sectionsOf(emptyList(), emptyList(), emptyMap(), null, today)

        assertFalse(sections.continueCollapsed)
    }

    @Test
    fun `冻结顺序决定轮播取哪几部 余下回落继续`() {
        val fresh = LocalDate.of(2026, 9, 28).atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli()
        val items = listOf(
            anime(1, listOf(episode(1001, 1.0, "2026-09-28")), updatedAt = fresh + 20),
            anime(2, listOf(episode(2001, 1.0, "2026-09-28")), updatedAt = fresh + 10),
            anime(3, listOf(episode(3001, 1.0, "2026-09-28")), updatedAt = fresh),
        )
        val collections = items.map { collection(it.subject.id, SubjectType.ANIME) }

        // 已冻结的全量顺序是 3,1,2；limit=1 → 轮播取 3，1、2 没展示 → 回落继续（按快照时间倒序 1,2）
        val sections = sectionsOf(
            items, collections, emptyMap(), null, today,
            previousToWatchOrder = listOf(3, 1, 2),
            toWatchLimit = 1,
        )

        assertEquals(listOf(3), sections.toWatch.map { it.card.subject.id })
        assertEquals(listOf(3, 1, 2), sections.toWatchOrder)
        assertEquals(listOf(1, 2), sections.continueItems.map { it.collection.subjectId })
    }

    @Test
    fun `收藏表里不再在看的条目不出现在首页`() {
        val items = listOf(book(1, 1), book(2, 2))
        val collections = listOf(collection(1, SubjectType.BOOK, CollectionType.DONE), collection(2, SubjectType.BOOK))
        assertEquals(listOf(2), sectionsOf(items, collections, emptyMap(), null, today).continueItems.map { it.collection.subjectId })
    }

    @Test
    fun `完结动画仍有已播未看正片时进入待看而非继续`() {
        // 完结番（无未来播出日）但有一集已播未看 → 归待看；相关时刻 2 天前，不在冷却里
        val twoDaysAgo = LocalDate.of(2026, 9, 27).atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli()
        val item = anime(20, listOf(episode(2001, 1.0, "2026-09-15")), updatedAt = twoDaysAgo)
        val sections = sectionsOf(listOf(item), listOf(collection(20, SubjectType.ANIME)), emptyMap(), null, today)

        assertEquals(listOf(20), sections.toWatch.map { it.card.subject.id })
        assertTrue(sections.continueItems.isEmpty())
    }

    @Test
    fun `待看按最近相关时刻排序 同时考虑最近看过时刻与新播出时刻`() {
        fun millis(date: LocalDate, hour: Int = 0, minute: Int = 0, second: Int = 0) =
            date.atTime(hour, minute, second).atZone(BANGUMI_ZONE).toInstant().toEpochMilli()
        // 积压 5 集（> 阈值）→ 不新番时刻，只按最近看过；3 天前看过，没过冷却线，排在最后
        val backlog = anime(
            3,
            (1..5).map { episode(3000 + it, it.toDouble(), LocalDate.of(2026, 9, 29).minusDays((5 - it).toLong()).toString()) },
            updatedAt = millis(LocalDate.of(2026, 9, 26)),
        )
        val watchedYesterday = anime(
            1,
            (1..5).map { episode(1000 + it, it.toDouble(), LocalDate.of(2026, 9, 1).plusDays(it.toLong()).toString()) },
            updatedAt = millis(LocalDate.of(2026, 9, 28), 23, 59, 59),
        )
        val airedToday = anime(
            2,
            listOf(episode(2001, 1.0, "2026-09-20"), episode(2002, 2.0, "2026-09-29"), episode(2003, 3.0, "2026-10-06")),
            updatedAt = millis(LocalDate.of(2026, 9, 22)),
        )
        val items = listOf(backlog, watchedYesterday, airedToday)
        val collections = items.map { collection(it.subject.id, SubjectType.ANIME) }
        val watchStates = mapOf(
            1 to (1001..1004).associateWith { EpisodeStatus.DONE },
            2 to mapOf(2001 to EpisodeStatus.DONE),
        )

        val sections = sectionsOf(items, collections, watchStates, null, today)

        assertEquals(listOf(2, 1, 3), sections.toWatch.map { it.card.subject.id })
    }

    @Test
    fun `待看时刻并列时先少积压再按 subjectId 稳定升序`() {
        val weekAgo = LocalDate.of(2026, 9, 22).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val freshSingle = anime(
            12,
            listOf(episode(12001, 1.0, "2026-09-28"), episode(12002, 2.0, "2026-09-29"), episode(12003, 3.0, "2026-10-06")),
            updatedAt = weekAgo,
        )
        val freshDoubleHigherId = anime(
            10,
            listOf(episode(10001, 1.0, "2026-09-28"), episode(10002, 2.0, "2026-09-29"), episode(10003, 3.0, "2026-10-06")),
            updatedAt = weekAgo,
        )
        val freshDoubleLowerId = anime(
            4,
            listOf(episode(4001, 1.0, "2026-09-28"), episode(4002, 2.0, "2026-09-29"), episode(4003, 3.0, "2026-10-06")),
            updatedAt = weekAgo,
        )
        val items = listOf(freshDoubleHigherId, freshDoubleLowerId, freshSingle)
        val collections = items.map { collection(it.subject.id, SubjectType.ANIME) }
        val latestWatchedAt = items.associate { it.subject.id to weekAgo }

        val sections = sectionsOf(
            items,
            collections,
            mapOf(12 to mapOf(12001 to EpisodeStatus.DONE)),
            null,
            today,
            latestWatchedAt,
        )

        assertEquals(listOf(12, 4, 10), sections.toWatch.map { it.card.subject.id })
    }

    @Test
    fun `待看冷掉的条目不进首页 交给进度页`() {
        fun millis(date: LocalDate, hour: Int = 0) = date.atTime(hour, 0).atZone(BANGUMI_ZONE).toInstant().toEpochMilli()
        // 两份都只有一个已播未看正片；旧的那份 updatedAt 在今天 − 11 天（早于冷却线）
        val fresh = anime(1, listOf(episode(1001, 1.0, "2026-09-20")), updatedAt = millis(LocalDate.of(2026, 9, 28)))
        val cold = anime(2, listOf(episode(2001, 1.0, "2026-09-18")), updatedAt = millis(LocalDate.of(2026, 9, 18)))
        val items = listOf(fresh, cold)
        val collections = items.map { collection(it.subject.id, SubjectType.ANIME) }

        val sections = sectionsOf(items, collections, emptyMap(), null, today)

        // 区块归属仍是待看（冷却只在 rankToWatch 里发生），但首页那份列表里只剩 fresh
        assertEquals(listOf(1), sections.toWatch.map { it.card.subject.id })
        assertTrue(EpisodeTracking.homeSection(SubjectType.ANIME, cold.eps, emptyMap(), today) == HomeSection.TO_WATCH)
    }

    @Test
    fun `待看冷却边界 恰为第 10 天的仍在`() {
        // 今天=2026-09-29，第 10 天线=2026-09-19 00:00；updatedAt 恰为 09-19 0 点 → 仍在
        val onCutoff = anime(
            1,
            listOf(episode(1001, 1.0, "2026-09-15")),
            updatedAt = LocalDate.of(2026, 9, 19).atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli(),
        )
        val sections = sectionsOf(listOf(onCutoff), listOf(collection(1, SubjectType.ANIME)), emptyMap(), null, today)
        assertEquals(listOf(1), sections.toWatch.map { it.card.subject.id })
    }

    /** 端到端纯函数夹具：章节准备与最终展示都调用生产函数，不在测试里复制分流规则。 */
    private fun sectionsOf(
        items: List<HomeProgressItemBo>,
        collections: List<UserCollectionBo>,
        watchStates: Map<Int, Map<Int, EpisodeStatus>>,
        continueFilter: SubjectType?,
        today: LocalDate,
        latestWatchedAt: Map<Int, Long> = emptyMap(),
        previousToWatchOrder: List<Int>? = null,
        toWatchLimit: Int = Int.MAX_VALUE,
        broadcastStarts: Map<Int, Long> = emptyMap(),
    ): HomeSections = io.github.touhougo.chogumi.feature.home.sectionsOf(
        prepareSectionCandidates(items, collections, watchStates, today, latestWatchedAt, broadcastStarts),
        continueFilter, previousToWatchOrder, toWatchLimit,
    )

    // ---------- 追更时间表的播出时刻 ----------

    @Test
    fun `追更时间表同一天内按播出时刻排且海报带时刻_没有时刻表时照原序且没有时刻`() {
        // 两部都在 10-04（周日）播下一集；时刻表给 2 号 20:00、1 号 22:30（北京时间）
        val items = listOf(
            anime(1, listOf(episode(1001, 1.0, "2026-10-04")), updatedAt = initialTime),
            anime(2, listOf(episode(2001, 1.0, "2026-10-04")), updatedAt = initialTime),
        )
        val collections = listOf(collection(1, SubjectType.ANIME), collection(2, SubjectType.ANIME))

        val plain = sectionsOf(items, collections, emptyMap(), null, today).scheduleGroups.single()
        assertEquals(listOf(1 to null, 2 to null), plain.items.map { it.subject.id to it.airing })

        val timed = sectionsOf(
            items, collections, emptyMap(), null, today,
            broadcastStarts = mapOf(1 to SUNDAY_2230, 2 to SUNDAY_2000),
        ).scheduleGroups.single()
        assertEquals(listOf(2 to AiringMinute(20 * 60), 1 to AiringMinute(22 * 60 + 30)), timed.items.map { it.subject.id to it.airing })
    }

    @Test
    fun `放送时刻表一变首页追更时间表跟着重排`() = runBlocking {
        val harness = createHarness(community = false) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(
                    content = p1HomeJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/fixture_user/collections" -> respond(
                    content = """{"data":[],"total":0,"limit":50,"offset":0}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }
        awaitUntil("追更时间表出来") { harness.vm.uiState.value.scheduleGroups.singleOrNull()?.items?.size == 2 }
        assertEquals(listOf(null, null), harness.vm.uiState.value.scheduleGroups.single().items.map { it.airing })

        // 1 号与 2 号的下一集都在 10-04（周日）：2 号 20:00 排到 1 号 22:30 前面
        harness.airingTimeDao.upsertAll(listOf(AiringTimeEntity(1, SUNDAY_2230), AiringTimeEntity(2, SUNDAY_2000)))
        awaitUntil("时间表按时刻重排") {
            harness.vm.uiState.value.scheduleGroups.single().items.map { it.subject.id } == listOf(2, 1)
        }
        assertEquals(listOf("20:00", "22:30"), harness.vm.uiState.value.scheduleGroups.single().items.map { it.airing?.label() })
    }

    @Test
    fun `只开首页也取放送时刻表_追更时间表标上时刻`() = runBlocking {
        val bangumiDataPath = Url(BANGUMI_DATA_URL).encodedPath
        val bangumiDataRequests = AtomicInteger()
        val harness = createHarness(community = false) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(
                    content = p1HomeJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/fixture_user/collections" -> respond(
                    content = """{"data":[],"total":0,"limit":50,"offset":0}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                bangumiDataPath -> {
                    bangumiDataRequests.incrementAndGet()
                    val broadcast = "R/${Instant.ofEpochMilli(SUNDAY_2000)}/P7D"
                    respond(
                        content = """{"items":[{"begin":"2026-10-04T12:00:00Z","broadcast":"$broadcast","sites":[{"site":"bangumi","id":"2"}]}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Text.Plain.toString()),
                    )
                }
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }
        awaitUntil("2 号标上 20:00") {
            harness.vm.uiState.value.scheduleGroups.singleOrNull()?.items?.any { it.subject.id == 2 && it.airing?.label() == "20:00" } == true
        }
        assertEquals(1, bangumiDataRequests.get(), "一次打开只取一次")
    }

    // ---------- 待看条数上限（limit） ----------

    @Test
    fun `后台先准备两拍 主线程依次接受时首拍冻结顺序不能被后拍重排`() = runBlocking {
        val scheduler = StandardTestDispatcher()
        val snapshots = AtomicInteger()
        val dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> =
                super.observeCurrent(accountId).onEach { snapshots.incrementAndGet() }
        }
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness(community = false, collectionDao = dao, computationDispatcher = scheduler) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(2), HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val load = withContext(main.dispatcher) {
            val children = harness.vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
            harness.vm.refresh()
            harness.vm.viewModelScope.coroutineContext[Job]!!.children.single { it !in children }
        }
        load.join()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = scope.launch(main.dispatcher) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "测试主线程闸门未释放" }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // 计算调度器由测试推进，页面的收藏观察者也在它上面（与生产同一个 computationDispatcher）；会话已在内存、假 DAO 不换线程，
            // 一轮快照读取与候选准备不离开它：读取开始之后推到空，这一拍就已排进主线程队列。
            val beforeFirst = snapshots.get()
            awaitUntil("第一拍开始读快照") { scheduler.scheduler.runCurrent(); snapshots.get() >= beforeFirst + 2 } // 长期订阅一拍 + 快照一拍
            scheduler.scheduler.runCurrent()
            val firstReads = snapshots.get()
            // 第二部的特别篇刚标为看过：相关时刻变新，但正片仍未看，不离开候选；单看这一拍，第二部排在前面。
            harness.episodeWatchStateDao.upsertAll(listOf(
                EpisodeWatchStateEntity(2999, 2, ACCOUNT_ID, EpisodeStatus.DONE.value, testClock.time + 1_000),
            ))
            awaitUntil("第二拍开始读快照") { scheduler.scheduler.runCurrent(); snapshots.get() > firstReads }
            scheduler.scheduler.runCurrent()
        } finally {
            release.countDown()
            blocker.join()
        }
        // 两拍都已排在主线程闸门之后；主线程屏障排在它们后面。
        withContext(main.dispatcher) { }
        assertTrue(harness.vm.uiState.value.toWatchFrozen)
        assertEquals(listOf(1, 2), harness.vm.uiState.value.toWatch.map { it.card.subject.id })
    }

    /** 造 N 份待看候选：i 越小，最近相关时刻越新，故顺序为 1..N。 */
    private fun candidatesJson(count: Int): String {
        val nowSec = testClock.time / 1000L
        val items = (1..count).map { i ->
            val watchedSec = nowSec - i * 60L // i 越小越新
            """
                {
                  "subject": {
                    "id": $i, "name": "s$i", "nameCN": "s$i", "type": 2, "eps": 1, "volumes": 0,
                    "series": false, "doing": 1, "airDate": "2026-09-20", "weekday": 7,
                    "images": {"large": "l$i.jpg", "common": "c$i.jpg", "medium": "m$i.jpg", "small": "s$i.jpg", "grid": "g$i.jpg"},
                    "info": "", "metaTags": [],
                    "rating": {"rank": 1, "count": [], "score": 8.0, "total": 10}, "locked": false, "nsfw": false
                  },
                  "interest": {
                    "id": ${i * 100}, "rate": 0, "type": 3, "comment": "", "tags": [],
                    "epStatus": 0, "volStatus": 0, "private": false, "updatedAt": $watchedSec
                  },
                  "percent": 0.0, "todayOnAir": false, "lastUnwatchedEp": {"id": ${i * 100 + 1}, "sort": 1.0},
                  "eps": [
                    {
                      "id": ${i * 100 + 1}, "subjectID": $i, "sort": 1.0, "type": 0, "disc": 0,
                      "name": "e1", "nameCN": "", "duration": "24m", "airdate": "2026-09-20", "comment": 0, "desc": "",
                      "collection": null
                    }
                  ]
                }
            """.trimIndent()
        }
        return """{"progress": [${items.joinToString(",")}], "timeline": [], "groupTopics": [], "famousGroups": [], "hotSubjectTopics": [], "calendar": {}}"""
    }

    private suspend fun createLimitHarness(count: Int, limit: Int = ToWatchLimit.DEFAULT): Harness {
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        return createHarness(community = false, initialLimit = limit) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(count), HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun `待看只显示 limit 限定的前几部`() = runBlocking {
        // 12 份候选，默认 limit=8 → 只显示排序前的 8 部（recency 最新的 1..8）
        val harness = createLimitHarness(12)
        awaitUntil("待看加载并截断完成排序冻结") {
            harness.vm.uiState.value.toWatchFrozen && harness.vm.uiState.value.toWatch.size == 8
        }
        assertEquals((1..8).toList(), harness.vm.uiState.value.toWatch.map { it.card.subject.id })
    }

    @Test
    fun `待看超限的条目经完整 VM 通路回落继续区`() = runBlocking {
        // 12 份候选默认 limit=8：第 9..12 名没在顶部展示，也没有未来集 → 「继续」里能找到它们
        val harness = createLimitHarness(12)
        awaitUntil("本轮待看顺序已冻结") { harness.vm.uiState.value.toWatchFrozen }
        assertEquals(listOf(9, 10, 11, 12), harness.vm.uiState.value.continueItems.map { it.collection.subjectId })
        assertFalse(harness.vm.uiState.value.continueCollapsed)
    }

    @Test
    fun `改 limit 立即生效且保持冻结顺序`() = runBlocking {
        val harness = createLimitHarness(12)
        awaitUntil("默认 8 部完成排序冻结") {
            harness.vm.uiState.value.toWatchFrozen && harness.vm.uiState.value.toWatch.size == 8
        }

        harness.testPrefs.repository.setToWatchLimit(5)
        awaitUntil("改成 5 部") { harness.vm.uiState.value.toWatch.size == 5 }
        // 冻结顺序不变，只是少取几个：仍是 1..5
        assertEquals((1..5).toList(), harness.vm.uiState.value.toWatch.map { it.card.subject.id })

        harness.testPrefs.repository.setToWatchLimit(12)
        awaitUntil("改成 12 部") { harness.vm.uiState.value.toWatch.size == 12 }
        assertEquals((1..12).toList(), harness.vm.uiState.value.toWatch.map { it.card.subject.id })
    }

    @Test
    fun `冻结后标记一集 其他可见条目不被挤出`() = runBlocking {
        // 9 份候选、limit=8：排序为 1..9，冻结后可见 1..8，第 9 名在外
        val harness = createLimitHarness(9)
        awaitUntil("冻结出前 8 部") {
            harness.vm.uiState.value.toWatchFrozen && harness.vm.uiState.value.toWatch.size == 8
        }
        assertEquals((1..8).toList(), harness.vm.uiState.value.toWatch.map { it.card.subject.id })

        // 第 1 部离开待看 → 其余 7 部原位（顺序冻结），第 9 名补到末尾
        harness.setSubjectDone(1)
        awaitUntil("第 1 部离开待看且第 9 名补位") {
            harness.vm.uiState.value.toWatch.map { it.card.subject.id } == (2..9).toList()
        }
    }

    @Test
    fun `某部追平离开待看 下一名补到末尾`() = runBlocking {
        // 与上一条同一机制，但用 limit=5、6 份候选：可见 1..5，第 6 名在外；
        // 第 1 部离开后补进来的是冻结顺序里的第 6 名，落在末尾（不是甩到最前）
        val harness = createLimitHarness(6, limit = 5)
        awaitUntil("冻结出前 5 部") {
            harness.vm.uiState.value.toWatchFrozen && harness.vm.uiState.value.toWatch.size == 5
        }
        assertEquals((1..5).toList(), harness.vm.uiState.value.toWatch.map { it.card.subject.id })

        harness.setSubjectDone(1)
        awaitUntil("第 6 名补到末尾") {
            harness.vm.uiState.value.toWatch.map { it.card.subject.id } == listOf(2, 3, 4, 5, 6)
        }
    }

    @Test
    fun `网络结束但收藏订阅仍为部分旧快照时按完整当前数据冻结`() = runBlocking {
        assertFreezeUsesFreshSnapshot(partial = true)
    }

    @Test
    fun `旧收藏ID齐全但内容未更新时不能冻结旧排序`() = runBlocking {
        assertFreezeUsesFreshSnapshot(partial = false)
    }

    private suspend fun assertFreezeUsesFreshSnapshot(partial: Boolean) {
        val scheduler = StandardTestDispatcher()
        val subscriptions = AtomicInteger()
        val dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> {
                val fresh = super.observeCurrent(accountId)
                if (subscriptions.incrementAndGet() != 1) return fresh
                // 第一份长期订阅滞后；后续新查询与真实数据库一样返回已经完成写入的当前行。
                val rows = store.values.filter { it.accountId == accountId }
                val stale = if (partial) rows.filter { it.subjectId == 3 }
                    else rows.map { if (it.subjectId == 1) it.copy(type = CollectionType.DONE.value) else it }
                return flowOf(stale)
            }
        }
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness(community = false, collectionDao = dao, computationDispatcher = scheduler) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(12), HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        // 精确捕获新一轮 load Job，等网络与写库全部完成后才运行后台合成，确定性制造旧订阅晚到。
        val load = withContext(main.dispatcher) {
            val scopeJob = requireNotNull(harness.vm.viewModelScope.coroutineContext[Job])
            val previous = scopeJob.children.toSet()
            harness.vm.refresh()
            scopeJob.children.single { it !in previous }
        }
        load.join()
        awaitUntil("本轮排序已冻结") {
            scheduler.scheduler.runCurrent()
            harness.vm.uiState.value.toWatchFrozen
        }
        assertEquals((1..8).toList(), harness.vm.uiState.value.toWatch.map { it.card.subject.id })
    }

    @Test
    fun `也在进行只取在看的游戏与音乐`() {
        val collections = listOf(
            collection(1, SubjectType.GAME),
            collection(2, SubjectType.MUSIC),
            collection(3, SubjectType.GAME, CollectionType.DONE),
            collection(4, SubjectType.ANIME),
        )
        assertEquals(listOf(1, 2), sectionsOf(emptyList(), collections, emptyMap(), null, today).doingItems.map { it.subjectId })
    }

    @Test
    fun `专注模式下好友在看区块不发请求且为空`() = runBlocking {
        var friendsRequested = false
        val harness = createHarness(community = false) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(
                    content = p1HomeJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/fixture_user/collections" -> respond(
                    content = """{"data":[],"total":0,"limit":50,"offset":0}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/p1/me/friends/subject-collections" -> {
                    friendsRequested = true
                    respond(content = "{}", status = HttpStatusCode.OK)
                }
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }

        awaitUntil("首页数据加载完成") { harness.vm.uiState.value.progress !is Load.Pending }
        assertFalse(harness.vm.uiState.value.showFriends)
        assertEquals(Load.Pending, harness.vm.uiState.value.friends)
        assertFalse(friendsRequested, "专注模式下不许向好友端点发起请求")
    }

    @Test
    fun `收藏观察已有内容后失败保留首页并只提示一次`() = runBlocking {
        val failure = CompletableDeferred<Unit>()
        val dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> {
                val source = super.observeCurrent(accountId)
                return flow {
                    emitAll(source.take(1))
                    failure.await()
                    throw IOException("collection database unavailable")
                }
            }
        }
        dao.upsert(
            UserCollectionEntity(
                subjectId = 1,
                subjectType = SubjectType.ANIME.value,
                type = CollectionType.DOING.value,
                epStatus = 0,
                detailsLoaded = true,
                accountId = ACCOUNT_ID,
                updatedAt = testClock.time,
            ),
        )
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness(community = false, collectionDao = dao) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(2), HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("首页已显示收藏中的待看") { harness.vm.uiState.value.toWatch.any { it.card.subject.id == 1 } }
        val before = harness.vm.uiState.value.toWatch

        failure.complete(Unit)
        awaitUntil("存储失败提示到达") { harness.vm.failures.current.value != null }

        val notice = harness.vm.failures.current.value!!
        assertEquals(NoticeText.Reason(Failure.Storage), notice.text)
        assertEquals(before, harness.vm.uiState.value.toWatch)
    }

    @Test
    fun `单次快照读取失败提示并保留首页 重试重新读取`() = runBlocking {
        // 读取持续失败而不是只失败一次：写入引起的在途重算也会读，读到失败又因过期被丢掉，提示要由当前这一轮读出
        val failing = AtomicBoolean(false)
        val reads = AtomicInteger()
        val dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> {
                reads.incrementAndGet()
                return if (failing.get()) flow { throw IOException("snapshot unavailable") }
                else super.observeCurrent(accountId)
            }
        }
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness(community = false, collectionDao = dao) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(2), HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("首页到达") { harness.vm.uiState.value.toWatch.isNotEmpty() }
        val before = harness.vm.uiState.value.toWatch
        failing.set(true)
        withContext(main.dispatcher) { harness.vm.onContinueFilterChange(SubjectType.ANIME) }
        awaitUntil("快照失败提示") { harness.vm.failures.current.value?.text == NoticeText.Reason(Failure.Storage) }
        val notice = harness.vm.failures.current.value!!
        val readCount = reads.get()
        assertEquals(before, harness.vm.uiState.value.toWatch)

        failing.set(false)
        harness.vm.failures.consume(notice)
        withContext(main.dispatcher) { notice.retry!!.invoke() }
        awaitUntil("重试读取") { reads.get() > readCount }
    }

    @Test
    fun `快照失败后成功再失败分别提示 无需观察流再次发射`() = runBlocking {
        val failing = AtomicBoolean(false)
        val reads = AtomicInteger()
        val dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> {
                reads.incrementAndGet()
                return if (failing.get()) flow { throw IOException("snapshot unavailable") }
                else super.observeCurrent(accountId)
            }
        }
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness(community = false, collectionDao = dao, initialLimit = 5) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(12), HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("首页到达") { harness.vm.uiState.value.toWatch.isNotEmpty() }
        failing.set(true)
        withContext(main.dispatcher) { harness.vm.onContinueFilterChange(SubjectType.ANIME) }
        awaitUntil("第一次失败提示") { harness.vm.failures.current.value != null }
        harness.vm.failures.consume(harness.vm.failures.current.value!!)
        failing.set(false)

        // 8 部进待看、4 部留在继续：请求的「动画」仍然生效，不会多一轮退回「全部」的重算；下一次读取就是下面这次切换引起的。
        harness.testPrefs.repository.setToWatchLimit(8)
        awaitUntil("成功快照已发布") { harness.vm.uiState.value.toWatch.size == 8 }
        val beforeFailure = reads.get()
        failing.set(true)
        withContext(main.dispatcher) { harness.vm.onContinueFilterChange(SubjectType.BOOK) }
        awaitUntil("第二次快照已读") { reads.get() > beforeFailure }
        awaitUntil("第二次失败提示") { harness.vm.failures.current.value != null }
        assertEquals(NoticeText.Reason(Failure.Storage), harness.vm.failures.current.value!!.text)
    }

    @Test
    fun `旧一轮快照失败不提示 已开始的新刷新`() = runBlocking {
        val failNext = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val nextRead = CompletableDeferred<Unit>()
        val previousFinished = AtomicBoolean(false)
        val dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> {
                if (!failNext.getAndSet(false)) {
                    if (previousFinished.get()) nextRead.complete(Unit)
                    return super.observeCurrent(accountId)
                }
                return flow {
                    started.complete(Unit)
                    release.await()
                    previousFinished.set(true)
                    finished.complete(Unit)
                    throw IOException("old snapshot failed")
                }
            }
        }
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val harness = createHarness(community = false, collectionDao = dao) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(2), HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("首页到达") { harness.vm.uiState.value.toWatch.isNotEmpty() }
        failNext.set(true)
        withContext(main.dispatcher) { harness.vm.onContinueFilterChange(SubjectType.ANIME) }
        started.await()
        withContext(main.dispatcher) { harness.vm.refresh() }
        release.complete(Unit)
        finished.await()
        nextRead.await()
        // 下一次读取在同一条合并流水线上、排在旧一拍发出之后：旧一拍若要提示，此时已排进 Main，这一拍在它后面
        withContext(main.dispatcher) { }
        assertNull(harness.vm.failures.current.value, "旧一轮失败不得覆盖新刷新")
    }

    @Test
    fun `刷新复用已观察的偏好而不重新读取`() = runBlocking {
        val prefs = FaultablePreferencesRepository(resources)
        val harness = createHarness(preferencesOverride = prefs.repository) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(1), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("首页偏好已观察") { harness.vm.uiState.value.showFriends }
        prefs.failReads.set(true)
        withContext(main.dispatcher) { harness.vm.refresh() }
        awaitUntil("刷新结束") { !harness.vm.uiState.value.refreshing && harness.vm.uiState.value.toWatchFrozen }
        assertEquals(0, prefs.failedReads.get())
        assertTrue(harness.vm.uiState.value.showFriends)
    }

    @Test
    fun `偏好读不出时首页提示存储失败 重试读到后区块与好友开关到达`() = runBlocking {
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val prefs = FaultablePreferencesRepository(resources).apply { failReads.set(true) }
        val harness = createHarness(preferencesOverride = prefs.repository) { request ->
            when (request.url.encodedPath) {
                "/p1/home" -> respond(candidatesJson(1), HttpStatusCode.OK, json)
                "/v0/users/fixture_user/collections" -> respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("存储失败提示") { harness.vm.failures.current.value?.text == NoticeText.Reason(Failure.Storage) }
        val retry = assertNotNull(harness.vm.failures.current.value?.retry, "提示带重试")
        // 条数上限没读到，区块等它；好友开关留默认
        assertEquals(Load.Pending, harness.vm.uiState.value.progress)
        assertFalse(harness.vm.uiState.value.showFriends)
        prefs.failReads.set(false)
        withContext(main.dispatcher) { retry() }
        awaitUntil("重试后首页首个结果到达") { harness.vm.uiState.value.progress is Load.Ready }
        awaitUntil("重试后好友开关到达") { harness.vm.uiState.value.showFriends }
    }

    private suspend fun createHarness(
        community: Boolean = true,
        beforeViewModel: suspend (HomeRepository) -> Unit = {},
        collectionDao: FakeUserCollectionDao = FakeUserCollectionDao(),
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
        initialLimit: Int = ToWatchLimit.DEFAULT,
        preferencesOverride: PreferencesRepository? = null,
        handler: io.ktor.client.engine.mock.MockRequestHandler,
    ): Harness {
        if (computationDispatcher is TestDispatcher) computationSchedulers += computationDispatcher.scheduler
        val store = testTokenStore(scope = scope)
        val session = store.beginSession("test-token")
        store.completeSession(session, ACCOUNT_ID, "fixture_user")

        val client = resources.apiClient(MockEngine(handler))


        val subjectDao = FakeSubjectDao()
        val episodeWatchStateDao = FakeEpisodeWatchStateDao()
        val homeProgressDao = FakeHomeProgressDao()
        val episodeDao = FakeEpisodeDao()
        val airingTimeDao = FakeAiringTimeDao()

        // 预置书籍条目 3 的收藏记录
        collectionDao.upsert(
            UserCollectionEntity(
                subjectId = 3,
                subjectType = SubjectType.BOOK.value,
                type = CollectionType.DOING.value,
                rate = 0,
                tagsJson = "[]",
                comment = "",
                epStatus = 5,
                volStatus = 2,
                isPrivate = false,
                updatedAt = 1790640000000L,
                accountId = ACCOUNT_ID,
                detailsLoaded = true,
            ),
        )

        val subjectApi = SubjectApi(client)
        val nextApi = NextApi(client)
        val collectionApi = CollectionApi(client)

        val subjectRepository = testSubjectRepository(
            resources,
            subjectApi, nextApi, subjectDao, episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources), tokenStore = store, clock = testClock,
        )
        val collectionRepository = testCollections(
            resources,
            collectionApi, subjectDao, collectionDao, store, episodeWatchStateDao, episodeDao, clock = testClock,
        )
        val homeRepository = testHomeRepository(
            nextApi = nextApi,
            homeProgressDao = homeProgressDao,
            subjectDao = subjectDao,
            tokenStore = store,
            collections = collectionRepository,
            episodeDao = episodeDao,
            clock = testClock,
        )
        val orchestrator = collectionRepository.progressOrchestrator(subjectRepository)
        val coordinator = collectionRepository.epTapCoordinator(orchestrator, scope, { 0L }, commitGate::await)
        beforeViewModel(homeRepository)

        val testPrefs = TestPreferencesRepository(
            experienceMode = if (community) io.github.touhougo.chogumi.core.model.ExperienceMode.FULL else io.github.touhougo.chogumi.core.model.ExperienceMode.FOCUS,
        )
        testPrefsList += testPrefs
        val preferencesRepository = preferencesOverride ?: testPrefs.repository
        if (initialLimit != ToWatchLimit.DEFAULT) {
            preferencesRepository.setToWatchLimit(initialLimit)
        }

        val vm = withContext(main.dispatcher) {
            HomeViewModel(
                homeRepository = homeRepository,
                collectionStore = collectionRepository.store,
                // 页面的收藏观察者与页面共用计算调度器，同生产的 computationDispatcher；收藏写入一侧照旧
                collectionObserver = CollectionObserver(subjectDao, collectionDao, episodeWatchStateDao, store, computationDispatcher),
                epTapCoordinator = coordinator,
                preferencesRepository = preferencesRepository,
                airingTimeRepository = testAiringTimeRepository(BangumiDataApi(client), airingTimeDao),
                collectionEditing = collectionRepository.editing(subjectRepository, orchestrator),
                appTime = testAppTime(testClock.time),
                computationDispatcher = computationDispatcher,
            )
        }
        viewModels += vm
        return Harness(vm, episodeWatchStateDao, testPrefs, collectionDao, airingTimeDao)
    }

    private data class Harness(
        val vm: HomeViewModel,
        val episodeWatchStateDao: FakeEpisodeWatchStateDao,
        val testPrefs: TestPreferencesRepository,
        private val collectionDao: FakeUserCollectionDao,
        val airingTimeDao: FakeAiringTimeDao,
    ) {
        /**
         * 把某部作品的「在看」收藏改成「看过」——模拟用户标记到最后一集后追平离开待看。
         * 走收藏表的真实写入口，让 [HomeViewModel] 的区块合成重新跑一遍。
         */
        suspend fun setSubjectDone(subjectId: Int) {
            val existing = collectionDao.getBySubjectId(subjectId, ACCOUNT_ID) ?: return
            collectionDao.upsert(existing.copy(type = CollectionType.DONE.value))
        }
    }


    private companion object {
        const val ACCOUNT_ID = 7

        /** 2026-10-04（周日）北京时间 20:00 与 22:30 的每周首播时刻。 */
        val SUNDAY_2000 = LocalDate.of(2026, 10, 4).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val SUNDAY_2230 = LocalDate.of(2026, 10, 4).atTime(14, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
