package io.github.touhougo.chogumi.feature.profile

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.formatIsoDate
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 资料头（头像、昵称、ID 行、签名行、数字行）的高度不随资料变：骨架与真实内容、有签名与没签名、
 * 数字行有项与全为 0、有无加入日期，高度都一样（容差 0.5dp），资料到了下面的分段与生涯卡不挪位置。每组覆盖 2 倍字号。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileHeaderFixedHeightTest {

    @get:Rule
    val compose = createComposeRule()

    private var user by mutableStateOf<UserBo?>(null)

    private fun heightsAt(fontScale: Float): Map<String, Float> {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ChogumiTheme {
                    val shown = user
                    Column(Modifier.width(380.dp).testTag(TAG)) {
                        ProfileHeader(
                            user = shown,
                            accent = null,
                            joinedDate = shown?.joinedAt?.let(::formatIsoDate)?.ifEmpty { null },
                            onOpenInfo = { }.takeIf { shown != null && !profileInfoOf(shown).isEmpty },
                        )
                        ProfileNumberLine(items = shown?.let { profileNumberLineOf(it, community = true) }, onSelect = {})
                    }
                }
            }
        }
        return CASES.mapValues { (_, case) ->
            compose.runOnIdle { user = case }
            compose.waitForIdle()
            compose.onNodeWithTag(TAG).fetchSemanticsNode().size.height / compose.density.density
        }
    }

    private fun assertFixed(fontScale: Float) {
        val heights = heightsAt(fontScale)
        val skeleton = heights.getValue(SKELETON)
        heights.forEach { (name, height) ->
            assertEquals("${fontScale}× 字号「$name」与骨架不同高：$heights", skeleton, height, 0.5f)
        }
    }

    @Test
    fun 资料到了资料头不改高度() = assertFixed(1f)

    @Test
    fun 两倍字号下资料到了资料头不改高度() = assertFixed(2f)

    @Test
    fun 没有资料可看时信息按钮占着位置但不可点也读不到() {
        val username = "fixture".repeat(16)
        val shownUser = UserBo(id = 1, username = username, nickname = "昵称")
        compose.setContent {
            ChogumiTheme {
                Column(Modifier.width(380.dp)) {
                    ProfileHeader(user = shownUser, accent = null, joinedDate = null, onOpenInfo = null, modifier = Modifier.testTag(INFO_HIDDEN))
                    ProfileHeader(user = shownUser, accent = null, joinedDate = null, onOpenInfo = {}, modifier = Modifier.testTag(INFO_SHOWN))
                }
            }
        }
        val hiddenHeight = compose.onNodeWithTag(INFO_HIDDEN).fetchSemanticsNode().size.height / compose.density.density
        val shownHeight = compose.onNodeWithTag(INFO_SHOWN).fetchSemanticsNode().size.height / compose.density.density
        assertEquals("隐形信息按钮仍须和可点按钮占同样位置", shownHeight, hiddenHeight, 0.5f)
        fun nameToId(tag: String): Float {
            fun topOf(text: String) = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text))
                .fetchSemanticsNode().boundsInRoot.top
            return (topOf("@$username") - topOf("昵称")) / compose.density.density
        }
        assertEquals("信息按钮隐藏时仍撑住同一条 ID 行", nameToId(INFO_SHOWN), nameToId(INFO_HIDDEN), 0.5f)
        fun idWidth(tag: String) = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText("@$username"))
            .fetchSemanticsNode().boundsInRoot.width / compose.density.density
        assertEquals("隐藏的按钮仍占用 ID 行尾的宽度", idWidth(INFO_SHOWN), idWidth(INFO_HIDDEN), 0.5f)
        compose.onAllNodes(hasAnyAncestor(hasTestTag(INFO_HIDDEN)) and hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(
            hasAnyAncestor(hasTestTag(INFO_HIDDEN)) and
                hasContentDescription(ApplicationProvider.getApplicationContext<Context>().getString(R.string.profile_info)),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private companion object {
        const val TAG = "profile_header"
        const val INFO_HIDDEN = "profile_info_hidden"
        const val INFO_SHOWN = "profile_info_shown"
        const val SKELETON = "骨架"
        val JOINED = 1_700_000_000_000L
        val CASES: Map<String, UserBo?> = linkedMapOf(
            SKELETON to null,
            "有签名、数字行三项" to UserBo(
                id = 1, username = "someone", nickname = "某人", sign = "我永远都喜欢美少女，这一句签名很长很长很长很长很长很长",
                joinedAt = JOINED, friendCount = 29, blogCount = 1, indexCreateCount = 3,
            ),
            "没签名、数字行全为 0" to UserBo(id = 2, username = "nobody", nickname = "无名", joinedAt = JOINED),
            "没有加入日期" to UserBo(id = 3, username = "unknown", nickname = "未知", sign = "签名", friendCount = 3),
        )
    }
}
