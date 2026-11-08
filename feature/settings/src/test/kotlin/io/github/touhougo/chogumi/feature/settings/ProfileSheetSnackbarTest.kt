package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 个人资料的面板开着时，写失败的提示条画在面板上：页面的提示条在面板下面，看不见。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ProfileSheetSnackbarTest {

    @get:Rule
    val compose = createComposeRule()

    private object NoActions : ProfileSettingsActions {
        override fun retry() = Unit
        override fun openText(field: ProfileTextField) = Unit
        override fun changeText(text: String) = Unit
        override fun saveText() = Unit
        override fun closeText() = Unit
        override fun pickAvatar(uri: String) = Unit
        override fun saveAvatar() = Unit
        override fun closeAvatar() = Unit
    }

    /** 底部面板的那一层（面板的窗格名是「关闭」；提示条自己也是一个窗格，不能只看有没有窗格名）。 */
    private val inSheet = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "关闭"))

    private fun show(state: ProfileSettingsUiState): FailureNotices {
        val failures = FailureNotices()
        compose.setContent {
            MaterialTheme {
                PageSheetTestHost { ProfileSettingsContent(state = state, actions = NoActions, failures = failures, onBack = {}) }
            }
        }
        return failures
    }

    private val me = Load.Ready(UserBo(id = 42, username = "fixture_user", nickname = "旧昵称"))

    @Test
    fun 面板开着时写失败的提示条画在面板上() {
        val failures = show(
            ProfileSettingsUiState(me = me, canEdit = true, textEditor = ProfileTextEditor(ProfileTextField.NICKNAME, "旧昵称", "新昵称")),
        )

        failures.post(R.string.failure_rule_image_unreadable)

        compose.onNodeWithText("读不出这张图片，换一张试试").assert(inSheet)
    }

    @Test
    fun 没开面板时提示条照常在页面上() {
        val failures = show(ProfileSettingsUiState(me = me, canEdit = true))

        failures.post(R.string.failure_rule_image_unreadable)

        compose.onNodeWithText("读不出这张图片，换一张试试").assert(!inSheet)
    }
}
