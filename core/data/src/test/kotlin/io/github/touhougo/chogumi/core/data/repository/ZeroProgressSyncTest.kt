package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ZeroProgressSyncTest {
    @get:Rule val resources = TestResources()
    @Test fun `服务端清零后全量同步清除旧已看_保留其他逐集状态`() = runBlocking {
        val requests = ConcurrentRequestRecorder()
        val token = testTokenStore(resources, 7)
        val client = resources.apiClient { request ->
            requests.record(request)
            val body = when {
                request.url.encodedPath.endsWith("/episodes") -> """{"data":[{"episode":{"id":81,"type":0,"name":"","name_cn":"","sort":1,"airdate":"2026-01-01","comment":0,"duration":"","desc":"","disc":0},"type":0,"updated_at":0},{"episode":{"id":82,"type":1,"name":"SP","name_cn":"","sort":1,"airdate":"2026-01-01","comment":0,"duration":"","desc":"","disc":0},"type":1,"updated_at":0}],"total":2,"limit":100,"offset":0}"""
                request.url.parameters["subject_type"] == "2" -> """{"data":[{"subject_id":8,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2026-11-11T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}"""
                else -> """{"data":[],"total":0,"limit":50,"offset":0}"""
            }
            respond(body, headers = headersOf("Content-Type", "application/json"))
        }
        val subject = FakeSubjectDao().apply { upsert(SubjectEntity(id=8,type=2,name="fixture",nameCn="",updatedAt=1L)) }
        val episodes = FakeEpisodeDao().apply { upsertAll(listOf(EpisodeEntity(id=81,subjectId=8,type=0,name="",nameCn="",sort=1.0,airdate="2026-01-01"))) }
        val watches = FakeEpisodeWatchStateDao().apply { upsertAll(listOf(EpisodeWatchStateEntity(episodeId=81,subjectId=8,accountId=7,status=2,updatedAt=1L))) }
        val dao = FakeUserCollectionDao()
        val collections = testCollections(resources,CollectionApi(client),subject,dao,token,watches,episodes)
        assertTrue(collections.sync.refreshAllUserCollections(force=true) is Outcome.Success)
        assertEquals(0, dao.store[7 to 8]?.epStatus)
        assertEquals("服务端的特别篇想看状态不能被清表丢掉", 1, watches.getBySubject(8, 7).first { it.episodeId == 82 }.status)
        assertEquals("服务端清零必须取回逐集表", 1, requests.toList().count { it.url.encodedPath.endsWith("/episodes") })
        assertEquals("服务器清零后旧已看记录不能留下", 0, watches.getBySubject(8,7).count { it.status == 2 })
    }
}
