package io.github.touhougo.chogumi.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * 生成 baseline profile 的旅程：冷启动 → 首页下拉刷新 → 切到探索 → 下拉刷新 → 滚货架 → 切回首页。
 * 旅程只读界面，要在被测包（[targetAppId]）已登录时跑：没登录时首页 tab 整页是登录页、底栏照常显示，点底栏拦不住，
 * 所以冷启动后先等只有登录后才有的首页标题（[requireSignedIn]），等不到就失败，不会生成一份只含登录页的 profile 还报成功。
 *
 * 点底栏用目的地的名字，名字从被测应用的资源里取（与界面同一份文案，不在这里抄一遍）；手势都落在屏幕中部，不碰边缘。
 */
class StartupJourney {

    @get:Rule
    val rule = BaselineProfileRule()

    private val targetAppId: String =
        checkNotNull(InstrumentationRegistry.getArguments().getString(TARGET_APP_ID_ARGUMENT)) { "运行参数里没有 $TARGET_APP_ID_ARGUMENT" }

    @Test
    fun generate() = rule.collect(packageName = targetAppId, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        requireSignedIn()
        settle()

        pullToRefresh()
        openTab("explore")
        pullToRefresh()
        scrollShelf()
        openTab("home")
    }

    /** 首页的顶栏标题只在登录后出现（没登录时首页 tab 画的是登录页）；等不到说明被测包没登录。 */
    private fun MacrobenchmarkScope.requireSignedIn() {
        val title = checkNotNull(targetString("home_today_title")) { "被测应用里没有名为 home_today_title 的字符串" }
        check(device.wait(Until.hasObject(By.text(title)), STARTUP_TIMEOUT_MS)) {
            "首页的「$title」没出现：被测包 $targetAppId 没有登录（登录只能由用户在这个包上亲手做），或界面改了"
        }
    }

    private fun MacrobenchmarkScope.openTab(stringName: String) {
        val label = checkNotNull(targetString(stringName)) { "被测应用里没有名为 $stringName 的字符串" }
        val tab = checkNotNull(device.wait(Until.findObject(By.text(label)), UI_TIMEOUT_MS)) {
            "底栏上找不到「$label」：设备上没有登录，或界面改了"
        }
        tab.click()
        check(device.wait(Until.hasObject(By.selected(true).hasDescendant(By.text(label))), UI_TIMEOUT_MS)) { "点了「$label」，底栏没有切到它" }
        settle()
    }

    private fun MacrobenchmarkScope.pullToRefresh() {
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, height * 3 / 10, width / 2, height * 7 / 10, PULL_STEPS)
        settle()
    }

    private fun MacrobenchmarkScope.scrollShelf() {
        val content = device.findObject(By.scrollable(true)) ?: return
        content.setGestureMargin(device.displayWidth / 5)
        repeat(SCROLL_ROUNDS) {
            content.fling(Direction.DOWN)
            settle()
        }
        content.fling(Direction.UP)
        settle()
    }

    /** 等界面空闲：下拉与切页之后的重组、图片解码、列表布局都落定，落定的那段代码才会进 profile。 */
    private fun MacrobenchmarkScope.settle() = device.waitForIdle(IDLE_MS)

    private fun targetString(name: String): String? {
        val resources = InstrumentationRegistry.getInstrumentation().context.packageManager.getResourcesForApplication(targetAppId)
        val id = resources.getIdentifier(name, "string", targetAppId)
        return if (id == 0) null else resources.getString(id)
    }

    private companion object {
        /** 构建把被测应用的 applicationId 放进运行参数（`AndroidBaselineProfileConventionPlugin`）。 */
        const val TARGET_APP_ID_ARGUMENT = "targetAppId"
        const val UI_TIMEOUT_MS = 5_000L
        const val STARTUP_TIMEOUT_MS = 15_000L
        const val IDLE_MS = 2_000L
        const val PULL_STEPS = 40
        const val SCROLL_ROUNDS = 3
    }
}
