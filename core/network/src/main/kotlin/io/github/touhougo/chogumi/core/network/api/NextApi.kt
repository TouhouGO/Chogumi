package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.NEXT_API_HOST
import io.github.touhougo.chogumi.core.network.model.p1.CalendarItemDto
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.ClearNotifyRequestDto
import io.github.touhougo.chogumi.core.network.model.p1.CreateTimelineReplyRequestDto
import io.github.touhougo.chogumi.core.network.model.p1.CreatedIdDto
import io.github.touhougo.chogumi.core.network.model.p1.EmptyObjectDto
import io.github.touhougo.chogumi.core.network.model.p1.EpisodeDto
import io.github.touhougo.chogumi.core.network.model.p1.FriendSubjectCollectionActivityDto
import io.github.touhougo.chogumi.core.network.model.p1.GroupTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.HomeResponseDto
import io.github.touhougo.chogumi.core.network.model.p1.NoticeDto
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.PagedDto
import io.github.touhougo.chogumi.core.network.model.p1.SearchSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimIndexDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectCollectDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectInterestCommentDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectRecDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectReviewDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectTagDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineLikeRequestDto
import io.github.touhougo.chogumi.core.network.model.p1.TopicDto
import io.github.touhougo.chogumi.core.network.model.p1.UpdateContentDto
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

/** 带评论区的对象（`/p1/{path}/{id}/comments`，发一条与回复楼层同一个接口）；角色、人物的段由 [MonoResource] 给出。 */
class CommentOwner private constructor(val path: String) {
    companion object {
        val BLOG = CommentOwner("blogs")
        val INDEX = CommentOwner("indexes")
        val EPISODE = CommentOwner("episodes")

        fun mono(resource: MonoResource) = CommentOwner(resource.path)
    }
}

/**
 * NextAPI（p1）上不归属其他专门接口类的端点：每日放送、分类与标签浏览、搜索与标签目录、条目详情的子模块
 * （关联、角色、吐槽、讨论、评论、推荐、目录、收藏动态）、帖子回复与贴贴、章节与章节吐槽、首页进度、好友收藏动态、通知。
 * 每个请求经 [pinSessionToken] 带上调用方给定的会话令牌（null 为匿名）。
 */
class NextApi(
    private val client: HttpClient,
) {

    /** 每日放送（响应是 星期→条目列表 的 map） */
    suspend fun getCalendar(accessToken: String? = null): Map<String, List<CalendarItemDto>> =
        client.get("$NEXT_API_HOST/p1/calendar") { pinSessionToken(accessToken) }.body()

    /**
     * 分类与标签浏览（网页「分类浏览」与「标签页」同源）。sort: rank|trends|collects|date|title；
     * tags 为标签值，多个同时成立，每个值单独一个 `tags` 参数；按页取（每页 24），返回的 total 是**页数**。
     * [tagsCat] 为 "subject" 时走单用户标签浏览；null 时走分类浏览。登录时带会话令牌（取舍见 `SearchRepository.browse`）。
     */
    suspend fun browseSubjects(
        type: Int,
        sort: String,
        tags: List<String> = emptyList(),
        year: Int? = null,
        month: Int? = null,
        page: Int = 1,
        tagsCat: String? = null,
        accessToken: String? = null,
    ): PagedDto<P1SlimSubjectDto> = client.get("$NEXT_API_HOST/p1/subjects") {
        pinSessionToken(accessToken)
        parameter("type", type)
        parameter("sort", sort)
        parameter("tagsCat", tagsCat)
        tags.forEach { parameter("tags", it) }
        parameter("year", year)
        parameter("month", month)
        parameter("page", page)
    }.body()

    /**
     * 条目搜索（sort: match/heat/rank/score；[limit] 上限 100），回短行（[P1SlimSubjectDto]），`total` 是条数。
     * 与 v0 `/v0/search/subjects` 读同一个索引；`filter.date` 在服务端恒为 0 条（2026-10-16 实测），带时间条件的搜索走 v0。
     */
    suspend fun searchSubjects(
        request: SearchSubjectDto,
        limit: Int,
        offset: Int,
        accessToken: String? = null,
    ): PagedDto<P1SlimSubjectDto> = client.post("$NEXT_API_HOST/p1/search/subjects") {
        pinSessionToken(accessToken)
        parameter("limit", limit)
        parameter("offset", offset)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    /**
     * 频道热门标签目录。
     *
     * 按**使用次数倒序**返回某类型的标签（每项 `{name, count}`），`total` 是该类型的标签总数。
     * [limit] 上限 100（实测 101 返回 400）；[offset] 按名次翻页，50 次请求可取前 5000。
     * 恒为匿名请求：2026-09-30 实测带不带令牌结果一致，故不接收令牌参数。
     *
     * 服务端**没有**「按关键词搜标签」的接口，也没有标签级 NSFW 字段
     * （2026-09-30 实测），查找与过滤都在本地做。
     */
    suspend fun getChannelTags(
        type: Int,
        limit: Int = 100,
        offset: Int = 0,
    ): PagedDto<SubjectTagDto> = client.get("$NEXT_API_HOST/p1/channels/$type/tags") {
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /**
     * 条目关联：每项带关系类型 ID（服务端按关系 ID、再按 order 升序返回）。
     *
     * **请求哪些关系由 [offprint] 决定，严格二选一、都不含对方**（关系 ID 1003 即单行本：`offprint = true` 取 `relation = 1003`，
     * `false` 取 `relation != 1003`）：
     * - `false`：除单行本外的全部关联；
     * - `true`：**只有**单行本。
     *
     * 不传等同 `false`（schema 默认值为 `false`；
     * 2026-09-28 实测 118165 不传与 `false` 结果完全一致），**因此要全量只能两路各请求一次再合并**——
     * 服务端只有一个开关，没有「两类一起返回」的口子。
     *
     * 组内顺序即卷号：服务端 `order` 就是卷号（2026-09-28 实测 3510 ONE PIECE 115 本
     * order 1..115 与卷号一致），故按 `order` 升序即正确，无需另设排序。
     *
     * 无需登录（2026-09-27 实测匿名 200）；limit 上限 100。
     */
    suspend fun getSubjectRelations(
        subjectId: Int,
        offprint: Boolean,
        limit: Int = 100,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SubjectRelationDto> = client.get("$NEXT_API_HOST/p1/subjects/$subjectId/relations") {
        pinSessionToken(accessToken)
        parameter("offprint", offprint)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /**
     * 条目角色：按定位（主角、配角、客串）再按 order 排好，每项带中文名与声优（2026-10-13 实测）。
     * limit 上限 100；绝大多数条目一页取完（实测 607915 共 28 个），长篇才多页（975 共 1051 个）。
     */
    suspend fun getSubjectCharacters(
        subjectId: Int,
        limit: Int = 100,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SubjectCharacterDto> = client.get("$NEXT_API_HOST/p1/subjects/$subjectId/characters") {
        pinSessionToken(accessToken)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /**
     * 条目吐槽（收藏短评），新的在前。只能按收藏状态筛，没有「只看好友 / 有评分」参数
     * （2026-10-05 实测）。limit 上限 100。
     */
    suspend fun getSubjectComments(
        subjectId: Int,
        limit: Int = 20,
        offset: Int = 0,
        accessToken: String? = null,
        /** 只看某个收藏状态（1 想看 … 5 抛弃）；null = 全部。 */
        type: Int? = null,
    ): PagedDto<SubjectInterestCommentDto> = client.get("$NEXT_API_HOST/p1/subjects/$subjectId/comments") {
        pinSessionToken(accessToken)
        parameter("type", type)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /** 条目讨论版话题列表（不含楼层）。limit 上限 100。 */
    suspend fun getSubjectTopics(
        subjectId: Int,
        limit: Int = 20,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<TopicDto> = client.get("$NEXT_API_HOST/p1/subjects/$subjectId/topics") {
        pinSessionToken(accessToken)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /**
     * 条目话题详情：**一次返回全部楼层**（两层），没有分页参数。
     * 实测 7822 楼的话题 892 响应 5.1 MB、0.59 s（2026-10-05 实测）。
     */
    suspend fun getSubjectTopic(topicId: Int, accessToken: String? = null): SubjectTopicDto =
        client.get("$NEXT_API_HOST/p1/subjects/-/topics/$topicId") { pinSessionToken(accessToken) }.body()

    /**
     * 回复作品讨论版的话题，返回新楼的 id（服务端只回 id，楼层要重新拉）。[replyTo] 0 = 新开一层，否则是所在主楼的 id：
     * 服务端把它原样存成楼中楼的归属，填楼中楼的 id 会挂到不存在的主楼下、谁都看不见。需要登录与人机验证令牌。
     */
    suspend fun replySubjectTopic(topicId: Int, content: String, replyTo: Int, turnstileToken: String, accessToken: String): Int =
        postReply("$NEXT_API_HOST/p1/subjects/-/topics/$topicId/replies", content, replyTo, turnstileToken, accessToken)

    /** 回复小组的话题，语义同 [replySubjectTopic]；非公开小组要先加入（错误码 `NOT_JOIN_PRIVATE_GROUP_ERROR`）。 */
    suspend fun replyGroupTopic(topicId: Int, content: String, replyTo: Int, turnstileToken: String, accessToken: String): Int =
        postReply("$NEXT_API_HOST/p1/groups/-/topics/$topicId/replies", content, replyTo, turnstileToken, accessToken)

    /**
     * 在 [owner] 的 [id] 的评论区发一条，返回新楼的 id（楼层要重新拉）。[replyTo] 语义同 [replySubjectTopic]：0 = 新的一条，
     * 否则是所在主楼的 id。需要登录与人机验证令牌。
     */
    suspend fun replyComments(owner: CommentOwner, id: Int, content: String, replyTo: Int, turnstileToken: String, accessToken: String): Int =
        postReply("$NEXT_API_HOST/p1/${owner.path}/$id/comments", content, replyTo, turnstileToken, accessToken)

    /** [owner] 的 [id] 的评论区全部楼层（与 [replyComments] 同一个地址）：发一条之后回读确认用。需要登录。 */
    suspend fun getComments(owner: CommentOwner, id: Int, accessToken: String): List<CommentDto> =
        client.get("$NEXT_API_HOST/p1/${owner.path}/$id/comments") { pinSessionToken(accessToken) }.body()

    /**
     * 改自己在作品讨论版的一层楼（主楼与楼中楼、帖子正文都是楼）。不需要人机验证。服务端只许改自己的、没删除的、
     * 楼下还没有楼中楼的，话题关闭后不许改，这些都回 403。需要登录。
     */
    suspend fun editSubjectPost(postId: Int, content: String, accessToken: String) =
        editPost("$NEXT_API_HOST/p1/subjects/-/posts/$postId", content, accessToken)

    /** 删除自己在作品讨论版的一层楼（服务端记成「用户删除」，楼层留着、正文不可见）。不需要人机验证。需要登录。 */
    suspend fun deleteSubjectPost(postId: Int, accessToken: String) {
        client.delete("$NEXT_API_HOST/p1/subjects/-/posts/$postId") { pinSessionToken(accessToken) }
    }

    /** 改自己在小组话题里的一层楼，语义同 [editSubjectPost]。 */
    suspend fun editGroupPost(postId: Int, content: String, accessToken: String) =
        editPost("$NEXT_API_HOST/p1/groups/-/posts/$postId", content, accessToken)

    /** 删除自己在小组话题里的一层楼，语义同 [deleteSubjectPost]。 */
    suspend fun deleteGroupPost(postId: Int, accessToken: String) {
        client.delete("$NEXT_API_HOST/p1/groups/-/posts/$postId") { pinSessionToken(accessToken) }
    }

    /** 改楼端点的统一写法：PUT `{content}`，回空对象。 */
    private suspend fun editPost(url: String, content: String, accessToken: String) {
        require(content.isNotBlank())
        client.put(url) {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateContentDto(content))
        }
    }

    /** 楼层回复端点的统一写法：POST `{content, replyTo, turnstileToken}`，回 `{id}`。 */
    private suspend fun postReply(url: String, content: String, replyTo: Int, turnstileToken: String, accessToken: String): Int {
        require(content.isNotBlank())
        require(replyTo >= 0)
        return client.post(url) {
            pinSessionToken(accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateTimelineReplyRequestDto(content, turnstileToken, replyTo.toLong()))
        }.body<CreatedIdDto>().id.toInt()
    }

    /** 讨论版楼层（主楼与楼中楼）贴贴：[value] 非 null 是贴（替换我原来那个），null 是取消。需要登录。 */
    suspend fun setSubjectPostReaction(postId: Int, value: Int?, accessToken: String) =
        setReaction("$NEXT_API_HOST/p1/subjects/-/posts/$postId/like", value, accessToken)

    /** 小组话题详情：与 [getSubjectTopic] 同一形状（一次给全部楼层），上级是小组。 */
    suspend fun getGroupTopic(topicId: Int, accessToken: String? = null): GroupTopicDto =
        client.get("$NEXT_API_HOST/p1/groups/-/topics/$topicId") { pinSessionToken(accessToken) }.body()

    /** 小组楼层（主楼与楼中楼）贴贴，语义同 [setSubjectPostReaction]。 */
    suspend fun setGroupPostReaction(postId: Int, value: Int?, accessToken: String) =
        setReaction("$NEXT_API_HOST/p1/groups/-/posts/$postId/like", value, accessToken)

    /** 作品吐槽（收藏评论）贴贴，语义同 [setSubjectPostReaction]。 */
    suspend fun setSubjectCollectReaction(collectId: Int, value: Int?, accessToken: String) =
        setReaction("$NEXT_API_HOST/p1/subjects/-/collects/$collectId/like", value, accessToken)

    /** 一集：集号、标题、播出日、简介，连同它属于哪部作品（`subject`，单集讨论页的眉题）。 */
    suspend fun getEpisode(episodeId: Int, accessToken: String? = null): EpisodeDto =
        client.get("$NEXT_API_HOST/p1/episodes/$episodeId") { pinSessionToken(accessToken) }.body()

    /** 单集评论：一次返回全部楼层（不分页），每项带楼中楼；结构与日志留言相同。 */
    suspend fun getEpisodeComments(episodeId: Int, accessToken: String? = null): List<CommentDto> =
        client.get("$NEXT_API_HOST/p1/episodes/$episodeId/comments") { pinSessionToken(accessToken) }.body()

    /** 单集评论（主楼与楼中楼）贴贴，语义同 [setSubjectPostReaction]。 */
    suspend fun setEpisodeCommentReaction(commentId: Int, value: Int?, accessToken: String) =
        setReaction("$NEXT_API_HOST/p1/episodes/-/comments/$commentId/like", value, accessToken)

    /** 贴贴端点的统一写法：PUT `{value}` 贴、DELETE 取消。 */
    private suspend fun setReaction(url: String, value: Int?, accessToken: String) {
        if (value != null) {
            client.put(url) {
                pinSessionToken(accessToken)
                contentType(ContentType.Application.Json)
                setBody(TimelineLikeRequestDto(value))
            }
        } else {
            client.delete(url) { pinSessionToken(accessToken) }
        }
    }

    /** 条目评论（用户日志）。limit 上限 **20**（契约）。 */
    suspend fun getSubjectReviews(
        subjectId: Int,
        limit: Int = 20,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SubjectReviewDto> = client.get("$NEXT_API_HOST/p1/subjects/$subjectId/reviews") {
        pinSessionToken(accessToken)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /**
     * 条目推荐：网页作品页「喜欢 … 的会员大概会喜欢」那一栏，服务端按收藏算好、已排好序，最多 10 部（契约 limit 上限 10）。
     * `sim` 与 `count` 实测只是名次与 0（2026-10-13，317613），不当相似度用。
     */
    suspend fun getSubjectRecs(subjectId: Int, accessToken: String? = null): PagedDto<SubjectRecDto> =
        client.get("$NEXT_API_HOST/p1/subjects/$subjectId/recs") {
            pinSessionToken(accessToken)
            parameter("limit", SUBJECT_RECS_LIMIT)
        }.body()

    /** 收录该条目的目录。limit 上限 100。 */
    suspend fun getSubjectIndexes(
        subjectId: Int,
        limit: Int = 20,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SlimIndexDto> = client.get("$NEXT_API_HOST/p1/subjects/$subjectId/indexes") {
        pinSessionToken(accessToken)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /**
     * 条目收藏者（「谁在看」），新的在前。[type] 为收藏状态值（1 想看 2 看过 3 在看 4 搁置 5 抛弃），null 不筛。
     * limit 上限 100，**offset 上限 500**（契约）。
     *
     * 契约另有 `mode=friends`（只看好友），匿名实测看不出效果，没有接（2026-10-05 实测）。
     */
    suspend fun getSubjectCollects(
        subjectId: Int,
        type: Int? = null,
        limit: Int = 20,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<SubjectCollectDto> = client.get("$NEXT_API_HOST/p1/subjects/$subjectId/collects") {
        pinSessionToken(accessToken)
        parameter("type", type)
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    /**
     * 首页聚合（进度/时间线/小组话题/热门小组/热门条目讨论/每日放送）。
     *
     * **各区块独立计算，单块失败静默返回空数组且无失败标志**，HTTP 恒 200
     * （服务端 `routes/private/routes/home.ts` 每个区块都 `.catch()`）。
     * 因此**不能用 `progress.isEmpty()` 判定「用户没有在看条目」**，
     * 更不能据此清空本地缓存。
     *
     * **未登录时**服务端返回 200 但 `progress`/`timeline`/`groupTopics` 为空
     * （公开区块照常返回）。注意：未登录必须**完全省略 Authorization 头**，
     * 传空令牌会得到 401 `AUTHORIZATION_INVALID`。
     *
     * 响应很大（实测 663 KB、耗时 2.5-3.4s，
     * 2026-09-29 实测），调用方必须缓存且不得在首帧同步等待。
     */
    suspend fun getHome(accessToken: String? = null): HomeResponseDto =
        client.get("$NEXT_API_HOST/p1/home") { pinSessionToken(accessToken) }.body()

    /**
     * 好友的条目收藏动态。
     *
     * [subjectType] **必填**（缺了服务端 400 `querystring must have required property`）；
     * 匿名访问返回 401 `NEED_LOGIN`。返回的 `user` 含昵称与头像地址，属他人个人信息。
     */
    suspend fun getFriendsSubjectCollections(
        subjectType: Int,
        limit: Int = 10,
        offset: Int = 0,
        accessToken: String? = null,
    ): PagedDto<FriendSubjectCollectionActivityDto> =
        client.get("$NEXT_API_HOST/p1/me/friends/subject-collections") {
            pinSessionToken(accessToken)
            parameter("subjectType", subjectType)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    /**
     * 获取通知列表。
     *
     * [unread] 为 true 时仅获取未读通知，此时返回的 total 为用户的未读通知总数（对应 chiiUsers.user_new_notify）。
     * [limit] 上限 40。
     */
    suspend fun getNotifications(
        unread: Boolean? = null,
        limit: Int = 40,
        accessToken: String? = null,
    ): PagedDto<NoticeDto> = client.get("$NEXT_API_HOST/p1/notify") {
        pinSessionToken(accessToken)
        parameter("unread", unread)
        parameter("limit", limit)
    }.body()

    /**
     * 标记通知为已读。
     *
     * 不传 id（即 body 为 `{}`）时清空所有未读通知。
     */
    suspend fun clearNotifications(
        request: ClearNotifyRequestDto = ClearNotifyRequestDto(),
        accessToken: String,
    ): EmptyObjectDto = client.post("$NEXT_API_HOST/p1/clear-notify") {
        pinSessionToken(accessToken)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()
}

/** 条目推荐一次取的上限（契约最大值）。 */
private const val SUBJECT_RECS_LIMIT = 10
