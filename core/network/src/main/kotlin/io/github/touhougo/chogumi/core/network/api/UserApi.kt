package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.API_V0_HOST
import io.github.touhougo.chogumi.core.network.NEXT_API_HOST
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.BlockListDto
import io.github.touhougo.chogumi.core.network.model.p1.BlogEntryDto
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.CreateReportDto
import io.github.touhougo.chogumi.core.network.model.p1.MessageDto
import io.github.touhougo.chogumi.core.network.model.p1.PagedDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimBlogEntryDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimIndexDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimPersonDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimUserDto
import io.github.touhougo.chogumi.core.network.model.p1.UserDto as P1UserDto
import io.github.touhougo.chogumi.core.network.model.v0.UserDto
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

/**
 * 用户端点。
 *
 * 两个 host 各有分工，不互相替代：
 * - **v0** `/v0/me`：仅本人可见的 `email`/`reg_time`，鉴权必需；
 * - **p1** `/p1/users/{username}`：`site`/`location`/`bio`/`networkServices`/`stats`，
 *   公开资料匿名可读，带上令牌时额外获得 `isFriend`。
 */
class UserApi(
    private val client: HttpClient,
) {

    /**
     * 当前登录用户（需要 Bearer）。
     *
     * 该端点**必须**经 [pinSessionToken] 指定凭据：共享客户端的 `loadTokens` 恒为 null
     * （防串号，见 NetworkModule），没有任何一方会自动补头。不提供无参重载：它会被
     * 展示路径误用，让已登录用户的请求裸奔 → 401 → 界面显示"登录状态已失效"。
     * 登录校验与资料读取统一走这一条，避免再次分叉。
     */
    suspend fun getMe(accessToken: String?): UserDto = client.get("$API_V0_HOST/v0/me") {
        pinSessionToken(accessToken)
    }.body()

    /** p1 用户资料（含统计）；匿名可读，令牌用于获得 `isFriend` 等个性化字段。 */
    suspend fun getP1User(username: String, accessToken: String? = null): P1UserDto =
        client.get("$NEXT_API_HOST/p1/users/${username.encodeURLPathPart()}") {
            pinSessionToken(accessToken)
        }.body()

    /** 按用户名查用户 */
    suspend fun getUser(username: String, accessToken: String?): UserDto =
        client.get("$API_V0_HOST/v0/users/${username.encodeURLPathPart()}") { pinSessionToken(accessToken) }.body()

    // ---- 个人主页的分组内容 ----
    // 以下五个端点都是**公开**的：匿名可读，带上令牌只影响 nsfw 过滤与 isFriend 之类的
    // 个性化字段。因此 accessToken 默认 null，与 getMe 的强制凭据形成对比。

    suspend fun getCharacterCollections(
        username: String,
        limit: Int = 50,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SlimCharacterDto> = paged(username, "collections/characters", limit, offset, accessToken)

    suspend fun getPersonCollections(
        username: String,
        limit: Int = 50,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SlimPersonDto> = paged(username, "collections/persons", limit, offset, accessToken)

    suspend fun getIndexCollections(
        username: String,
        limit: Int = 50,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SlimIndexDto> = paged(username, "collections/indexes", limit, offset, accessToken)

    /**
     * 某人**关注**的人（`chiiFriends.uid = 该用户` 的单向查询）。
     *
     * ## ⚠️ 不要按字面理解"好友"
     *
     * 端点和它自己的 summary 都叫「好友列表 / getUserFriends」，但服务端实现里
     * **没有任何交集运算**（实测 `routes/private/routes/user.ts:105`）：
     *
     * ```ts
     * const conditions = op.and(op.eq(schema.chiiFriends.uid, user.id));
     * ```
     *
     * Bangumi 的关注是**单向**的：A 关注 B 只往 `chiiFriends` 插一行 `(uid=A, fid=B)`，
     * B 回关 A 是**另一行**，不是同一行的翻转。所以：
     *
     * | 语义 | 查询 | 本客户端文案 |
     * |---|---|---|
     * | 我关注的人（follow） | `uid = me` | 我的关注 |
     * | 关注我的人（follower） | `fid = me` | 关注我的 |
     * | **互相关注（服务端语义的"好友"）** | 两个方向的**交集** | 暂无独立入口 |
     *
     * 「互相关注」目前没有端点直接给出，服务端用 `isFriends(uid, another)` 现算
     * （见 `lib/user/utils.ts:103`）。要展示真正的"好友"必须自己取两个方向求交集，
     * **不能**把这个端点当成好友列表用。
     */
    suspend fun getFriends(
        username: String,
        limit: Int = 50,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SlimUserDto> = paged(username, "friends", limit, offset, accessToken)

    /**
     * **关注**某人的人（`chiiFriends.fid = 该用户` 的单向查询）。
     *
     * 与 [getFriends] 是**同一张表的两个方向**，不是子集关系：
     * 一个只被别人关注、没关注任何人的账号会有 `followers` 但 `friends` 为空
     * （本机测试账号实测如此），这是正常数据，不是缺陷。
     *
     * 服务端 summary 写作「获取用户的关注者列表」，与网页版术语一致（旧站不展示此列表）。
     */
    suspend fun getFollowers(
        username: String,
        limit: Int = 50,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SlimUserDto> = paged(username, "followers", limit, offset, accessToken)

    private suspend inline fun <reified T> paged(
        username: String,
        suffix: String,
        limit: Int,
        offset: Int,
        accessToken: String?,
    ): PagedDto<T> = client.get("$NEXT_API_HOST/p1/users/${username.encodeURLPathPart()}/$suffix") {
        pinSessionToken(accessToken)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    // ---- 社交写操作----
    //
    // 四个端点都只 `requireLogin`（不查 scope），凭据必须传。
    // 请求无 body，响应已实测核实（2026-10-03 实测）。

    /** 关注（加好友）`PUT /p1/friends/{username}`；幂等，已存在好友行也返回 `{}`。 */
    suspend fun addFriend(username: String, accessToken: String) {
        client.put("$NEXT_API_HOST/p1/friends/${username.encodeURLPathPart()}") {
            pinSessionToken(accessToken)
        }
    }

    /** 取消关注（取消好友）`DELETE /p1/friends/{username}`。 */
    suspend fun removeFriend(username: String, accessToken: String) {
        client.delete("$NEXT_API_HOST/p1/friends/${username.encodeURLPathPart()}") {
            pinSessionToken(accessToken)
        }
    }

    /** 当前账号的绝交名单 `GET /p1/blocklist`：绝交用户的数字 id。 */
    suspend fun getBlocklist(accessToken: String): BlockListDto =
        client.get("$NEXT_API_HOST/p1/blocklist") {
            pinSessionToken(accessToken)
        }.body()

    /** 绝交 `PUT /p1/blocklist/{username}`，返回绝交后的完整黑名单 id 数组。 */
    suspend fun addUserToBlocklist(username: String, accessToken: String): BlockListDto =
        client.put("$NEXT_API_HOST/p1/blocklist/${username.encodeURLPathPart()}") {
            pinSessionToken(accessToken)
        }.body()

    /** 取消绝交 `DELETE /p1/blocklist/{username}`，返回取消后的完整黑名单 id 数组。 */
    suspend fun removeUserFromBlocklist(username: String, accessToken: String): BlockListDto =
        client.delete("$NEXT_API_HOST/p1/blocklist/${username.encodeURLPathPart()}") {
            pinSessionToken(accessToken)
        }.body()

    /**
     * 报告 `POST /p1/report`。
     *
     * [type] 用 `ReportType.User = 6`（举报用户），[value] 是 `ReportReason` 枚举值
     * （1-9 / 99，对应十种原因）。同一对象重复报告服务端回 409。
     */
    suspend fun createReport(body: CreateReportDto, accessToken: String): MessageDto =
        client.post("$NEXT_API_HOST/p1/report") {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    // ---- 日志 / 目录只读页----
    //
    // 全部公开端点：匿名可读，带令牌只为 nsfw 过滤等个性化。

    /** 某用户创建的日志，`limit`(≤100)/`offset` 分页。 */
    suspend fun getUserBlogs(
        username: String,
        limit: Int = 50,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SlimBlogEntryDto> = paged(username, "blogs", limit, offset, accessToken)

    /** 某用户创建的目录，`limit`/`offset` 分页。 */
    suspend fun getUserIndexes(
        username: String,
        limit: Int = 50,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SlimIndexDto> = paged(username, "indexes", limit, offset, accessToken)

    /** 日志详情（含 BBCode 正文）。 */
    suspend fun getBlogEntry(entryId: Int, accessToken: String? = null): BlogEntryDto =
        client.get("$NEXT_API_HOST/p1/blogs/$entryId") { pinSessionToken(accessToken) }.body()

    /** 日志关联的作品（服务端按关联时间倒序、已剔除对当前用户不可见的 NSFW）。 */
    suspend fun getBlogSubjects(entryId: Int, accessToken: String? = null): List<P1SlimSubjectDto> =
        client.get("$NEXT_API_HOST/p1/blogs/$entryId/subjects") { pinSessionToken(accessToken) }.body()

    /** 日志留言：一次返回全部（不分页），每项带楼中楼；结构与目录留言相同。 */
    suspend fun getBlogComments(entryId: Int, accessToken: String? = null): List<CommentDto> =
        client.get("$NEXT_API_HOST/p1/blogs/$entryId/comments") { pinSessionToken(accessToken) }.body()
}
