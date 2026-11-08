package io.github.touhougo.chogumi.feature.profile

import android.os.Looper
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.CollectionStatsDataSource
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.OtherCareerDataSource
import io.github.touhougo.chogumi.core.data.repository.SyncProgress
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.TimelineRepository
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.noWebClient
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import io.github.touhougo.chogumi.core.data.repository.testOtherUserCollectionsCache
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.CareerStatistics
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.TimelineApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The real profile page derives one user key for four destinations. The fallback is exercised through the page's loaded
 * profile state, and the normal username is exercised by refreshing the same page and repeating the clicks in the same
 * composition.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h900dp")
class ProfileScreenUserKeyTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val resources = TestResources()

    @Test
    fun `资料用户名缺失时四个入口都去uid_普通用户名保持不变`() {
        val viewedUsername = AtomicReference("")
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val client = resources.apiClient { request ->
            val path = request.url.encodedPath
            when (path) {
                "/v0/users/lookup" -> respond(userJson(viewedUsername.get()), headers = json)
                "/p1/users/lookup" -> respond(profileJson(viewedUsername.get()), headers = json)
                "/p1/users/lookup/indexes",
                "/p1/users/lookup/collections/indexes",
                -> respond("""{"data":[],"total":1}""", headers = json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val tokenStore = testTokenStore(resources, userId = 9, username = "fixture_user")
        val webSessions = testWebSessionRepository(tokenStore, resources.own(noWebClient()))
        val users = testUserRepository(resources, UserApi(client), tokenStore, EmptyUserDao, webSessions = webSessions)
        val subjectDao = FakeSubjectDao()
        val collections = testCollections(
            resources,
            CollectionApi(client),
            subjectDao,
            FakeUserCollectionDao(),
            tokenStore,
            FakeEpisodeWatchStateDao(),
        )
        val indexRepository = IndexRepository(IndexApi(client), UserApi(client), tokenStore)
        val otherStats = CollectionStatistics(
            totalCount = 1,
            statusCounts = mapOf(CollectionType.DONE to 1),
        )
        val navigations = mutableListOf<Navigation>()
        var profileViewModel: ProfileViewModel? = null
        var careerViewModel: CareerViewModel? = null

        val application = koinApplication {
            modules(
                module {
                    viewModel {
                        ProfileViewModel(
                            username = "lookup",
                            userRepository = users,
                            collectionStore = collections.store,
                            otherCollections = testOtherUserCollectionsCache(CollectionApi(client), subjectDao, tokenStore),
                            timelineRepository = TimelineRepository(
                                TimelineApi(client),
                                NextApi(client),
                                UserApi(client),
                                tokenStore,
                                webSessions,
                                resources.scope,
                                Dispatchers.Default,
                                NoBlockedUsers(),
                            ),
                            collectionMarker = StillCollectionMarker(),
                            collectionEditing = StillCollectionEditing(),
                            appTime = testAppTime(System.currentTimeMillis()),
                            drafts = testDraftRepository(resources.scope, tokens = tokenStore),
                            clock = AppClock { 0L },
                            blockedUsers = NoBlockedUsers(),
                            indexRepository = indexRepository,
                        ).also { profileViewModel = it }
                    }
                    viewModel {
                        CareerViewModel(
                            username = "lookup",
                            initialType = SubjectType.ANIME,
                            summaryOnly = false,
                            collectionStats = EmptyStatsSource,
                            otherCareers = FixedOtherCareers(otherStats),
                            userRepository = users,
                            appTime = testAppTime(System.currentTimeMillis()),
                        ).also { careerViewModel = it }
                    }
                },
            )
        }

        try {
            compose.setContent {
                KoinIsolatedContext(application) {
                    ChogumiTheme {
                        PageSheetTestHost {
                            ProfileScreen(
                                username = "lookup",
                                isLoggedIn = true,
                                onLogin = {},
                                onSettingsClick = {},
                                onBack = {},
                                onSubjectClick = {},
                                onUserClick = {},
                                onCharacterClick = {},
                                onPersonClick = {},
                                onOpenBlogs = { navigations += Navigation.Blogs(it) },
                                onOpenIndexes = { key, counts -> navigations += Navigation.Indexes(key, counts) },
                                onOpenCareer = { key, type -> navigations += Navigation.Career(key, type) },
                                onMessage = { navigations += Navigation.Message(it) },
                            )
                        }
                    }
                }
            }

            awaitViewModel {
                val profile = requireNotNull(profileViewModel).uiState.value.user
                profile != null && profile.id == 99 && profile.username == ""
            }
            awaitViewModel {
                requireNotNull(careerViewModel).uiState.value.cardContent(publicRatings = true) is CareerCardContent.Ready
            }
            clickFourEntries()
            assertEquals(
                listOf(
                    Navigation.Message("99"),
                    Navigation.Blogs("99"),
                    Navigation.Indexes("99", IndexListCounts(created = 1, collected = 1)),
                    Navigation.Career("99", SubjectType.ANIME),
                ),
                navigations,
            )

            navigations.clear()
            viewedUsername.set("carol")
            compose.runOnIdle { requireNotNull(profileViewModel).retry() }
            awaitViewModel { requireNotNull(profileViewModel).uiState.value.user?.username == "carol" }
            clickFourEntries()
            assertEquals(
                listOf(
                    Navigation.Message("carol"),
                    Navigation.Blogs("carol"),
                    Navigation.Indexes("carol", IndexListCounts(created = 1, collected = 1)),
                    Navigation.Career("carol", SubjectType.ANIME),
                ),
                navigations,
            )
        } finally {
            application.close()
        }
    }

    private fun clickFourEntries() {
        compose.onNodeWithText("短消息", useUnmergedTree = true)
            .performTouchInput { click() }
        compose.onNodeWithText("日志", useUnmergedTree = true)
            .performTouchInput { click() }
        compose.onNodeWithText("目录", useUnmergedTree = true)
            .performTouchInput { click() }
        compose.onNodeWithTag(CareerCardTag, useUnmergedTree = true)
            .performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun awaitViewModel(condition: () -> Boolean) = compose.waitUntil(WAIT_BUDGET_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun userJson(username: String) = """
        {"id":99,"username":"$username","nickname":"#99","user_group":10,
         "avatar":{"large":"","medium":"","small":""},"sign":"","reg_time":"2020-01-01T00:00:00Z"}
    """.trimIndent()

    private fun profileJson(username: String) = """
        {"id":99,"username":"$username","nickname":"#99",
         "avatar":{"large":"","medium":"","small":""},"group":10,"joinedAt":1600000000,
         "sign":"","site":"","location":"","bio":"","networkServices":[],
         "homepage":{"left":[],"right":[]},
         "stats":{"subject":{"2":{"2":1}},"mono":{"character":0,"person":0},
           "blog":1,"friend":0,"group":0,"index":{"create":1,"collect":1}},"isFriend":false}
    """.trimIndent()

    private sealed interface Navigation {
        data class Message(val key: String) : Navigation
        data class Blogs(val key: String) : Navigation
        data class Indexes(val key: String, val counts: IndexListCounts) : Navigation
        data class Career(val key: String?, val type: SubjectType) : Navigation
    }

    private object EmptyUserDao : UserDao {
        override suspend fun get(id: Int): UserEntity? = null
        override suspend fun getByUsername(username: String): UserEntity? = null
        override suspend fun upsert(user: UserEntity) = Unit
    }

    private object EmptyStatsSource : CollectionStatsDataSource {
        override val syncProgress = MutableStateFlow(SyncProgress())

        override fun observeStatistics(currentYear: Int): Flow<Load<CareerStatistics>> = flowOf(
            Load.Ready(
                CareerStatistics(
                    byType = ContentDomains.associateWith { CollectionStatistics() },
                    overall = CollectionStatistics(),
                ),
            ),
        )

        override suspend fun refreshAllUserCollections(force: Boolean): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun lastSyncedAt(): Long = 0L
    }

    private class FixedOtherCareers(private val statistics: CollectionStatistics) : OtherCareerDataSource {
        override suspend fun statisticsFor(
            username: String,
            subjectType: SubjectType,
            currentYear: Int,
            onProgress: (SyncProgress) -> Unit,
        ): Outcome<CollectionStatistics> = Outcome.Success(statistics)

        override fun hasCached(username: String, subjectType: SubjectType): Boolean = false

        override suspend fun overallStatisticsFor(
            username: String,
            subjectTypes: Collection<SubjectType>,
            currentYear: Int,
        ): Outcome<CollectionStatistics> = Outcome.Success(statistics)

        override suspend fun coverPicksFor(
            username: String,
            subjectType: SubjectType,
            currentYear: Int,
        ): Outcome<List<SubjectBo>> = Outcome.Success(emptyList())
    }
}
