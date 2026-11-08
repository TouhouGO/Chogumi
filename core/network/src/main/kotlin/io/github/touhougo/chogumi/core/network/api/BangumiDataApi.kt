package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.ApiJson
import io.github.touhougo.chogumi.core.network.BANGUMI_DATA_URL
import io.github.touhougo.chogumi.core.network.model.bangumidata.BangumiDataDto
import io.github.touhougo.chogumi.core.network.model.bangumidata.BangumiDataItemDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * bangumi-data：社区维护的每季放送时刻表，取 GitHub 上的原始文件（[BANGUMI_DATA_URL]）。不是 bgm.tv 的接口：不带令牌
 * （[pinSessionToken] 摘掉），用接口客户端只为共用只读重试。GitHub 把这个 JSON 标成 `text/plain`，内容协商不接，
 * 所以这里自己按 [ApiJson] 解码（同样挪到 [Dispatchers.Default]）。
 *
 * 整份约 7.9 MB（gzip 后 1.5 MB），调用方隔几小时才取一次，并带上次响应的 ETag：没变时服务端回 304，省掉整份下载。
 * 这一个下载比别的请求大两个数量级，请求超时单独放宽到 [DOWNLOAD_TIMEOUT_MILLIS]；慢网上取不到只是先用旧表，不挡页面。
 */
class BangumiDataApi(private val client: HttpClient) {

    /** 一次取数的结果：整份新数据与它的 ETag，或服务端说没变。 */
    sealed interface Result {
        data class Fresh(val items: List<BangumiDataItemDto>, val etag: String?) : Result
        data object NotModified : Result
    }

    /** [etag] 是上次 [Result.Fresh] 带回的；没有就无条件取整份。 */
    suspend fun getData(etag: String?): Result {
        val response = try {
            client.get(BANGUMI_DATA_URL) {
                pinSessionToken(null)
                etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS }
            }
        } catch (e: RedirectResponseException) {
            // 接口客户端把 3xx 当失败抛出；304 是这里唯一要的 3xx
            if (e.response.status == HttpStatusCode.NotModified) return Result.NotModified
            throw e
        }
        val text = response.bodyAsText()
        val items = withContext(Dispatchers.Default) { ApiJson.decodeFromString<BangumiDataDto>(text).items }
        return Result.Fresh(items, response.headers[HttpHeaders.ETag])
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MILLIS = 60_000L
    }
}
