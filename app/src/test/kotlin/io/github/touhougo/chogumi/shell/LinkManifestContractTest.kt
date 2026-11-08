package io.github.touhougo.chogumi.shell

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import io.github.touhougo.chogumi.core.ui.common.navigation.inAppRoute
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * 清单登记的 Bangumi 链接与 [inAppRoute] 对得上：登记的每个域名与路径前缀，系统都交给 [LinkActivity]，进来之后都有应用内的页；
 * 登录授权页不在里面（授权的 Custom Tab 不能被自己接走）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LinkManifestContractTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun handlers(url: String) = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName),
        PackageManager.MATCH_DEFAULT_ONLY,
    ).map { it.activityInfo.name }

    @Test
    fun `登记的每个域名与路径前缀都由入口接下并打开应用内的页`() {
        val data = webLinkData()
        val hosts = data.mapNotNull { it.getAttribute("android:host").ifEmpty { null } }
        val prefixes = data.mapNotNull { it.getAttribute("android:pathPrefix").ifEmpty { null } }
        assertTrue("没读到登记的域名", hosts.isNotEmpty())
        assertTrue("没读到登记的路径", prefixes.isNotEmpty())
        for (scheme in listOf("https", "http")) for (host in hosts) for (prefix in prefixes) {
            val url = "$scheme://$host${prefix}1"
            assertEquals(url, listOf(LinkActivity::class.java.name), handlers(url))
            assertNotNull(url, inAppRoute(url))
        }
    }

    @Test
    fun `登录授权页与站外地址不由自己接`() {
        assertEquals(emptyList<String>(), handlers("https://bgm.tv/oauth/authorize?client_id=x"))
        assertEquals(emptyList<String>(), handlers("https://example.com/subject/1"))
    }

    /** 带 https 的那个 intent-filter（网页链接）里的 `<data>`。 */
    private fun webLinkData(): List<Element> {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(RepoFiles.file("app/src/main/AndroidManifest.xml")).documentElement
        val filters = manifest.getElementsByTagName("intent-filter")
        return (0 until filters.length).map { filters.item(it) as Element }
            .map { filter -> filter.getElementsByTagName("data").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } } }
            .single { data -> data.any { it.getAttribute("android:scheme") == "https" } }
    }
}
