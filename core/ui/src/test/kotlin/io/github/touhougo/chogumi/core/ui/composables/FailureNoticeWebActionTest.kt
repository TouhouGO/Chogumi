package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 只能在网页上做成的失败：提示条的动作是「去网页」，点了用当前的 UriHandler 打开那个网页。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h800dp")
class FailureNoticeWebActionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `点去网页 打开提示带的网页 提示随之消费`() {
        val opened = mutableListOf<String>()
        val notices = FailureNotices()
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) {
                            opened += uri
                        }
                    },
                ) {
                    val snackbar = remember { SnackbarHostState() }
                    FailureNoticeEffect(notices, snackbar)
                    SnackbarHost(snackbar)
                }
            }
        }
        notices.postWebOnly(Failure.Rejected(RejectReason.GROUP_MEMBERSHIP_UNAVAILABLE), BgmWeb.group("a"))

        composeRule.onNodeWithText(context.getString(R.string.failure_action_open_web)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(BgmWeb.group("a")), opened)
        assertNull(notices.current.value)
    }
}
