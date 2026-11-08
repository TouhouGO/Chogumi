package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toCommentThreadBoList
import io.github.touhougo.chogumi.core.data.db.mapper.toDomain
import io.github.touhougo.chogumi.core.data.db.mapper.toEntity
import io.github.touhougo.chogumi.core.data.db.mapper.toFullBo
import io.github.touhougo.chogumi.core.data.db.mapper.toTopicSubjectBo
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.data.util.SharedFetch
import io.github.touhougo.chogumi.core.data.util.cachedNetworkCall
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.onWebIfServerError
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.BlogDetailsBo
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.ProfileChange
import io.github.touhougo.chogumi.core.model.withProfileOf
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.ReportTarget
import io.github.touhougo.chogumi.core.model.SlimBlogBo
import io.github.touhougo.chogumi.core.model.TopicSubjectBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.model.p1.CreateReportDto
import io.github.touhougo.chogumi.core.network.model.p1.PagedDto
import io.github.touhougo.chogumi.core.network.model.v0.UserDto
import io.github.touhougo.chogumi.core.network.model.web.ProfileForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update

/** 用户数据仓库（先出缓存、再用网络结果水合） */
class UserRepository(
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
    private val userDao: UserDao,
    private val clock: AppClock,
    private val webSessions: WebSessionRepository,
    private val friends: FriendsRepository,
    private val blocked: BlockedUsersSource = BlockedUsersSource.NONE,
    private val accountMe: AccountMe,
) : UserModeration {

    /**
     * 当前登录用户的完整资料。
     *
     * 两个 host 都要取，缺一不可：
     * - v0 `/v0/me` 给出基础字段，且需要凭据；
     * - p1 `/p1/users/{username}` 给出 `site`/`location`/`bio`/`joinedAt`/`stats`。
     *
     * 两者怎么合并见 [withProfile]。
     */
    suspend fun getMe(refresh: Boolean = false): Flow<Load<UserBo>> {
        val session = tokenStore.session()
        if (!session.authenticated) return flow { emit(Load.Failed(Failure.NotLoggedIn)) }
        // 必须经 tokenFor 取凭据：/v0/me 需要鉴权，而共享客户端的 loadTokens 恒为 null
        // （防串号，见 NetworkModule），没有任何一方会自动补头。不带凭据发出的请求 → 服务端 401
        // `need Login` → 被映射成"登录状态已失效"，已登录用户点「我的」就会看到登录失效。
        // 凭据在取网络时才解析，失败会被 cachedNetworkCall 转成 Load.Failed，不会把异常抛给上层 collect。
        return cachedUser(session, readCache = { userDao.get(session.userId!!) }, refresh = refresh) {
            tokenStore.withSessionToken(session) { _, token -> withProfile(session.username!!, token) { accountMe.get(session) } }
        }
    }

    /** 按用户名查看他人资料。公开端点匿名可读；已登录时带上凭据以获得 `isFriend`。 */
    suspend fun getUser(username: String, refresh: Boolean = false): Flow<Load<UserBo>> {
        val session = tokenStore.session()
        return cachedUser(session, readCache = { userDao.getByUsername(username) }, refresh = refresh) {
            tokenStore.withSessionToken(session) { _, token ->
                userFetches.fetch(username to token) { withProfile(username, token) { userApi.getUser(username, token) } }
            }
        }
    }

    /** 他人资料在途的那一次请求（按用户名与令牌）：他人主页与它的生涯同时打开时共用一次。 */
    private val userFetches = SharedFetch<Pair<String, String?>, UserBo>()

    /** 用户资料的缓存优先读取：本人与他人同一套缓存、写回与过期规则，只差读哪一行、怎么取网络。 */
    private fun cachedUser(
        session: AccountSession,
        readCache: suspend () -> UserEntity?,
        refresh: Boolean,
        fetch: suspend () -> UserBo,
    ): Flow<Load<UserBo>> = flow {
        cachedNetworkCall(
            readCache = { tokenStore.requireCurrent(session); readCache() },
            mapCache = { tokenStore.requireCurrent(session); it.toBo() },
            fetchNetwork = fetch,
            writeCache = { user -> tokenStore.commitForSession(session) { userDao.upsert(user.toEntity(updatedAt = clock.nowMillis())) } },
            refresh = refresh,
            ttlMillis = CacheTtl.USER_PROFILE,
            cachedAt = { it.updatedAt },
            clock = clock,
        ).collect { load ->
            tokenStore.requireCurrent(session)
            emit(load)
        }
    }.failuresAsLoad()

    /**
     * v0 基础资料、p1 资料（统计、简介、站点）与目录两页的条数并行取后合并。v0 是必需项（失败则整次失败）；
     * 后两者是增强项，取不到就只用 v0 的基础资料，不让统计的网络抖动整页报错。
     */
    private suspend fun withProfile(username: String, token: String?, basic: suspend () -> UserDto): UserBo = coroutineScope {
        val base = async { basic() }
        val profile = async { outcomeOf { userApi.getP1User(username, token).toFullBo() }.valueOrNull() }
        val indexes = async { outcomeOf { indexCountsOf(username, token) }.valueOrNull() }
        val friends = async { outcomeOf { userApi.getFriends(username, limit = 1, offset = 0, accessToken = token).total }.valueOrNull() }
        base.await().toDomain().mergeProfile(profile.await(), indexes.await(), friends.await())
    }

    /**
     * 目录两页（创建的、收藏的）各有多少条：就是目录页两个列表端点自己的 `total`（各取 1 条），带同一个令牌，
     * 自己看自己时私密目录也算在内，与目录页列表数的是同一批。资料 `stats.index` 不用：它与列表的计数口径不同，
     * 不可互相替代。好友数同理，是好友 tab 列表端点的 `total`：资料 `stats.friend` 是服务端缓存的计数，加、取消好友之后
     * 要过一阵子才变（2026-11-07 实测：取消一个好友后列表 `total` 当即是 5，`stats.friend` 还是 6）。
     */
    private suspend fun indexCountsOf(username: String, token: String?): IndexCounts = coroutineScope {
        val created = async { userApi.getUserIndexes(username, limit = 1, accessToken = token).total }
        val collected = async { userApi.getIndexCollections(username, limit = 1, accessToken = token).total }
        IndexCounts(created = created.await(), collected = collected.await())
    }

    // ---- 个人主页的分组内容 ----
    // 这些是**分页**列表，用一次性 suspend 而不是 SWR 流：它们没有本地缓存表，
    // 也不需要"先给缓存再刷新"的语义，做成流只会凭空多出一层状态。

    /**
     * 取**一页**好友（= 该用户关注的人）。
     *
     * 注意这不是"互相关注"——端点名叫 friends，但服务端只查 `uid = 该用户`
     * 一个方向。语义与陷阱详见 [UserApi.getFriends]。
     *
     * 返回 [PagedResult] 而非裸 List：**一页取不完是常态**。关注者可以远超一页，而契约把
     * `limit` 上限钉死在 100（`maximum: 100`）。
     * 只取一页就停的话，界面会**静默只显示前 100 条**——没有错误、没有"还有更多"，
     * 用户以为就这么多。分页是必需项，不是优化。
     */
    suspend fun getFriendsPage(
        username: String,
        offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE,
    ): Outcome<PagedResult<UserBo>> = fetchPublicPage(
        offset = offset, limit = limit,
        fetch = { token -> userApi.getFriends(username, limit, offset, token) },
        transform = { it.toBo() },
    )

    /**
     * 取**一页**关注者。与 [getFriendsPage] 是同一张好友表的相反方向，两者都不可省：
     * 只被关注、未关注他人的账号会出现 followers 非空而 friends 为空。
     */
    suspend fun getFollowersPage(
        username: String,
        offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE,
    ): Outcome<PagedResult<UserBo>> = fetchPublicPage(
        offset = offset, limit = limit,
        fetch = { token -> userApi.getFollowers(username, limit, offset, token) },
        transform = { it.toBo() },
    )

    /** 取**一页**角色收藏。 */
    suspend fun getCharacterCollectionsPage(
        username: String,
        offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE,
    ): Outcome<PagedResult<RelatedCharacterBo>> = fetchPublicPage(
        offset = offset, limit = limit,
        fetch = { token -> userApi.getCharacterCollections(username, limit, offset, token) },
        transform = { it.toBo() },
    )

    /** 取**一页**人物收藏。 */
    suspend fun getPersonCollectionsPage(
        username: String,
        offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE,
    ): Outcome<PagedResult<RelatedPersonBo>> = fetchPublicPage(
        offset = offset, limit = limit,
        fetch = { token -> userApi.getPersonCollections(username, limit, offset, token) },
        transform = { it.toBo() },
    )

    /** 取**一页**目录收藏。 */
    suspend fun getIndexCollectionsPage(
        username: String,
        offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE,
    ): Outcome<PagedResult<IndexBo>> = fetchPublicPage(
        offset = offset, limit = limit,
        fetch = { token -> userApi.getIndexCollections(username, limit, offset, token) },
        transform = { it.toBo() },
    )

    // ---- 社交写操作----
    //
    // 与上面的公开分页不同，这些**必须**已登录：方法内部自己取凭据，
    // 未登录时直接返回失败（需要登录）而不是发裸请求。

    /**
     * 关注（加好友）。服务端幂等：已是好友时重复调用不报错。做成后重读自己的好友名单（[FriendsRepository.confirmWrite]），
     * 名字旁的「好友」小牌当场带上；重读失败不算加好友失败，名单等下次过期再读。
     */
    override suspend fun addFriend(username: String): Outcome<Unit> = tokenStore.authenticatedOutcome { session, token ->
        outcomeOf { userApi.addFriend(username, token) }.also { added ->
            if (added is Outcome.Success) outcomeOf { friends.confirmWrite(session) }
        }
    }

    /**
     * 取消关注（取消好友）。接口在服务端恒 5xx，改用网页会话解除（[onWebIfServerError]）：网页按用户 id 解除，
     * 做完回读自己的好友名单（[FriendsRepository.confirmWrite]，同时记下新名单）确认对方已不在里面。不看对方资料上的 `isFriend`：
     * 那是服务端一小时的好友缓存，网页解除不清它，刚解除完还是 true；好友列表直接查库。接口做成时同样重读一次名单，
     * 名字旁的「好友」小牌当场撤下。
     */
    override suspend fun removeFriend(username: String): Outcome<Unit> = tokenStore.authenticatedOutcome { session, token ->
        var readBack = false
        outcomeOf { userApi.removeFriend(username, token) }.onWebIfServerError(RejectReason.UNFRIEND_UNAVAILABLE) {
            // 对方 id 在确认有网页会话之后才取：没有网页会话时原因就是「要账号密码登录」，不多发请求。
            var target: Int? = null
            webSessions.perform(session) { formhash ->
                BgmWeb.unfriend(userApi.getP1User(username, token).id.also { target = it }, formhash)
            }
            readBack = true
            if (friends.confirmWrite(session).includes(target)) {
                throw FailureException(Failure.Rejected(RejectReason.UNFRIEND_UNAVAILABLE))
            }
        }.also { removed ->
            if (removed is Outcome.Success && !readBack) outcomeOf { friends.confirmWrite(session) }
        }
    }

    /**
     * 当前账号是否已与 [userId] 绝交：读页面藏人用的同一份名单。本机存储读取失败是 [Failure.Storage]，不能伪装成已解除绝交。
     * 读取前后固定同一会话，游客为 false。
     */
    suspend fun isBlocked(userId: Int): Outcome<Boolean> = storageOutcomeOf {
        tokenStore.withSessionToken { session, _ -> blocked.of(session).hides(userId) }
    }

    /**
     * 当前账号是否绝交了 [userId]，跟着本机记下的名单变（[BlockedUsersSource.changes]）：在设置页或别的页绝交、取消后，
     * 停在返回栈里的主页也跟上。读不出本机名单时这条流结束：订阅方据此知道跟随断了，重读成功后再订阅一次；
     * 读取失败由 [isBlocked] 报给用户。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun blockedChanges(userId: Int): Flow<Boolean> = tokenStore.sessionFlow
        .flatMapLatest { session -> blocked.changes(session).map { it.hides(userId) }.storageAsLoad() }
        .transformWhile { load ->
            load.valueOrNull()?.let { emit(it) }
            load !is Load.Failed
        }
        .distinctUntilChanged()

    /**
     * 当前账号是否关注了 [userId]，跟着自己的好友名单变（[FriendsRepository.changes]，名字旁「好友」小牌的同一来源）：
     * 在别的页、网页端加或取消后，停在返回栈里的主页也跟上。不读对方资料上的 `isFriend`：那是服务端一小时的好友缓存，
     * 网页解除不清它，刚取消完再刷新资料还是 true。
     */
    fun friendChanges(userId: Int): Flow<Boolean> = friendsChanges().map { it.includes(userId) }.distinctUntilChanged()

    // ---- 改自己的资料 ----

    private val profileEditCount = MutableStateFlow(0)

    /** 每改成一次自己的资料加一：自己的主页、设置里的资料页观察它重读，不等下拉刷新。 */
    val profileEdits: StateFlow<Int> = profileEditCount.asStateFlow()

    /**
     * 当前账号能不能在 App 里改资料：开放接口没有改资料的写接口，只能用网页会话提交网页的个人设置（guide §1.7），
     * 所以只看本地有没有存着网页会话（[WebSessionRepository.available]）。未登录是 false。
     */
    suspend fun canEditProfile(): Boolean {
        val session = tokenStore.session()
        return session.authenticated && webSessions.available(session)
    }

    /**
     * 自己能改的四项（头像、昵称、签名、自我介绍）现在的值：只来自自己的 p1 资料，与 [editProfile] 回读确认的是同一份。
     * 不经 [getMe]：那里 p1 取不到时退回 v0 的基础资料，自我介绍是空的，拿它开面板，用户一保存就盖掉网页上的自我介绍。
     * p1 取不到就是失败。
     */
    suspend fun getEditableProfile(): Outcome<UserBo> = tokenStore.authenticatedOutcome { session, token ->
        outcomeOf { ownProfileOf(session.username!!, token) }
    }

    /** 自己的 p1 资料（[getEditableProfile] 与 [editProfile] 的回读读的都是它）。 */
    private suspend fun ownProfileOf(username: String, token: String?): UserBo = userApi.getP1User(username, token).toFullBo()

    /**
     * 改自己资料的一项（[ProfileChange]，一次一项）：网页会话现读个人设置页、只换这一项提交（[WebSessionRepository.editProfile]），
     * 再回读自己的 p1 资料，这一项是改后的样子才算做成（[ProfileChange.landedIn]）；头像比的是提交前后的地址。
     * 回读仍是旧的就是「去网页端改」（[RejectReason.PRIVACY_SETTING_UNAVAILABLE]）；没有网页会话是「要账号密码登录」。
     * 做成后回回读到的资料（页面直接用它，不等重读），[profileEdits] 加一。
     */
    suspend fun editProfile(change: ProfileChange): Outcome<UserBo> = tokenStore.authenticatedOutcome { session, token ->
        val username = session.username!!
        val normalized = change.normalized()
        outcomeOf {
            val avatarBefore = if (normalized is ProfileChange.Avatar) userApi.getP1User(username, token).avatar.large else null
            webSessions.editProfile(session, normalized.toForm())
            val after = tokenStore.withSessionToken(session) { _, readToken -> ownProfileOf(username, readToken) }
            if (!normalized.landedIn(after, avatarBefore)) throw FailureException(Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE))
            after
        }.also { if (it is Outcome.Success) profileEditCount.update { n -> n + 1 } }
    }

    /**
     * 当前账号的整份好友名单（[FriendsRepository.changes]）：自己的主页据此在别处加、取消好友之后重读「好友 N」与「我关注的」。
     */
    fun friendsChanges(): Flow<Friends> = friends.changes()

    /**
     * 绝交。接口在服务端恒 5xx（与 `PATCH /p1/privacy` 写同一张用户字段表），改用网页会话提交隐私设置页的绝交表单
     * （[onWebIfServerError]），做完回读绝交名单确认对方已在里面。
     */
    override suspend fun addUserToBlocklist(username: String): Outcome<Unit> = blocklistWrite(username, blocking = true) { token ->
        userApi.addUserToBlocklist(username, token).blocklist
    }

    /** 取消绝交。同绝交：接口 5xx 时用网页会话点隐私设置页上对方那一行的取消链接，回读名单确认对方已不在。 */
    suspend fun removeUserFromBlocklist(username: String): Outcome<Unit> = blocklistWrite(username, blocking = false) { token ->
        userApi.removeUserFromBlocklist(username, token).blocklist
    }

    /**
     * 绝交（[blocking] 为 true）或取消绝交 [username]：先走接口，5xx 时走网页，回读名单里对方在不在等于 [blocking] 才算做成。
     * 做成后记下服务端给的完整名单（[BlockedUsersSource.record]），订阅名单的页面当场藏起楼层，返回栈里的帖子、动态列表当场按新名单重算。
     */
    private suspend fun blocklistWrite(
        username: String,
        blocking: Boolean,
        request: suspend (token: String) -> List<Int>,
    ): Outcome<Unit> = tokenStore.authenticatedOutcome { session, token ->
        val written = outcomeOf { request(token) }.onWebIfServerError(RejectReason.BLOCK_UNAVAILABLE) {
            var target: Int? = null
            val targetId: suspend () -> Int = { userApi.getP1User(username, token).id.also { target = it } }
            if (blocking) {
                webSessions.block(session, targetId)
            } else {
                webSessions.unblock(session, targetId)
            }
            val blocklist = tokenStore.withSessionToken(session) { _, readToken -> userApi.getBlocklist(requireNotNull(readToken)).blocklist }
            if ((requireNotNull(target) in blocklist) != blocking) throw FailureException(Failure.Rejected(RejectReason.BLOCK_UNAVAILABLE))
            blocklist
        }
        when (written) {
            is Outcome.Success -> outcomeOf { blocked.record(session, written.value) }
            is Outcome.Failed -> written
        }
    }

    /** 报告用户或一条内容。同一对象重复报告服务端回 409（[Failure.Http]）；成功带服务端致谢文案。 */
    override suspend fun report(target: ReportTarget, reason: Int, comment: String?): Outcome<String> = socialAction { token ->
        userApi.createReport(CreateReportDto(type = target.type.value, id = target.id, value = reason, comment = comment), token).message
    }

    // ---- 日志 / 目录只读页----
    //
    // 列表走 [fetchPublicPage]（公开端点、游标语义同前）；详情是一次性读取。

    /** 某用户创建的日志，一页。 */
    suspend fun getUserBlogsPage(
        username: String,
        offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE,
    ): Outcome<PagedResult<SlimBlogBo>> = fetchPublicPage(
        offset = offset, limit = limit,
        fetch = { token -> userApi.getUserBlogs(username, limit, offset, token) },
        transform = { it.toBo() },
    )

    /**
     * 某用户创建的目录，一页（目录页「创建的」）。
     *
     * 这个端点每项不带 `user`（实测 `/p1/users/{username}/indexes`，「收藏的」端点带），创建者就是 [username] 本人：
     * 用本地缓存的资料补上（从个人页进来时已缓存，不另发请求），目录行与作品社区的目录行一样带创建者头像与名字。
     */
    suspend fun getUserIndexesPage(
        username: String,
        offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE,
    ): Outcome<PagedResult<IndexBo>> {
        // 创建者只是行上的装饰：读不出本地缓存就不补，这一页照常
        val owner = outcomeOf { userDao.getByUsername(username) }.valueOrNull()?.toBo()
        return fetchPublicPage(
            offset = offset, limit = limit,
            fetch = { token -> userApi.getUserIndexes(username, limit, offset, token) },
            transform = { dto -> dto.toBo().let { if (it.creator == null) it.copy(creator = owner) else it } },
        )
    }

    /** 日志详情（BBCode 正文）。与同页的关联作品、留言一样带令牌：不公开的日志匿名可能取不到。 */
    suspend fun getBlogDetails(entryId: Int): Outcome<BlogDetailsBo> = outcomeOf {
        tokenStore.withSessionToken { session, token -> userApi.getBlogEntry(entryId, token).toBo(session.userId) }
    }

    /** 日志关联的作品（日志页眉题）。 */
    suspend fun getBlogSubjects(entryId: Int): Outcome<List<TopicSubjectBo>> = outcomeOf {
        tokenStore.withSessionToken { _, token -> userApi.getBlogSubjects(entryId, token).map { it.toTopicSubjectBo() } }
    }

    /** 日志留言（楼层 + 楼中楼）。匿名可读，带令牌只为标出「我」的楼层。 */
    suspend fun getBlogComments(entryId: Int): Outcome<List<CommentThreadBo>> = outcomeOf {
        tokenStore.withSessionToken { session, token ->
            userApi.getBlogComments(entryId, token).toCommentThreadBoList(session.userId)
        }
    }

    /**
     * 社交写操作的统一包法（[authenticatedOutcome]）：未登录 → [Failure.NotLoggedIn]，不发请求；途中换号 →
     * [Failure.SessionChanged]；其余异常转成失败原因。
     */
    private suspend inline fun <R> socialAction(crossinline block: suspend (token: String) -> R): Outcome<R> =
        tokenStore.authenticatedOutcome { _, token -> outcomeOf { block(token) } }

    /**
     * 公开分页端点的统一取法。
     *
     * 游标由**请求的 offset + 响应里的 limit** 算出，不按返回条数推进：
     * 服务端先切 `chiiFriends`/`chiiPersonCollects` 的结果集，再跳过查不到的条目
     * （账号已注销），所以一页回不满、甚至整页为空都**不代表这是最后一页**。
     * 按返回条数推进会重复扫描被跳过的那一段，整页被跳过时游标还会原地不动。
     *
     * [transform] 允许对不关心的行返回 null，统一过滤。
     *
     * 凭据是**可选**的：这些端点匿名可读，带上令牌只是让服务端按 nsfw 偏好过滤。
     * 因此不像 [getMe] 那样要求已登录——未登录时按匿名请求发出去即可。
     */
    private suspend inline fun <T, R> fetchPublicPage(
        offset: Int,
        limit: Int,
        crossinline fetch: suspend (String?) -> PagedDto<T>,
        transform: (T) -> R?,
    ): Outcome<PagedResult<R>> = outcomeOf {
        val page = tokenStore.withSessionToken { _, token -> fetch(token) }
        page.toPagedResult(offset, limit, page.data.mapNotNull(transform))
    }

    private companion object {
        /**
         * 契约允许的最大页大小（`maximum: 100`，见 `tools/openapi/generated/p1.bundled.json`）。
         * 取满即可，不必保守——分页逻辑会按需续取。
         */
        const val MAX_PAGE_SIZE = 100
    }
}

/**
 * 用 p1 资料、目录条数与好友数补全 v0 的基础资料。
 *
 * 哪一份缺失（网络失败或匿名受限）就少哪几项，其余原样——宁可少几行统计，
 * 也不能让"我的"页因为一个可选端点而整体报错。昵称、签名、头像两份都有，取 p1 的：改资料之后回读确认的是 p1
 * （[UserRepository.editProfile]），页面画的与确认的是同一份。
 */
private fun UserBo.mergeProfile(profile: UserBo?, indexes: IndexCounts?, friendCount: Int?): UserBo {
    val withIndexes = indexes?.let { copy(indexCreateCount = it.created, indexCollectCount = it.collected) } ?: this
    val withCounts = friendCount?.let { withIndexes.copy(friendCount = it) } ?: withIndexes
    if (profile == null) return withCounts
    return withCounts.withProfileOf(profile).copy(
        site = profile.site,
        location = profile.location,
        joinedAt = profile.joinedAt,
        subjectStats = profile.subjectStats,
        characterCount = profile.characterCount,
        personCount = profile.personCount,
        blogCount = profile.blogCount,
        groupCount = profile.groupCount,
    )
}

/** 目录两页列表端点的 `total`（[UserRepository] 取资料时一并取）。 */
private data class IndexCounts(val created: Int, val collected: Int)

/** 要改的一项 → 网页个人设置表单里要换的那一项（其余为 null，按页面上现在的值提交）。 */
private fun ProfileChange.toForm(): ProfileForm = when (this) {
    is ProfileChange.Nickname -> ProfileForm(nickname = value)
    is ProfileChange.Sign -> ProfileForm(sign = value)
    is ProfileChange.Bio -> ProfileForm(bio = value)
    is ProfileChange.Avatar -> ProfileForm(avatarJpeg = jpeg)
}
