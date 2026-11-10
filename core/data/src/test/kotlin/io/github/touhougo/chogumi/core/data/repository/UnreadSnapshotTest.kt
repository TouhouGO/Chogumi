package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class UnreadSnapshotTest {
    @get:org.junit.Rule val resources = TestResources()

    @Test
    fun `短信读取期间换号_完整未读快照必须失败`() = runBlocking {
        val tokens = testTokenStore(resources, userId = 7)
        val prefs = TestPreferencesRepository(ExperienceMode.FULL)
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val webClient = resources.own(testWebClient {
            arrived.complete(Unit)
            release.await()
            respond("""{"pm_count":2,"pm_ignore_url":"/pm/ignore_unread.chii?gh=TEST"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val sessions = testWebSessionRepository(tokens, webClient)
        sessions.save(tokens.session(), WebSession("chii_auth=test-cookie"))
        val repository = NotificationRepository(
            NextApi(resources.apiClient { respond("""{"data":[],"total":8}""", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString())) }),
            tokens, sessions, prefs.repository, resources.scope,
        )
        try {
            val pending = resources.scope.async { repository.unreadSnapshot() }
            withTimeout(10000) { arrived.await() }
            tokens.completeSession(tokens.beginSession("other-token"), 8, "other")
            release.complete(Unit)
            val actual = withTimeout(10000) { pending.await() }
            assertEquals(Outcome.Failed(Failure.SessionChanged), actual)
        } finally { release.complete(Unit); prefs.close() }
    }
}
