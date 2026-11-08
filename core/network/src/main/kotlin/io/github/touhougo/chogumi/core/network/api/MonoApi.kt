package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.NEXT_API_HOST
import io.github.touhougo.chogumi.core.network.model.p1.CharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.PagedDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonWorkDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimIndexDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put

/** 角色与人物在 p1 上的资源段：两类实体形状相同的接口（目录、吐槽、收藏）按它拼路径。 */
enum class MonoResource(internal val path: String) {
    CHARACTERS("characters"),
    PERSONS("persons"),
}

/** 角色 / 人物接口。返回体不同的（详情、作品、关联）各一个方法，形状相同的按 [MonoResource] 共用。 */
class MonoApi(private val client: HttpClient) {
    suspend fun getCharacter(id: Int, accessToken: String? = null): CharacterDto =
        client.get("$NEXT_API_HOST/p1/characters/$id") {
            pinSessionToken(accessToken)
        }.body()

    suspend fun getPerson(id: Int, accessToken: String? = null): PersonDto =
        client.get("$NEXT_API_HOST/p1/persons/$id") {
            pinSessionToken(accessToken)
        }.body()

    suspend fun getCharacterCasts(id: Int, limit: Int = 30, offset: Int = 0, accessToken: String? = null): PagedDto<CharacterSubjectDto> =
        client.get("$NEXT_API_HOST/p1/characters/$id/casts") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    suspend fun getPersonWorks(id: Int, limit: Int = 30, offset: Int = 0, accessToken: String? = null): PagedDto<PersonWorkDto> =
        client.get("$NEXT_API_HOST/p1/persons/$id/works") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    suspend fun getPersonCasts(
        id: Int,
        limit: Int = 30,
        offset: Int = 0,
        subjectType: Int? = null,
        type: Int? = null,
        accessToken: String? = null,
    ): PagedDto<PersonCharacterDto> =
        client.get("$NEXT_API_HOST/p1/persons/$id/casts") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("offset", offset)
            parameter("subjectType", subjectType)
            parameter("type", type)
        }.body()

    suspend fun getCharacterRelations(id: Int, limit: Int = 30, offset: Int = 0, accessToken: String? = null): PagedDto<CharacterRelationDto> =
        client.get("$NEXT_API_HOST/p1/characters/$id/relations") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    suspend fun getPersonRelations(id: Int, limit: Int = 30, offset: Int = 0, accessToken: String? = null): PagedDto<PersonRelationDto> =
        client.get("$NEXT_API_HOST/p1/persons/$id/relations") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    suspend fun getIndexes(resource: MonoResource, id: Int, limit: Int = 30, offset: Int = 0, accessToken: String? = null): PagedDto<SlimIndexDto> =
        client.get("$NEXT_API_HOST/p1/${resource.path}/$id/indexes") {
            pinSessionToken(accessToken)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    suspend fun getComments(resource: MonoResource, id: Int, accessToken: String? = null): List<CommentDto> =
        client.get("$NEXT_API_HOST/p1/${resource.path}/$id/comments") {
            pinSessionToken(accessToken)
        }.body()

    suspend fun setCollected(resource: MonoResource, id: Int, collected: Boolean, accessToken: String) {
        val url = "$NEXT_API_HOST/p1/collections/${resource.path}/$id"
        if (collected) client.put(url) { pinSessionToken(accessToken) } else client.delete(url) { pinSessionToken(accessToken) }
    }
}
