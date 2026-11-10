package io.github.touhougo.chogumi.core.data.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.RecordedMockEngines
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.AppRelease
import io.github.touhougo.chogumi.core.model.UpdateCheckInterval
import io.github.touhougo.chogumi.core.network.api.ReleaseApi
import io.github.touhougo.chogumi.core.network.model.release.GitHubReleaseDto
import io.github.touhougo.chogumi.core.network.model.release.ReleaseManifestDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** 检查更新与应用内下载：按间隔才查、取不到不提示、忽略只管自动检查、更新内容随提示带上、安装包校验对得上才算下好。 */
class AppUpdatesTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val engines = RecordedMockEngines()
    private val clock = TestClock(10L * UpdateCheckInterval.WEEKLY.millis)
    private val apkBytes = "chogumi 0.2.0".toByteArray()
    private val apkName = "chogumi-0.2.0-200.apk"
    private val apkUrl = "https://github.com/TouhouGO/Chogumi/releases/download/v0.2.0/$apkName"
    private val manifest = """
        {"applicationId": "io.github.touhougo.chogumi", "versionName": "0.2.0", "versionCode": 200,
         "sha256": {"mapping-0.2.0-200.txt": "${"0".repeat(64)}", "$apkName": "${sha256Hex(apkBytes)}"}}
    """.trimIndent()

    private val preBytes = "chogumi 0.3.0".toByteArray()
    private val preName = "chogumi-0.3.0-300.apk"
    private val preManifestUrl = "https://github.com/TouhouGO/Chogumi/releases/download/v0.3.0/release.json"
    private val preManifest = """
        {"versionName": "0.3.0", "versionCode": 300, "sha256": {"$preName": "${sha256Hex(preBytes)}"}}
    """.trimIndent()
    private val stableManifestUrl = "https://github.com/TouhouGO/Chogumi/releases/download/v0.2.0/release.json"
    private val listUrl = "https://api.github.com/repos/TouhouGO/Chogumi/releases?per_page=10"

    /** 最近发布的 Release，从新到旧：没有 release.json 的一版、0.3.0 预发布、0.2.0 正式版。 */
    private val withPrerelease = """
        [{"body": "半成品", "prerelease": true, "assets": [{"name": "notes.txt", "browser_download_url": "https://example.invalid/notes.txt"}]},
         {"body": "## 预发布
* 通知", "prerelease": true, "assets": [{"name": "release.json", "browser_download_url": "$preManifestUrl"}]},
         {"body": "## 新增
* 检查更新", "prerelease": false, "assets": [{"name": "release.json", "browser_download_url": "$stableManifestUrl"}]}]
    """.trimIndent()

    /** 最新的一版就是正式版 0.2.0。 */
    private val stableNewest = """
        [{"body": "## 新增
* 检查更新", "prerelease": false, "assets": [{"name": "release.json", "browser_download_url": "$stableManifestUrl"}]}]
    """.trimIndent()

    private val prefs = PreferencesRepository(
        PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("prefs"), "p.preferences_pb") },
    )
    private val downloadDir = File(resources.directory("updates"), "updates")

    private fun engine(offline: Boolean = false, served: ByteArray = apkBytes, releases: String = withPrerelease) = engines.create { request ->
        if (offline) throw UnknownHostException(request.url.host)
        val url = request.url.toString()
        val octet = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
        when {
            url.endsWith("/releases/latest/download/release.json") || url == stableManifestUrl -> respond(manifest, HttpStatusCode.OK, octet)
            url == preManifestUrl -> respond(preManifest, HttpStatusCode.OK, octet)
            url == listUrl -> respond(releases, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            url == "https://api.github.com/repos/TouhouGO/Chogumi/releases/tags/v0.2.0" ->
                respond("""{"body": "## 新增\n* 检查更新"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            url == apkUrl -> respond(served, HttpStatusCode.OK)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun updates(engine: MockEngine, installedVersionCode: Int = 100, scope: CoroutineScope = resources.scope) = AppUpdates(
        api = ReleaseApi(resources.apiClient(engine)),
        preferences = prefs,
        clock = clock,
        installedVersionCode = installedVersionCode,
        dir = downloadDir,
        scope = scope,
        io = Dispatchers.IO,
    )

    private fun release(notes: String? = null) =
        AppRelease("0.2.0", 200, apkName, apkUrl, sha256Hex(apkBytes), notes)

    private fun requestUrls(engine: MockEngine) = engines.requests(engine).map { it.url.toString() }

    @Test
    fun `到点时查到新版就提示_带上更新内容_记下检查时刻`() = runTest {
        val updates = updates(engine())

        updates.checkIfDue(launch = true)

        assertEquals(release(notes = "新增\n- 检查更新"), updates.offer.value)
        assertEquals(clock.time, prefs.updateCheckedAt.first())
    }

    @Test
    fun `离上次查到不满间隔时不发请求`() = runTest {
        prefs.setUpdateCheckedAt(clock.time - 60 * 60 * 1000L)
        val engine = engine()

        updates(engine).checkIfDue(launch = true)

        assertEquals(emptyList(), requestUrls(engine))
    }

    @Test
    fun `每次打开只在冷启动时查_回到前台不查`() = runTest {
        prefs.setUpdateCheckInterval(UpdateCheckInterval.EVERY_LAUNCH)
        val engine = engine()

        updates(engine).watch(flowOf(false, true, false, true, false, true))

        assertEquals(listOf("https://github.com/TouhouGO/Chogumi/releases/latest/download/release.json"), requestUrls(engine).filter { "release.json" in it })
    }

    @Test
    fun `自动检查关着时不发请求`() = runTest {
        prefs.setUpdateCheck(false)
        val engine = engine()

        updates(engine).checkIfDue(launch = true)

        assertEquals(emptyList(), requestUrls(engine))
    }

    @Test
    fun `取不到时不提示也不记检查时刻`() = runTest {
        val updates = updates(engine(offline = true))

        updates.checkIfDue(launch = true)

        assertNull(updates.offer.value)
        assertNull(prefs.updateCheckedAt.first())
    }

    @Test
    fun `忽略过的那一版自动检查不提示_立即检查照样提示`() = runTest {
        prefs.ignoreUpdate(200)
        val updates = updates(engine())

        updates.checkIfDue(launch = true)
        assertNull(updates.offer.value)

        assertEquals(Outcome.Success(release()), updates.checkNow())
        assertEquals(release(notes = "新增\n- 检查更新"), updates.offer.value)
    }

    @Test
    fun `已是最新时立即检查返回空_取不到时返回失败`() = runTest {
        assertEquals(Outcome.Success(null), updates(engine(), installedVersionCode = 200).checkNow())
        assertEquals(Outcome.Failed(Failure.Offline), updates(engine(offline = true)).checkNow())
    }

    @Test
    fun `接收预发布版时提示最新的预发布版_说明随列表带上`() = runTest {
        prefs.setUpdatePrerelease(true)
        val engine = engine()
        val updates = updates(engine)

        updates.checkIfDue(launch = true)

        val preUrl = "https://github.com/TouhouGO/Chogumi/releases/download/v0.3.0/$preName"
        assertEquals(AppRelease("0.3.0", 300, preName, preUrl, sha256Hex(preBytes), notes = "预发布\n- 通知", prerelease = true), updates.offer.value)
        assertEquals(listOf(listUrl, preManifestUrl), requestUrls(engine))
    }

    @Test
    fun `接收预发布版但最新一版是正式版时按正式版提示`() = runTest {
        prefs.setUpdatePrerelease(true)
        val engine = engine(releases = stableNewest)
        val updates = updates(engine)

        assertEquals(Outcome.Success(release(notes = "新增\n- 检查更新")), updates.checkNow())
        assertEquals(release(notes = "新增\n- 检查更新"), updates.offer.value)
        assertEquals(listOf(listUrl, stableManifestUrl), requestUrls(engine))
    }

    @Test
    fun `忽略记下那一版_不再提示关掉自动检查`() = runTest {
        val updates = updates(engine())

        updates.ignore(release())
        assertEquals(200, prefs.ignoredUpdateVersionCode.first { it != null })
        updates.stopChecking()
        assertEquals(false, prefs.updateCheck.first { !it })
    }

    @Test
    fun `下载的包校验对得上才算下好_改成正式文件名`() = runTest {
        val updates = updates(engine())

        updates.startDownload(release())
        val done = updates.download.first { it is UpdateDownload.Ready || it is UpdateDownload.Failed }

        assertEquals(UpdateDownload.Ready(release(), File(downloadDir, apkName)), done)
        assertEquals(apkBytes.toList(), File(downloadDir, apkName).readBytes().toList())
        assertEquals(listOf(apkName), downloadDir.list()?.toList())
    }

    @Test
    fun `取消下载之后不再冒出进度`() = runTest {
        val downloads = CoroutineScope(SupervisorJob())
        // 整个包一次就在内存里：读下一块不挂起，取消要等写完、去校验时才生效
        val updates = updates(engine(served = ByteArray(4 * 64 * 1024)), scope = downloads)
        // 第一拍进度就在下载线程上原地取消，后面几块照样写完
        val cancel = launch(Dispatchers.Unconfined) {
            updates.download.first { it is UpdateDownload.Running && it.written > 0 }
            updates.closeDownload()
        }

        updates.startDownload(release())
        cancel.join()
        downloads.coroutineContext.job.children.forEach { it.join() }

        assertEquals(UpdateDownload.Idle, updates.download.value)
    }

    @Test
    fun `下载的包对不上时失败并删掉`() = runTest {
        val updates = updates(engine(served = "tampered".toByteArray()))

        updates.startDownload(release())
        val done = updates.download.first { it is UpdateDownload.Ready || it is UpdateDownload.Failed }

        assertEquals(UpdateDownload.Failed(release(), Failure.Rejected(RejectReason.UPDATE_PACKAGE_MISMATCH)), done)
        assertEquals(emptyList(), downloadDir.list()?.toList())
    }

    @Test
    fun `release_json里没有唯一的安装包时认不出`() {
        val noApk = ReleaseManifestDto("0.2.0", 200, mapOf("mapping.txt" to "0".repeat(64)))
        val failure = assertFailsWith<FailureException> { releaseOf(noApk) }.failure
        assertEquals(Failure.BadResponse, failure)
    }

    @Test
    fun `最近的版本里没有一个带release_json时认不出`() {
        val failure = assertFailsWith<FailureException> { newestPublishedOf(listOf(GitHubReleaseDto(body = "半成品"))) }.failure
        assertEquals(Failure.BadResponse, failure)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
