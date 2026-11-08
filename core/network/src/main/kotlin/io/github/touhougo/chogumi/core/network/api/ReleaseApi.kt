package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.ApiJson
import io.github.touhougo.chogumi.core.network.LATEST_RELEASE_MANIFEST_URL
import io.github.touhougo.chogumi.core.network.model.release.GitHubReleaseDto
import io.github.touhougo.chogumi.core.network.model.release.ReleaseManifestDto
import io.github.touhougo.chogumi.core.network.releaseApiUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本应用在 GitHub 上的正式版：最新一版的 release.json（[LATEST_RELEASE_MANIFEST_URL]）、一版的更新说明与安装包。不是 bgm.tv 的接口：
 * 不带令牌（[pinSessionToken] 摘掉），用接口客户端只为共用只读重试与超时。GitHub 经跳转把附件当
 * `application/octet-stream` 给出，内容协商不接，所以 release.json 自己按 [ApiJson] 解码（一份不到 1 KB）。
 */
class ReleaseApi(private val client: HttpClient) {

    suspend fun getLatestManifest(): ReleaseManifestDto {
        val text = client.get(LATEST_RELEASE_MANIFEST_URL) { pinSessionToken(null) }.bodyAsText()
        return ApiJson.decodeFromString<ReleaseManifestDto>(text)
    }

    /** 标签 `v<versionName>` 那一版发布时写的说明（Markdown）；没写为 null 或空串。GitHub 接口的 JSON 走内容协商。 */
    suspend fun getReleaseNotes(versionName: String): String? =
        client.get(releaseApiUrl(versionName)) { pinSessionToken(null) }.body<GitHubReleaseDto>().body

    /**
     * 把 [url] 的安装包整份写进 [target]（已有的覆盖），每写一块报一次 [onProgress]（已写字节、总字节；服务端没给长度为 null）。
     * 约 7 MB，慢网上远超接口请求的 15 秒上限，所以整个请求不限时、只限两次读之间的空档（[STALL_TIMEOUT_MILLIS]）。
     */
    suspend fun downloadTo(url: String, target: File, onProgress: (written: Long, total: Long?) -> Unit) {
        client.prepareGet(url) {
            pinSessionToken(null)
            timeout {
                requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
                socketTimeoutMillis = STALL_TIMEOUT_MILLIS
            }
        }.execute { response ->
            val total = response.contentLength()
            val body = response.bodyAsChannel()
            withContext(Dispatchers.IO) {
                target.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        val read = body.readAvailable(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
        }
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MILLIS = 30 * 60_000L
        const val STALL_TIMEOUT_MILLIS = 30_000L
        const val BUFFER_BYTES = 64 * 1024
    }
}
