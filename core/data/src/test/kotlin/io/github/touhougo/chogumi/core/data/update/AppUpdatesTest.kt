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
import kotlinx.coroutines.Dispatchers
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

    private val prefs = PreferencesRepository(
        PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("prefs"), "p.preferences_pb") },
    )
    private val downloadDir = File(resources.directory("updates"), "updates")

    private fun engine(offline: Boolean = false, served: ByteArray = apkBytes) = engines.create { request ->
        if (offline) throw UnknownHostException(request.url.host)
        val url = request.url.toString()
        when {
            url.endsWith("/releases/latest/download/release.json") ->
                respond(manifest, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()))
            url == "https://api.github.com/repos/TouhouGO/Chogumi/releases/tags/v0.2.0" ->
                respond("""{"body": "## 新增\n* 检查更新"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            url == apkUrl -> respond(served, HttpStatusCode.OK)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun updates(engine: MockEngine, installedVersionCode: Int = 100) = AppUpdates(
        api = ReleaseApi(resources.apiClient(engine)),
        preferences = prefs,
        clock = clock,
        installedVersionCode = installedVersionCode,
        dir = downloadDir,
        scope = resources.scope,
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
