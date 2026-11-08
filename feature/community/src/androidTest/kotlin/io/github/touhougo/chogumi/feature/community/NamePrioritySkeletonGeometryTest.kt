package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.model.NoticeSenderBo
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通知骨架与真实行共用同一个姓名/动作框架；2× 下真实行多出的动作行必须在加载时预留。 */
@RunWith(AndroidJUnit4::class)
class NamePrioritySkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    @Test
    fun 通知骨架与真实行一倍字号同高() = assertGeometry(fontScale = 1f)

    @Test
    fun 通知骨架与真实行两倍字号同高_动作行也预留() = assertGeometry(fontScale = 2f)

    private fun assertGeometry(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(NotificationContentWidth).testTag(ROW)) {
                        if (showReal) {
                            NotificationItemRow(
                                item = NoticeBo(
                                    id = 1,
                                    type = 0,
                                    sender = NoticeSenderBo(id = 1, username = "姓名优先显示", nickname = "姓名优先显示"),
                                    title = "",
                                    mainId = 1,
                                    relatedId = 1,
                                    createdAt = 0L,
                                    unread = true,
                                ),
                                onUser = {},
                                onOpen = {},
                            )
                        } else {
                            NotificationItemSkeleton()
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertSkeletonMatchesReal(
            anchors = listOf(
                SkeletonAnchor(
                    name = "通知行",
                    skeleton = { composeRule.onNodeWithTag(ROW, useUnmergedTree = true) },
                    real = { composeRule.onNodeWithTag(ROW, useUnmergedTree = true) },
                ),
            ),
            flipToReal = {
                composeRule.runOnIdle { showReal = true }
                composeRule.waitForIdle()
            },
        )
    }

    private companion object {
        val NotificationContentWidth = 348.dp
        const val ROW = "notification-skeleton-row"
    }
}
