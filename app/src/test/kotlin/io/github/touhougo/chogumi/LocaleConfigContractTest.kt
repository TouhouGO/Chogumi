package io.github.touhougo.chogumi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * 应用语言清单（Android 13 起系统设置里的「应用语言」）只列有字符串的语言：默认的中文（`resources.properties`）与英文（`values-en`）。
 * 清单由 AGP 从资源目录生成（app/build.gradle.kts 的 generateLocaleConfig），这里读生成的那份。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocaleConfigContractTest {

    @Test
    fun `应用语言清单只有中文与英文`() {
        val parser = ApplicationProvider.getApplicationContext<Context>().resources.getXml(R.xml._generated_res_locale_config)
        val locales = buildList {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    add(parser.getAttributeValue(ANDROID_NAMESPACE, "name"))
                }
            }
        }
        assertEquals(listOf("zh", "en"), locales.sortedDescending())
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
