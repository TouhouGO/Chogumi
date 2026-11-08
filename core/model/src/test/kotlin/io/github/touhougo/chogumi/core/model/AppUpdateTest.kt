package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    private fun release(code: Int) = AppRelease("0.$code.0", code, "chogumi-$code.apk", "https://example.invalid/chogumi-$code.apk", "00")

    @Test
    fun `最新一版比装着的新就提示`() {
        assertEquals(release(200), updateOfferOf(enabled = true, latest = release(200), installedVersionCode = 100, ignoredVersionCode = null))
    }

    @Test
    fun `装着的就是最新或更新时不提示`() {
        assertNull(updateOfferOf(enabled = true, latest = release(100), installedVersionCode = 100, ignoredVersionCode = null))
        assertNull(updateOfferOf(enabled = true, latest = release(100), installedVersionCode = 200, ignoredVersionCode = null))
    }

    @Test
    fun `忽略过的那一版不再提示_之后的新版照常提示`() {
        assertNull(updateOfferOf(enabled = true, latest = release(200), installedVersionCode = 100, ignoredVersionCode = 200))
        assertEquals(release(300), updateOfferOf(enabled = true, latest = release(300), installedVersionCode = 100, ignoredVersionCode = 200))
    }

    @Test
    fun `自动检查关着或没取到时不提示`() {
        assertNull(updateOfferOf(enabled = false, latest = release(200), installedVersionCode = 100, ignoredVersionCode = null))
        assertNull(updateOfferOf(enabled = true, latest = null, installedVersionCode = 100, ignoredVersionCode = null))
    }

    @Test
    fun `每次打开只在冷启动时查`() {
        assertTrue(updateCheckDue(UpdateCheckInterval.EVERY_LAUNCH, launch = true, lastCheckedAt = 1_000L, now = 1_000L))
        assertFalse(updateCheckDue(UpdateCheckInterval.EVERY_LAUNCH, launch = false, lastCheckedAt = null, now = 1_000L))
    }

    @Test
    fun `每天在满二十四小时之后才查_冷启动也一样`() {
        val day = UpdateCheckInterval.DAILY.millis
        assertFalse(updateCheckDue(UpdateCheckInterval.DAILY, launch = true, lastCheckedAt = 0L, now = day - 1))
        assertTrue(updateCheckDue(UpdateCheckInterval.DAILY, launch = false, lastCheckedAt = 0L, now = day))
        assertTrue(updateCheckDue(UpdateCheckInterval.WEEKLY, launch = false, lastCheckedAt = null, now = 0L))
        assertFalse(updateCheckDue(UpdateCheckInterval.WEEKLY, launch = true, lastCheckedAt = 0L, now = day))
    }

    @Test
    fun `系统时间往回改过时当作到点`() {
        assertTrue(updateCheckDue(UpdateCheckInterval.WEEKLY, launch = false, lastCheckedAt = 10_000L, now = 5_000L))
    }

    @Test
    fun `更新说明去掉标题引用与加粗记号_列表统一成短横`() {
        val markdown = "## What's Changed\n* **新增** 检查更新\n+ 修复错位\n- 原样\n\n\n\n> First public version"
        assertEquals("What's Changed\n- 新增 检查更新\n- 修复错位\n- 原样\n\nFirst public version", releaseNotesTextOf(markdown))
    }

    @Test
    fun `网页上写的说明是CRLF换行_不留回车`() {
        val markdown = "> First public version after local dev.\r\n\r\n**Full Changelog**: https://github.com/TouhouGO/Chogumi/commits/v0.1.0\r\n"
        assertEquals(
            "First public version after local dev.\n\nFull Changelog: https://github.com/TouhouGO/Chogumi/commits/v0.1.0",
            releaseNotesTextOf(markdown),
        )
    }

    @Test
    fun `普通的一段说明原样留着`() {
        assertEquals("第一版公开发布。", releaseNotesTextOf("第一版公开发布。"))
    }

    @Test
    fun `没写说明时没有这一块`() {
        assertNull(releaseNotesTextOf(null))
        assertNull(releaseNotesTextOf("  \n "))
        assertNull(releaseNotesTextOf("## "))
    }
}
