package io.github.touhougo.chogumi.feature.home

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 首页的海报是读屏的入口：每张海报要念出作品名，并且能点。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class HomePosterSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 在看迷你封面念作品名并且可点() {
        val clicked = mutableListOf<Int>()
        val subject = SubjectBo(id = 7, type = SubjectType.GAME, name = "Hades", nameCn = "哈迪斯")
        val item = UserCollectionBo(
            subjectId = 7,
            subjectType = SubjectType.GAME,
            type = CollectionType.DOING,
            subject = subject,
        )

        compose.setContent { DoingList(items = listOf(item), onSubjectClick = { clicked += it }) }

        compose.onNodeWithContentDescription("哈迪斯").assertHasClickAction().performClick()
        assertEquals(listOf(7), clicked)
    }

    @Test
    fun 待看大卡的海报念作品名并且可点() {
        val clicked = mutableListOf<Int>()
        val subject = SubjectBo(id = 11, type = SubjectType.ANIME, name = "Frieren", nameCn = "葬送的芙莉莲")
        val item = HomeToWatchItem(
            card = HomeProgressCard(
                subject = subject,
                epButtonState = EpButtonState.Empty,
                progressSubject = ProgressSubject(subjectId = 11, subjectType = SubjectType.ANIME, episodes = emptyList()),
                progressCount = ProgressCount.Known(BookUnit.EP, 0, 12),
            ),
            airedUnwatchedCount = 0,
        )

        compose.setContent {
            ToWatchRow(
                items = listOf(item),
                pendingVisuals = emptyMap(),
                onEpButtonTap = {},
                onMarkFinished = {},
                onSubjectClick = { clicked += it },
            )
        }

        compose.onNodeWithContentDescription("葬送的芙莉莲").assertHasClickAction().performClick()
        assertEquals(listOf(11), clicked)
    }
}
