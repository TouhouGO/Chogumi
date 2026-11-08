package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 点 tab 翻过去的页，页里列表项的可保存状态在 Activity 重建后还在（翻页途中预先组合的页不能丢状态）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabPagerSavedStateTest {
    @get:Rule
    val compose = createComposeRule()

    private enum class Tab { A, B, C, D }

    @Test
    fun `点远处的tab翻到的页 列表项里的可保存状态重建后还在`() {
        var selected by mutableStateOf(0)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val pager = rememberTabPagerState(selected = selected, pageCount = Tab.entries.size, onSelect = {})
            TabPager(pager, key = { Tab.entries[it].name }) { page ->
                LazyColumn {
                    items(listOf(1, 2, 3), key = { "item-$it" }) { id ->
                        var taps by rememberSaveable { mutableStateOf(0) }
                        Text("page$page item$id taps$taps", Modifier.clickable { taps++ })
                    }
                }
            }
        }
        // 从第一页点到最后一页：翻页动画途中，前方的页会被预先组合
        compose.runOnIdle { selected = 3 }
        compose.waitForIdle()
        compose.onNodeWithText("page3 item1 taps0").performClick()
        compose.onNodeWithText("page3 item1 taps1").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("page3 item1 taps1").assertExists()
    }
}
