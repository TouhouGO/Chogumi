package io.github.touhougo.chogumi.feature.profile

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 资料头的 ⓘ 紧跟在「@用户名」后面、与它同一行居中，不浮在 ID 与加入日期两行的右边。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileHeaderInfoButtonTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 信息按钮紧跟在ID后面且与ID同行() {
        compose.setContent {
            ChogumiTheme {
                Column(Modifier.width(380.dp)) {
                    ProfileHeader(
                        user = UserBo(id = 1, username = "1183252", nickname = "anonymous"),
                        accent = null,
                        joinedDate = "2025-11-27",
                        onOpenInfo = {},
                    )
                }
            }
        }
        val id = compose.onNodeWithText("@1183252").getUnclippedBoundsInRoot()
        val info = compose.onNodeWithContentDescription(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.profile_info),
        ).getUnclippedBoundsInRoot()
        assertEquals("ⓘ 的左缘不贴着 ID 的右缘：ID $id，ⓘ $info", id.right.value, info.left.value, 0.5f)
        assertEquals("ⓘ 与 ID 不在同一行居中：ID $id，ⓘ $info", (id.top + id.bottom).value / 2, (info.top + info.bottom).value / 2, 0.5f)
    }
}
