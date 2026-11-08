package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.API_V0_HOST
import io.github.touhougo.chogumi.core.network.model.v0.BatchEpisodeStatusDto
import io.github.touhougo.chogumi.core.network.model.v0.CollectionModifyDto
import io.github.touhougo.chogumi.core.network.model.v0.EpisodeCollectionPageDto
import io.github.touhougo.chogumi.core.network.model.v0.EpisodeStatusDto
import io.github.touhougo.chogumi.core.network.model.v0.PagedDto
import io.github.touhougo.chogumi.core.network.model.v0.UserCollectionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/**
 * PATCH 收藏返回 404 的类型化信号（spec v0.yaml：404 = "用户不存在或者条目未收藏"）。
 *
 * 由 [CollectionApi.patchCollection] 在 core:network 内部把 Ktor 的 ClientRequestException(404) 翻译成本异常
 * 再抛出——数据层据此回退 POST 创建收藏，同时不必在 core:data 里直接捕获 Ktor 异常类型（模块边界：ktor 依赖是
 * implementation）。
 */
class CollectionNotCollectedException(cause: ClientRequestException) :
    RuntimeException("条目未收藏（404）", cause)

/**
 * 每个请求绑定发起时的账户，避免排队中的写请求随全局 token 切换身份。
 * 写接口的令牌必传（非空、无默认）：漏传就编译不过，不会匿名发出去再挨 401。
 */
class CollectionApi(private val client: HttpClient) {
    suspend fun getUserCollections(username: String, subjectType: Int, type: Int? = null, limit: Int = 50, offset: Int = 0, accessToken: String? = null): PagedDto<UserCollectionDto> =
        client.get("$API_V0_HOST/v0/users/${username.encodeURLPathPart()}/collections") {
            pinSessionToken(accessToken)
            parameter("subject_type", subjectType)
            parameter("type", type)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    suspend fun getCollection(username: String, subjectId: Int, accessToken: String? = null): UserCollectionDto =
        client.get("$API_V0_HOST/v0/users/${username.encodeURLPathPart()}/collections/$subjectId") { pinSessionToken(accessToken) }.body()

    suspend fun postCollection(subjectId: Int, body: CollectionModifyDto, accessToken: String) {
        client.post("$API_V0_HOST/v0/users/-/collections/$subjectId") {
            pinSessionToken(accessToken); contentType(ContentType.Application.Json); setBody(body)
        }
    }

    suspend fun patchCollection(subjectId: Int, body: CollectionModifyDto, accessToken: String) {
        try {
            client.patch("$API_V0_HOST/v0/users/-/collections/$subjectId") {
                pinSessionToken(accessToken); contentType(ContentType.Application.Json); setBody(body)
            }
        } catch (e: ClientRequestException) {
            // PATCH 404 语义是「条目未收藏」（spec v0.yaml），翻译成类型化异常供数据层回退 POST；
            // 其余 4xx（400 参数错误等）原样抛出，语义不得混淆。
            if (e.response.status == HttpStatusCode.NotFound) throw CollectionNotCollectedException(e)
            throw e
        }
    }

    /** 单集观看状态 PUT（条目须已收藏，未收藏时服务端回 400 / 404）。 */
    suspend fun putEpisodeStatus(episodeId: Int, type: Int, accessToken: String) {
        client.put("$API_V0_HOST/v0/users/-/collections/-/episodes/$episodeId") {
            pinSessionToken(accessToken); contentType(ContentType.Application.Json); setBody(EpisodeStatusDto(type))
        }
    }

    suspend fun patchEpisodeBatch(subjectId: Int, episodeIds: List<Int>, type: Int, accessToken: String) {
        client.patch("$API_V0_HOST/v0/users/-/collections/$subjectId/episodes") {
            pinSessionToken(accessToken); contentType(ContentType.Application.Json); setBody(BatchEpisodeStatusDto(episodeIds, type))
        }
    }

    /**
     * 逐集观看状态读取（本账号）。
     *
     * **返回条目的全部章节**（分页），每项自带 `type`（0 无记录 / 1 想看 / 2 看过 / 3 抛弃）
     * 与 `updated_at`。这是「某集是否已看」的可信来源：即使某集从未被标记，
     * 它也会以 `type = 0` 出现在响应里，不需要「不在响应里就算未看」的推断。
     *
     * 与之相对，p1 `/p1/home` 的 `eps[].collection` 只在**有非 0 记录时**才出现，
     * 且整条 `eps[]` 超过 72 集会被截断（2026-09-29 实测），
     * 因此不适合作为逐集状态的唯一来源。
     *
     * `limit` 上限 1000（spec v0.yaml）；调用方按 `total` 翻页取完。
     */
    suspend fun getUserEpisodeCollections(
        subjectId: Int,
        limit: Int = 100,
        offset: Int = 0,
        accessToken: String? = null,
    ): EpisodeCollectionPageDto =
        client.get("$API_V0_HOST/v0/users/-/collections/$subjectId/episodes") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
}
