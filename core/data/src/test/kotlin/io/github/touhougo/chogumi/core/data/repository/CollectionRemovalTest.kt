package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** 网页操作必须经回读确认，再删当前账号的本地收藏；失败与账号切换均保留缓存。 */
class CollectionRemovalTest {
    @get:org.junit.Rule
    val resources = TestResources()

    @Test
    fun `网页移除后回读404才删除本地收藏_其他账号不变`() = runTest {
        val tokens = testTokenStore(backgroundScope)
        val pending = tokens.beginSession("token")
        tokens.completeSession(pending, 7, "fixture_user")
        val session = tokens.requireSession()
        val site = TestWebSite(7)
        testWebSessionRepository(tokens, site.client).save(session, WebSession("chii_auth=test"))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, updatedAt = 1, accountId = 7))
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, updatedAt = 1, accountId = 9))
        val episodes = FakeEpisodeWatchStateDao()
        episodes.upsertAll(listOf(
            EpisodeWatchStateEntity(episodeId = 80, subjectId = 8, accountId = 7, status = 2),
            EpisodeWatchStateEntity(episodeId = 80, subjectId = 8, accountId = 9, status = 2),
        ))
        var verified = false
        val api = CollectionApi(resources.apiClient { request ->
            assertEquals("/v0/users/fixture_user/collections/8", request.url.encodedPath)
            assertEquals("Bearer token", request.headers["Authorization"])
            assertEquals(listOf<Pair<String, String?>>("https://bgm.tv/subject/8/remove?gh=0a1b2c3d" to "chii_auth=test"), site.opened)
            assertNotNull(dao.getBySubjectId(8, 7), "确认服务端删除前保留缓存")
            verified = true
            respond("", HttpStatusCode.NotFound)
        })
        val store = testCollections(resources, api, FakeSubjectDao(), dao, tokens, episodes, webClient = site.client).store
        assertEquals(Outcome.Success(Unit), store.removeCollection(8, session))
        assertTrue(verified)
        assertNull(dao.getBySubjectId(8, 7))
        assertNotNull(dao.getBySubjectId(8, 9))
        assertTrue(episodes.getBySubject(8, 7).isEmpty())
        assertEquals(1, episodes.getBySubject(8, 9).size)
    }

    @Test
    fun `网页会话缺失时不请求删除也不清本地收藏`() = runTest {
        val tokens = testTokenStore(backgroundScope)
        val pending = tokens.beginSession("token")
        tokens.completeSession(pending, 7, "fixture_user")
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, updatedAt = 1, accountId = 7))
        val api = CollectionApi(resources.apiClient { error("没有网页会话不能发请求") })
        val store = testCollections(resources, api, FakeSubjectDao(), dao, tokens, FakeEpisodeWatchStateDao()).store
        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)), store.removeCollection(8, tokens.requireSession()))
        assertNotNull(dao.getBySubjectId(8, 7))
    }

    @Test
    fun `回读失败时不把网络错误当成未收藏`() = runTest {
        val tokens = testTokenStore(backgroundScope)
        val pending = tokens.beginSession("token")
        tokens.completeSession(pending, 7, "fixture_user")
        val session = tokens.requireSession()
        val site = TestWebSite(7)
        testWebSessionRepository(tokens, site.client).save(session, WebSession("chii_auth=test"))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, updatedAt = 1, accountId = 7))
        val api = CollectionApi(resources.apiClient { respond("", HttpStatusCode.Forbidden) })
        val store = testCollections(resources, api, FakeSubjectDao(), dao, tokens, FakeEpisodeWatchStateDao(), webClient = site.client).store
        assertEquals(Outcome.Failed(Failure.Http(403)), store.removeCollection(8, session))
        assertNotNull(dao.getBySubjectId(8, 7))
    }

    @Test
    fun `网页删除途中换号_不再回读和清除缓存`() = runTest {
        val tokens = testTokenStore(backgroundScope)
        val pending = tokens.beginSession("token")
        tokens.completeSession(pending, 7, "fixture_user")
        val session = tokens.requireSession()
        val site = TestWebSite(7)
        testWebSessionRepository(tokens, site.client).save(session, WebSession("chii_auth=test"))
        site.onOpen = {
            val replacement = tokens.beginSession("other-token")
            tokens.completeSession(replacement, 9, "other")
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, updatedAt = 1, accountId = 7))
        val api = CollectionApi(resources.apiClient { error("换号之后不应回读") })
        val store = testCollections(resources, api, FakeSubjectDao(), dao, tokens, FakeEpisodeWatchStateDao(), webClient = site.client).store
        assertEquals(Outcome.Failed(Failure.SessionChanged), store.removeCollection(8, session))
        assertNotNull(dao.getBySubjectId(8, 7))
    }
    @Test
    fun `网页返回跳转但回读仍有收藏_报告未移除并保留本地记录`() = runTest {
        val tokens = testTokenStore(backgroundScope)
        val pending = tokens.beginSession("token")
        tokens.completeSession(pending, 7, "fixture_user")
        val session = tokens.requireSession()
        val site = TestWebSite(7)
        testWebSessionRepository(tokens, site.client).save(session, WebSession("chii_auth=test"))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, updatedAt = 1, accountId = 7))
        val api = CollectionApi(resources.apiClient {
            respond(
                """{"subject_id":8,"subject_type":2,"rate":8,"type":3,"comment":"","tags":[],"ep_status":5,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}""",
                HttpStatusCode.OK,
                io.ktor.http.headersOf("Content-Type", "application/json"),
            )
        })
        val store = testCollections(resources, api, FakeSubjectDao(), dao, tokens, FakeEpisodeWatchStateDao(), webClient = site.client).store
        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.SUBJECT_UNCOLLECT_UNAVAILABLE)), store.removeCollection(8, session))
        assertNotNull(dao.getBySubjectId(8, 7))
    }

}
