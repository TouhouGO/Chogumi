package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.NEXT_API_HOST
import io.github.touhougo.chogumi.core.network.model.p1.CreateTopicRequestDto
import io.github.touhougo.chogumi.core.network.model.p1.CreatedIdDto
import io.github.touhougo.chogumi.core.network.model.p1.GroupDto
import io.github.touhougo.chogumi.core.network.model.p1.GroupTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.PagedDto
import io.github.touhougo.chogumi.core.network.model.p1.RaKuenTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimGroupDto
import io.github.touhougo.chogumi.core.network.model.p1.TopicDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/** 社区（p1）：超展开、小组新帖，小组本身（列表、小组页、小组里的帖子、发帖）。帖子页的楼层不在这里。 */
class CommunityApi(private val client: HttpClient) {

    /**
     * 超展开：全站讨论按最后回复倒序。[type] 是 all|group|my_group|subject|episode|character|person；
     * 没有 offset，一次最多 [MAX_RAKUEN_LIMIT] 条（`total` 是本次条数，不是全站总数）。
     */
    suspend fun getRakuenTopics(type: String, limit: Int, accessToken: String? = null): PagedDto<RaKuenTopicDto> {
        require(limit in 1..MAX_RAKUEN_LIMIT)
        return client.get("$NEXT_API_HOST/p1/rakuen/topics") {
            pinSessionToken(accessToken)
            parameter("type", type)
            parameter("limit", limit)
        }.body()
    }

    /**
     * 小组新帖：[mode] 是 all（全站）| joined（我加入的小组）| created | replied，后三种要登录。
     * 按 offset 翻页，[limit] 上限 [MAX_GROUP_TOPICS_LIMIT]；每帖的 `replies` 为空表（楼层在帖子页取）。
     */
    suspend fun getGroupTopics(mode: String, limit: Int, offset: Int, accessToken: String? = null): PagedDto<GroupTopicDto> {
        require(limit in 1..MAX_GROUP_TOPICS_LIMIT)
        return client.get("$NEXT_API_HOST/p1/groups/-/topics") {
            pinSessionToken(accessToken)
            parameter("mode", mode)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    }

    /**
     * 小组列表：[mode] 是 all | joined（我加入的，要登录）| managed；[sort] 是 members | posts | topics | created | updated。
     * 按 offset 翻页，[limit] 上限 [MAX_GROUPS_LIMIT]。实测 updated 与 posts 返回的是建组先后，不是活跃度。
     */
    suspend fun getGroups(mode: String, sort: String, limit: Int, offset: Int, accessToken: String? = null): PagedDto<SlimGroupDto> {
        require(limit in 1..MAX_GROUPS_LIMIT)
        return client.get("$NEXT_API_HOST/p1/groups") {
            pinSessionToken(accessToken)
            parameter("mode", mode)
            parameter("sort", sort)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    }

    /** 小组详情：简介、成员数、话题数；登录时 `membership` 是我在这个小组里的身份（没加入为 null）。 */
    suspend fun getGroup(name: String, accessToken: String? = null): GroupDto =
        client.get("$NEXT_API_HOST/p1/groups/${name.encodeURLPathPart()}") { pinSessionToken(accessToken) }.body()

    /** 一个小组的帖子，按最后回复倒序、按 offset 翻页（[limit] 上限 [MAX_GROUP_TOPICS_LIMIT]）。 */
    suspend fun getTopicsOfGroup(name: String, limit: Int, offset: Int, accessToken: String? = null): PagedDto<TopicDto> {
        require(limit in 1..MAX_GROUP_TOPICS_LIMIT)
        return client.get("$NEXT_API_HOST/p1/groups/${name.encodeURLPathPart()}/topics") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    }

    /**
     * 在小组里发帖，回新帖的 id。[turnstileToken] 是用户刚过的人机验证令牌；非公开小组要先加入
     * （错误码 `NOT_JOIN_PRIVATE_GROUP_ERROR`），频率限制回 429。
     */
    suspend fun createGroupTopic(name: String, title: String, content: String, turnstileToken: String, accessToken: String): Int {
        require(title.isNotBlank() && content.isNotBlank())
        return client.post("$NEXT_API_HOST/p1/groups/${name.encodeURLPathPart()}/topics") {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateTopicRequestDto(title, content, turnstileToken))
        }.body<CreatedIdDto>().id.toInt()
    }

    companion object {
        /** 契约 `/p1/rakuen/topics` 的 `limit` 上限（`maximum: 200`）。 */
        const val MAX_RAKUEN_LIMIT = 200

        /** 契约 `/p1/groups/-/topics` 的 `limit` 上限（`maximum: 100`）。 */
        const val MAX_GROUP_TOPICS_LIMIT = 100

        /** 契约 `/p1/groups` 的 `limit` 上限（`maximum: 100`）。 */
        const val MAX_GROUPS_LIMIT = 100
    }
}
