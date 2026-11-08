package io.github.touhougo.chogumi.shell

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 应用自己也接的 Bangumi 地址指名交给浏览器（不绕回自己）；别的地址照旧由系统选应用。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CustomTabUriHandlerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun installBrowser() {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = BROWSER })
        val activity = ComponentName(BROWSER, "$BROWSER.Main")
        pm.addActivityIfNotPresent(activity)
        pm.addIntentFilterForActivity(
            activity,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataScheme("http")
            },
        )
    }

    private fun open(url: String): Intent {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        CustomTabUriHandler(activity).openUri(url)
        return shadowOf(activity).nextStartedActivity
    }

    @Test
    fun `应用里没有的 Bangumi 页交给浏览器`() {
        val started = open("https://bgm.tv/subject/1/comments")
        assertEquals("https://bgm.tv/subject/1/comments", started.dataString)
        assertEquals(BROWSER, started.`package`)
    }

    @Test
    fun `站外地址不指定应用`() {
        val started = open("https://example.com/a")
        assertEquals("https://example.com/a", started.dataString)
        assertNull(started.`package`)
    }

    private companion object {
        const val BROWSER = "com.example.browser"
    }
}
