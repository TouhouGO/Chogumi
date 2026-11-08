package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.NEXT_API_HOST
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.CreateTimelineReplyRequestDto
import io.github.touhougo.chogumi.core.network.model.p1.CreateTimelineSayRequestDto
import io.github.touhougo.chogumi.core.network.model.p1.CreatedIdDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineLikeRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/** p1 时间线，依据 routes/private/routes/{timeline,user}.ts；until 是 ID 而非时间。 */
class TimelineApi(private val client: HttpClient) {
    suspend fun getTimeline(mode: String, limit: Int = 20, until: Long? = null, accessToken: String? = null): List<TimelineDto> {
        require(mode == "friends" || mode == "all")
        require(limit in 1..20)
        return client.get("$NEXT_API_HOST/p1/timeline") {
            pinSessionToken(accessToken)
            parameter("mode", mode)
            parameter("limit", limit)
            parameter("until", until)
        }.body()
    }

    suspend fun getUserTimeline(username: String, limit: Int = 20, until: Long? = null, accessToken: String? = null): List<TimelineDto> {
        require(username.isNotBlank())
        require(limit in 1..20)
        return client.get("$NEXT_API_HOST/p1/users/${username.encodeURLPathPart()}/timeline") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("until", until)
        }.body()
    }

    suspend fun getReplies(id: Long, accessToken: String? = null): List<CommentDto> =
        client.get("$NEXT_API_HOST/p1/timeline/$id/replies") { pinSessionToken(accessToken) }.body()

    suspend fun publish(content: String, turnstileToken: String, accessToken: String): Long {
        require(content.isNotBlank())
        require(turnstileToken.isNotBlank()) { "请先完成 Turnstile 验证" }
        return client.post("$NEXT_API_HOST/p1/timeline") {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateTimelineSayRequestDto(content, turnstileToken))
        }.body<CreatedIdDto>().id
    }

    suspend fun reply(id: Long, content: String, replyTo: Long, turnstileToken: String, accessToken: String): Long {
        require(content.isNotBlank())
        require(replyTo >= 0)
        require(turnstileToken.isNotBlank()) { "请先完成 Turnstile 验证" }
        return client.post("$NEXT_API_HOST/p1/timeline/$id/replies") {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateTimelineReplyRequestDto(content, turnstileToken, replyTo))
        }.body<CreatedIdDto>().id
    }

    suspend fun delete(id: Long, accessToken: String) {
        client.delete("$NEXT_API_HOST/p1/timeline/$id") { pinSessionToken(accessToken) }
    }

    /** 说说的贴贴：[value] 非 null 是贴（替换我原来那个），null 是取消。收藏的贴贴不在这里（`NextApi.setSubjectCollectReaction`）。 */
    suspend fun react(id: Long, value: Int?, accessToken: String) {
        if (value != null) {
            client.put("$NEXT_API_HOST/p1/timeline/$id/like") {
                pinSessionToken(accessToken)
                contentType(ContentType.Application.Json)
                setBody(TimelineLikeRequestDto(value))
            }
        } else {
            // DELETE 没有 value 参数：服务端删除此用户在该状态上的 reaction。
            client.delete("$NEXT_API_HOST/p1/timeline/$id/like") { pinSessionToken(accessToken) }
        }
    }
}
