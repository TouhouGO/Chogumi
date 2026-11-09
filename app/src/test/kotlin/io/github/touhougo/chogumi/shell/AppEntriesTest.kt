package io.github.touhougo.chogumi.shell

import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.SignedInAccount
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequests
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals

/** 每种路由都接上了页面：漏接的路由一导航过去就崩（entryProvider 找不到页面时抛异常）。 */
class AppEntriesTest {

    private val samples: List<Route> = listOf(
        Route.Home,
        Route.Explore,
        Route.Progress,
        Route.Community,
        Route.Profile,
        Route.Login,
        Route.Feed,
        Route.Calendar,
        Route.Settings,
        Route.SettingsPage(SettingsSection.APPEARANCE),
        Route.About,
        Route.GestureTour,
        Route.ProfileSettings,
        Route.Search(),
        Route.UserProfile("fixture_user"),
        Route.SubjectDetails(1),
        Route.SubjectRelations(1),
        Route.SubjectTopics(1),
        Route.Topic(TopicType.GROUP, 1),
        Route.Episode(1),
        Route.Group("boring"),
        Route.AllGroups,
        Route.MyTopics,
        Route.MutedGroups,
        Route.BlockedUsers,
        Route.SubjectReviews(1),
        Route.SubjectIndexes(1),
        Route.MonoDetails(MonoType.PERSON, 1),
        Route.MonoWorks(MonoType.CHARACTER, 1),
        Route.MonoRelations(MonoType.PERSON, 1),
        Route.PersonCharacters(1),
        Route.UserBlogs("fixture_user"),
        Route.BlogDetails(1),
        Route.UserIndexes("fixture_user", createdCount = 1, collectedCount = 2),
        Route.IndexDetails(1),
        Route.UserCareer(null, SubjectType.ANIME),
        Route.PmConversation(1),
        Route.PmWrite(),
    )

    @Test
    fun `样例覆盖全部路由`() {
        val all = Route::class.java.declaredClasses.filter { Route::class.java.isAssignableFrom(it) }.toSet()
        assertEquals(all, samples.map { it.javaClass }.toSet())
    }

    @Test
    fun `每种路由都有页面`() {
        val entries = appEntries(navigate = {}, goBack = {}, account = SignedInAccount(userId = 1, username = "me"), progressEntryRequests = ProgressEntryRequests(), bottomPadding = { 0.dp })
        assertEquals(emptyList(), samples.filter { runCatching { entries(it) }.isFailure }, "这些路由没有页面")
    }
}
