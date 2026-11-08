package io.github.touhougo.chogumi.core.res

import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 系统语言列表怎么选到界面语言：Android 7 起取列表里**第一个有资源的语言**，默认资源（`values/`）不算任何语言。
 * 中文只在默认资源里时，「中文、English」的用户看到的是英文；所以中文还有一份构建生成的 `values-zh`（build-logic 的
 * `DefaultLocaleStrings.kt`）。跑在 Robolectric 的真资源表上，经框架自己的 `createConfigurationContext` 选语言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultLocaleResolutionTest {

    @Test
    fun `系统语言先中文后英文时界面是中文`() {
        assertEquals("首页", homeUnder(LocaleList(Locale.SIMPLIFIED_CHINESE, Locale.US)))
    }

    @Test
    fun `系统语言先英文后中文时界面是英文`() {
        assertEquals("Home", homeUnder(LocaleList(Locale.US, Locale.SIMPLIFIED_CHINESE)))
    }

    @Test
    fun `系统语言没有中英文时落回中文`() {
        assertEquals("首页", homeUnder(LocaleList(Locale.JAPAN)))
    }

    private fun homeUnder(locales: LocaleList): String {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply { setLocales(locales) }
        return app.createConfigurationContext(configuration).getString(R.string.home)
    }
}
