package io.github.touhougo.chogumi.core.data.update

import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.AppRelease
import io.github.touhougo.chogumi.core.model.releaseNotesTextOf
import io.github.touhougo.chogumi.core.model.updateCheckDue
import io.github.touhougo.chogumi.core.model.updateOfferOf
import io.github.touhougo.chogumi.core.network.api.ReleaseApi
import io.github.touhougo.chogumi.core.network.model.release.GitHubReleaseDto
import io.github.touhougo.chogumi.core.network.model.release.ReleaseManifestDto
import io.github.touhougo.chogumi.core.network.releaseDownloadUrl
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** 应用内下载一版安装包的进展；每一种都带着它下的那一版。 */
sealed interface UpdateDownload {
    data object Idle : UpdateDownload

    /** 下载中：[written] 已写字节，[total] 总字节（服务端没给长度为 null）。 */
    data class Running(val release: AppRelease, val written: Long, val total: Long?) : UpdateDownload

    /** 下好了、SHA-256 对得上：[file] 交给系统安装器。 */
    data class Ready(val release: AppRelease, val file: File) : UpdateDownload

    /** 没下成：断网、超时，或校验对不上（[RejectReason.UPDATE_PACKAGE_MISMATCH]，包已删掉）。 */
    data class Failed(val release: AppRelease, val failure: Failure) : UpdateDownload
}

/**
 * 检查更新与应用内下载，全 App 一份（应用作用域）：外壳的更新提示框读 [offer] 与 [download]，设置页的「立即检查」调 [checkNow]。
 *
 * - 去哪儿找最新一版由「接收预发布版」（`updatePrerelease`）定：关着只看最新正式版的 release.json；开着看最近发布的几个
 *   Release 里最新的一个（正式或预发布，[newestPublishedOf]）和它的 release.json，说明随列表一起到。预发布与正式版共用一套
 *   递增的版本号，比新旧、忽略都照旧只看 versionCode。
 * - 要提示的那一版带着 Release 页上发布时写的说明（[withNotes]）。
 * - 自动检查只在 App 到前台时（[watch]）按间隔判（`updateCheckDue`）；取不到什么也不做，等下一个时机——用户什么也没做，
 *   不为它打扰（设计决策）。查到了就记下时刻（检查间隔从它起算），要不要提示由 `updateOfferOf` 判。
 * - 「立即检查」是用户动手：失败交给调用方显示；有新版就提示，忽略过的那一版也提示，开关关着也查。
 * - 下载在应用作用域里跑：转屏、切后台都接着下；写进 [dir] 的 `*.part`，SHA-256 对得上才改成正式文件名，[dir] 里别的文件
 *   （更早的版本）同时删掉。已经下好、校验对得上的同一版不重下。
 *
 * [installedVersionCode] 是装着的这一版（app 的 BuildConfig）。[io] 跑文件读写与摘要（生产是 `Dispatchers.IO`）。
 */
class AppUpdates(
    private val api: ReleaseApi,
    private val preferences: PreferencesRepository,
    private val clock: AppClock,
    private val installedVersionCode: Int,
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
) {
    private val offered = MutableStateFlow<AppRelease?>(null)
    private val downloads = MutableStateFlow<UpdateDownload>(UpdateDownload.Idle)
    private val checks = Mutex()

    /**
     * 当前这一轮下载；[downloads] 只在持着 [attemptLock] 时写，且只有这一轮能写。取消只在下一次挂起时生效，
     * 写盘那一段不挂起：被取消的一轮之后还会报进度、甚至交出结果，凭这把锁丢掉，不盖住「取消」。
     */
    private val attemptLock = Any()
    private var attempt: Job? = null

    /** 要提示的那一版；没有要提示的为 null。用户在提示框里选了什么（[dismiss]、[ignore]、[stopChecking]、下载）之后清掉。 */
    val offer: StateFlow<AppRelease?> = offered.asStateFlow()

    /** 应用内下载的进展。 */
    val download: StateFlow<UpdateDownload> = downloads.asStateFlow()

    /**
     * 跟着 App 在不在前台（[foreground]）自动检查：进程里第一次到前台算冷启动，之后每次回到前台再判一次。
     * 由 App 在应用作用域里启动一次，随进程结束。
     */
    suspend fun watch(foreground: Flow<Boolean>) {
        foreground.distinctUntilChanged().filter { it }.withIndex().collect { (index, _) -> checkIfDue(launch = index == 0) }
    }

    /** 自动检查的一次：开关开着、按间隔到点了才去取；取到了记下时刻，有该提示的就提示。任何一步失败都只是这次不查。 */
    internal suspend fun checkIfDue(launch: Boolean) {
        checks.withLock {
            val due = storageOutcomeOf {
                preferences.updateCheck.first() &&
                    updateCheckDue(preferences.updateCheckInterval.first(), launch, preferences.updateCheckedAt.first(), clock.nowMillis())
            }.valueOrNull() ?: false
            if (!due) return
            val prerelease = storageOutcomeOf { preferences.updatePrerelease.first() }.valueOrNull() ?: return
            val latest = fetchLatest(prerelease).valueOrNull() ?: return
            val offer = storageOutcomeOf {
                updateOfferOf(preferences.updateCheck.first(), latest.release, installedVersionCode, preferences.ignoredUpdateVersionCode.first())
            }.valueOrNull()
            if (offer == null) return
            val shown = withNotes(latest)
            // 取的时候换了通道：这一版是按旧设置找的，不弹，等下一次
            if (prereleaseIs(prerelease)) offered.value = shown
        }
    }

    /**
     * 「立即检查」：按「接收预发布版」取最新一版，比装着的新就提示（不看开关与忽略过的版本），返回它；
     * 已是最新返回 null。读不出设置、取不到返回失败。取的时候换了通道，按新设置重取：旧通道的结果不落下。
     */
    suspend fun checkNow(): Outcome<AppRelease?> = checks.withLock { checkNowLocked() }

    private tailrec suspend fun checkNowLocked(): Outcome<AppRelease?> {
        val prerelease = when (val read = storageOutcomeOf { preferences.updatePrerelease.first() }) {
            is Outcome.Failed -> return read
            is Outcome.Success -> read.value
        }
        val latest = when (val fetched = fetchLatest(prerelease)) {
            is Outcome.Failed -> return fetched
            is Outcome.Success -> fetched.value
        }
        val offer = updateOfferOf(enabled = true, latest = latest.release, installedVersionCode = installedVersionCode, ignoredVersionCode = null)
        val shown = offer?.let { withNotes(latest) }
        if (!prereleaseIs(prerelease)) return checkNowLocked()
        if (shown != null) offered.value = shown
        return Outcome.Success(offer)
    }

    /** 关掉提示框，什么也不记：下一次到点再问。 */
    fun dismiss() {
        offered.value = null
    }

    /** 「忽略」：这一版及更早的不再提示。记不下来时（存储坏了）下一次还会再问，不另提示。 */
    fun ignore(release: AppRelease) {
        offered.value = null
        scope.launch { storageOutcomeOf { preferences.ignoreUpdate(release.versionCode) } }
    }

    /** 「不再提示」：关掉自动检查（设置里能再打开）。记不下来时同 [ignore]。 */
    fun stopChecking() {
        offered.value = null
        scope.launch { storageOutcomeOf { preferences.setUpdateCheck(false) } }
    }

    /** 开始（或重试）下载 [release]；正在下的那一轮先取消。 */
    fun startDownload(release: AppRelease) {
        offered.value = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            val result = outcomeOf {
                fetchApk(release) { written, total -> publish(self, UpdateDownload.Running(release, written, total)) }
            }
            publish(
                self,
                when (result) {
                    is Outcome.Success -> UpdateDownload.Ready(release, result.value)
                    is Outcome.Failed -> UpdateDownload.Failed(release, result.failure)
                },
            )
        }
        synchronized(attemptLock) {
            attempt?.cancel()
            attempt = job
            downloads.value = UpdateDownload.Running(release, written = 0L, total = null)
        }
        job.start()
    }

    /** 取消下载、或在下载结果上关掉（取消、改用浏览器、已交给系统安装器）：回到什么也没有。下好的包留着，下次不重下。 */
    fun closeDownload() {
        synchronized(attemptLock) {
            attempt?.cancel()
            attempt = null
            downloads.value = UpdateDownload.Idle
        }
    }

    /** [job] 那一轮报的进展；已经不是当前这一轮（取消了、换了一轮）时丢掉。 */
    private fun publish(job: Job, next: UpdateDownload) {
        synchronized(attemptLock) {
            if (attempt === job) downloads.value = next
        }
    }

    /** 「接收预发布版」此刻还是 [prerelease]；读不出算变了。 */
    private suspend fun prereleaseIs(prerelease: Boolean): Boolean =
        storageOutcomeOf { preferences.updatePrerelease.first() }.valueOrNull() == prerelease

    /** 取到的最新一版；[notesKnown] 为 true 时说明已经随它到了（预发布通道读的列表带着说明），不用再取。 */
    private class Latest(val release: AppRelease, val notesKnown: Boolean)

    /** 最新一版：[prerelease] 关着读最新正式版的 release.json，开着读最近发布的几个 Release 里最新的那一个。 */
    private suspend fun fetchLatest(prerelease: Boolean): Outcome<Latest> = outcomeOf {
        val latest = if (prerelease) {
            val (newest, manifestUrl) = newestPublishedOf(api.getRecentReleases())
            val release = releaseOf(api.getManifest(manifestUrl)).copy(notes = releaseNotesTextOf(newest.body), prerelease = newest.prerelease)
            Latest(release, notesKnown = true)
        } else {
            Latest(releaseOf(api.getLatestManifest()), notesKnown = false)
        }
        storageOutcomeOf { preferences.setUpdateCheckedAt(clock.nowMillis()) }
        latest
    }

    /** 要提示的那一版带上发布时写的说明；说明取不到只是不画那一块，不算这次检查失败。只在要提示时取，不为每次检查多发一个请求。 */
    private suspend fun withNotes(latest: Latest): AppRelease {
        if (latest.notesKnown) return latest.release
        val release = latest.release
        return release.copy(notes = releaseNotesTextOf(outcomeOf { api.getReleaseNotes(release.versionName) }.valueOrNull()))
    }

    /** 下好并校验过的安装包；同一版已经在 [dir] 里、对得上就直接用。 */
    private suspend fun fetchApk(release: AppRelease, onProgress: (written: Long, total: Long?) -> Unit): File {
        val target = File(dir, release.apkFileName)
        val partial = File(dir, release.apkFileName + PARTIAL_SUFFIX)
        withContext(io) {
            dir.mkdirs()
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        }
        if (withContext(io) { target.isFile && sha256Of(target) == release.apkSha256 }) return target
        api.downloadTo(release.apkUrl, partial, onProgress)
        return withContext(io) {
            if (sha256Of(partial) != release.apkSha256) {
                partial.delete()
                throw FailureException(Failure.Rejected(RejectReason.UPDATE_PACKAGE_MISMATCH))
            }
            if (!partial.renameTo(target)) throw FailureException(Failure.Storage)
            target
        }
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
    }
}

/**
 * release.json → 一个正式版。安装包是摘要表里唯一以 `.apk` 结尾的附件（文件名与摘要都从它来）；没有或不止一个、
 * 文件名带路径时是认不出的响应（[Failure.BadResponse]），不猜。
 */
internal fun releaseOf(manifest: ReleaseManifestDto): AppRelease {
    val apk = manifest.sha256.entries.singleOrNull { it.key.endsWith(".apk") }
        ?.takeIf { '/' !in it.key && it.value.matches(SHA256_HEX) }
        ?: throw FailureException(Failure.BadResponse)
    return AppRelease(
        versionName = manifest.versionName,
        versionCode = manifest.versionCode,
        apkFileName = apk.key,
        apkUrl = releaseDownloadUrl(manifest.versionName, apk.key),
        apkSha256 = apk.value.lowercase(),
    )
}

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

/**
 * 预发布通道的最新一版：最近发布的 Release（GitHub 按建立时间从新到旧给出，不带令牌时不含草稿）里第一个带 release.json
 * 附件的，连同那份 release.json 的地址。版本号只增不减，最新建立的就是版本最高的；一个也没有是认不出的响应（[Failure.BadResponse]）。
 */
internal fun newestPublishedOf(releases: List<GitHubReleaseDto>): Pair<GitHubReleaseDto, String> =
    releases.firstNotNullOfOrNull { release ->
        release.assets.singleOrNull { it.name == MANIFEST_FILE_NAME }?.let { release to it.downloadUrl }
    } ?: throw FailureException(Failure.BadResponse)

/** 每一版附带的版本信息文件名（tools/release/formal_release.py 写出）。 */
private const val MANIFEST_FILE_NAME = "release.json"

/** 文件的 SHA-256，小写十六进制。 */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
