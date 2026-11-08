package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.NEXT_API_HOST
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.CreateIndexDto
import io.github.touhougo.chogumi.core.network.model.p1.CreateIndexRelatedDto
import io.github.touhougo.chogumi.core.network.model.p1.CreatedIdDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexDto
import io.github.touhougo.chogumi.core.network.model.p1.IndexRelatedDto
import io.github.touhougo.chogumi.core.network.model.p1.PagedDto
import io.github.touhougo.chogumi.core.network.model.p1.UpdateIndexDto
import io.github.touhougo.chogumi.core.network.model.p1.UpdateIndexRelatedDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * 目录端点（p1）：详情、收录、留言、收藏，以及自己目录的建、改、删与收录、移出。
 * 用户创建 / 收藏的目录**列表**按用户维度留在 [UserApi]。p1 与旧站是同一份数据：这里建的目录旧站上也有。
 * 实测（next.bgm.tv/p1，2026-10-05）：
 * - 详情带令牌才有 `collectedAt`（是否已收藏），所以详情请求要带令牌；
 * - 收录接口没有排序参数：「按评分」只能取全量后在本地排；请求带 `cat` 只取一类，一页才回得满、`total` 才对得上；
 * - 留言一次返回全部（不分页），结构与角色 / 人物吐槽相同。
 */
class IndexApi(
    private val client: HttpClient,
) {

    /** 目录详情（描述、收藏数、留言数、各类型数量）。带令牌时多一个 `collectedAt`：当前用户是否收藏了它。 */
    suspend fun getIndex(indexId: Int, accessToken: String? = null): IndexDto =
        client.get("$NEXT_API_HOST/p1/indexes/$indexId") { pinSessionToken(accessToken) }.body()

    /**
     * 目录收录，分页 `limit`（≤ 100）/ `offset`。[cat] 按类别过滤（`IndexRelatedCategory`：0 条目、1 角色…），
     * 在服务端过滤才能让 `total` 与分页对得上。接口没有排序参数。
     */
    suspend fun getIndexRelated(
        indexId: Int,
        cat: Int,
        limit: Int,
        offset: Int,
        accessToken: String? = null,
    ): PagedDto<IndexRelatedDto> =
        client.get("$NEXT_API_HOST/p1/indexes/$indexId/related") {
            pinSessionToken(accessToken)
            parameter("cat", cat)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    /** 目录留言：一次返回全部（不分页），每项带楼中楼。 */
    suspend fun getIndexComments(indexId: Int, accessToken: String? = null): List<CommentDto> =
        client.get("$NEXT_API_HOST/p1/indexes/$indexId/comments") { pinSessionToken(accessToken) }.body()

    /** 收藏目录（需要登录）。 */
    suspend fun collectIndex(indexId: Int, accessToken: String) {
        client.put("$NEXT_API_HOST/p1/collections/indexes/$indexId") { pinSessionToken(accessToken) }
    }

    /** 取消收藏目录（需要登录）。 */
    suspend fun uncollectIndex(indexId: Int, accessToken: String) {
        client.delete("$NEXT_API_HOST/p1/collections/indexes/$indexId") { pinSessionToken(accessToken) }
    }

    /** 新建目录，回新目录的 id。[title] 1–80 字（契约），[isPrivate] 为真是私密目录（只有自己看得到）。 */
    suspend fun createIndex(title: String, desc: String, isPrivate: Boolean, accessToken: String): Int {
        require(title.isNotBlank() && title.length <= MAX_INDEX_TITLE_LENGTH)
        return client.post("$NEXT_API_HOST/p1/indexes") {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateIndexDto(title = title, desc = desc, `private` = isPrivate))
        }.body<CreatedIdDto>().id.toInt()
    }

    /** 改自己的目录：名称、介绍、公开 / 私密三项一起写（界面的编辑面板总是带着三项）。 */
    suspend fun updateIndex(indexId: Int, title: String, desc: String, isPrivate: Boolean, accessToken: String) {
        require(title.isNotBlank() && title.length <= MAX_INDEX_TITLE_LENGTH)
        client.patch("$NEXT_API_HOST/p1/indexes/$indexId") {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateIndexDto(title = title, desc = desc, `private` = isPrivate))
        }
    }

    /** 删除自己的目录。 */
    suspend fun deleteIndex(indexId: Int, accessToken: String) {
        client.delete("$NEXT_API_HOST/p1/indexes/$indexId") { pinSessionToken(accessToken) }
    }

    /**
     * 往自己的目录里收录一样东西（[cat] 见 `IndexRelatedCategory`，[sid] 是那样东西的 id），回这条收录自己的 id
     * （移出、改评语都用它，不是 [sid]）。[comment] 是收录理由，空串表示不写。
     */
    suspend fun addRelated(indexId: Int, cat: Int, sid: Int, comment: String, accessToken: String): Int =
        client.put("$NEXT_API_HOST/p1/indexes/$indexId/related") {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateIndexRelatedDto(cat = cat, sid = sid, comment = comment.takeIf { it.isNotEmpty() }))
        }.body<CreatedIdDto>().id.toInt()

    /** 改一条收录的排序与评语：契约里两项都必填，只改一项也要把另一项原样带上。 */
    suspend fun updateRelated(indexId: Int, relatedId: Int, order: Int, comment: String, accessToken: String) {
        client.patch("$NEXT_API_HOST/p1/indexes/$indexId/related/$relatedId") {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateIndexRelatedDto(order = order, comment = comment))
        }
    }

    /** 从自己的目录里移出一条收录（[relatedId] 是收录行自己的 id）。 */
    suspend fun deleteRelated(indexId: Int, relatedId: Int, accessToken: String) {
        client.delete("$NEXT_API_HOST/p1/indexes/$indexId/related/$relatedId") { pinSessionToken(accessToken) }
    }

    companion object {
        /** 契约 `CreateIndex.title` 的 `maxLength`。 */
        const val MAX_INDEX_TITLE_LENGTH = 80
    }
}
