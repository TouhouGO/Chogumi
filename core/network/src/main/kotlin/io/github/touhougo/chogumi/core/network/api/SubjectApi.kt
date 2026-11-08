package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.API_V0_HOST
import io.github.touhougo.chogumi.core.network.V0_SEARCH_SUBJECTS_PATH
import io.github.touhougo.chogumi.core.network.model.v0.EpisodeDto
import io.github.touhougo.chogumi.core.network.model.v0.PagedDto
import io.github.touhougo.chogumi.core.network.model.v0.SearchRequestDto
import io.github.touhougo.chogumi.core.network.model.v0.SubjectDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** v0 条目与章节端点 */
class SubjectApi(
    private val client: HttpClient,
) {

    suspend fun getSubject(id: Int, accessToken: String? = null): SubjectDto =
        client.get("$API_V0_HOST/v0/subjects/$id") {
            pinSessionToken(accessToken)
        }.body()

    /** 章节列表（注意：端点是 /v0/episodes 带 subject_id 查询参数，不是 /v0/subjects/{id}/episodes） */
    suspend fun getEpisodes(
        subjectId: Int,
        type: Int? = null,
        limit: Int = 100,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<EpisodeDto> = client.get("$API_V0_HOST/v0/episodes") {
        pinSessionToken(accessToken)
        parameter("subject_id", subjectId)
        parameter("type", type)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /** 条目搜索（meilisearch；sort: match/heat/rank/score） */
    suspend fun searchSubjects(
        request: SearchRequestDto,
        limit: Int = 20,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SubjectDto> = client.post("$API_V0_HOST$V0_SEARCH_SUBJECTS_PATH") {
        pinSessionToken(accessToken)
        parameter("limit", limit)
        parameter("offset", offset)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    /** 条目制作人员/人物（监督、原作、音乐等） */
    suspend fun getPersons(subjectId: Int, accessToken: String? = null): List<io.github.touhougo.chogumi.core.network.model.v0.RelatedPersonDto> =
        client.get("$API_V0_HOST/v0/subjects/$subjectId/persons") {
            pinSessionToken(accessToken)
        }.body()

    /** 分类浏览（GET /v0/subjects；sort: date|rank，rank 即排行榜） */
    suspend fun browseSubjects(
        type: Int,
        cat: String? = null,
        sort: String? = null,
        year: Int? = null,
        month: Int? = null,
        limit: Int = 20,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SubjectDto> = client.get("$API_V0_HOST/v0/subjects") {
        pinSessionToken(accessToken)
        parameter("type", type)
        parameter("cat", cat)
        parameter("sort", sort)
        parameter("year", year)
        parameter("month", month)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()
}
