package io.github.touhougo.chogumi.core.data.db.cache

import io.github.touhougo.chogumi.core.data.db.SQLITE_IN_CHUNK
import io.github.touhougo.chogumi.core.data.db.subject.LINEUP_SECTIONS
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSection
import io.github.touhougo.chogumi.core.data.repository.SyncPreferenceStore
import io.github.touhougo.chogumi.core.data.repository.TransactionRunner
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 启动时在后台清理一次本地缓存：条目表、条目详情子模块表、被删条目的章节、搜索第一页、用户资料，以及久不用的旧账号的分份数据。
 *
 * 这几张表没有别的淘汰途径：浏览他人收藏、搜索、排行都会把条目摘要写进来，每搜一组新条件就多存一份第一页，只增不减；
 * 登出或切走的账号，收藏、逐集状态与首页进度一直留着（切回来不必重拉），它们引用的条目也就一直不能删。
 * 「删谁」全部由 [cacheEvictionVictims] 与 [inactiveAccounts] 决定，这里只负责取数和按主键删除。
 *
 * 与 NSFW 同步器一样是「注册即生效」的副作用组件，必须列进 DataModule 里的 `BackgroundStartup`。
 * 清理失败只放弃这一次（下次启动再来），不影响任何页面。
 */
class LocalCacheJanitor(
    private val dao: CacheEvictionDao,
    private val transactions: TransactionRunner,
    private val clock: AppClock,
    /** 当前登录的账号：它的数据永远不清。 */
    private val tokenStore: TokenStore,
    /** 每个账号上次全量同步的时刻：判断旧账号多久没在本机用过。 */
    private val syncPreferenceStore: SyncPreferenceStore,
    scope: CoroutineScope,
) {
    init {
        // 清理是尽力而为：失败留到下次启动，绝不向外抛。
        scope.launch { outcomeOf { evict() } }
    }

    internal suspend fun evict() {
        val now = clock.nowMillis()
        // 偏好与会话不在数据库里，先在事务外读好
        val current = tokenStore.session().takeIf { it.authenticated }?.userId
        val synced = dao.accountsWithData().associateWith { syncPreferenceStore.lastSyncedAt(it) ?: 0L }
        transactions.inTransaction {
            val touched = dao.homeProgressTouches().associate { it.accountId to it.updatedAt }
            val inactive = inactiveAccounts(
                lastActiveAt = synced.mapValues { (accountId, at) -> maxOf(at, touched[accountId] ?: 0L) },
                currentAccountId = current,
                nowMillis = now,
                maxIdleMillis = ACCOUNT_MAX_IDLE_MILLIS,
            ).toList()
            // 先清旧账号：它们的收藏不再保护条目，下面这一轮就能把只被它们引用的条目清掉
            inactive.chunked(SQLITE_IN_CHUNK).forEach { ids ->
                dao.deleteCollectionsOf(ids)
                dao.deleteWatchStatesOf(ids)
                dao.deleteHomeProgressOf(ids)
                dao.deleteHomeProgressMetaOf(ids)
            }
            val referenced = dao.referencedSubjectIds().toSet()
            val subjects = cacheEvictionVictims(
                rows = dao.subjectAges().map { CachedRowAge(it.id, it.touchedAt) },
                isProtected = { it in referenced },
                nowMillis = now,
                policy = SUBJECTS_POLICY,
            ).toList()
            subjects.chunked(SQLITE_IN_CHUNK).forEach { ids ->
                dao.deleteSubjects(ids)
                dao.deleteEpisodesOf(ids)
                dao.deleteSectionsOf(ids)
                dao.deleteFetchStampsOf(ids)
            }
            // 收藏里作品的阵容按收藏它的账号视角保护：生涯统计每次都读它们，删了下次同步就要逐部重拉
            val collected = dao.collectedSubjects().mapTo(HashSet()) { it.subjectId to it.accountId }
            val lineupSections = LINEUP_SECTIONS.mapTo(HashSet()) { it.name }
            cacheEvictionVictims(
                rows = dao.sectionAges().map { CachedRowAge(it, it.updatedAt) },
                isProtected = { it.section in lineupSections && (it.subjectId to it.viewerId) in collected },
                nowMillis = now,
                policy = SECTIONS_POLICY,
            ).forEach {
                dao.deleteSection(it.subjectId, it.section, it.viewerId)
                dao.deleteFetchStamp(it.subjectId, it.section, it.viewerId)
            }
            cacheEvictionVictims(
                rows = dao.searchFirstPageAges().map { CachedRowAge(it.cacheKey, it.updatedAt) },
                isProtected = { false },
                nowMillis = now,
                policy = SEARCH_FIRST_PAGES_POLICY,
            ).toList().chunked(SQLITE_IN_CHUNK).forEach { dao.deleteSearchFirstPages(it) }
            cacheEvictionVictims(
                rows = dao.userAges().map { CachedRowAge(it.id, it.updatedAt) },
                isProtected = { false },
                nowMillis = now,
                policy = USERS_POLICY,
            ).toList().chunked(SQLITE_IN_CHUNK).forEach { dao.deleteUsers(it) }
        }
    }

    companion object {
        /**
         * 条目：30 天没被用到即过期，不受保护的最多留 3000 条（一条约数 KB，合计约十几 MB 以内）。
         * **设计决定（not in M3）**。
         */
        val SUBJECTS_POLICY = CacheEvictionPolicy(maxAgeMillis = TimeUnit.DAYS.toMillis(30), maxRows = 3000)

        /**
         * 子模块：同样 30 天；每个条目每个查看者至多 [SubjectSection] 那么多个模块，上限按条目上限乘模块数
         * （游客与各账号看同一条目各占一份，少见，不再乘）。收藏作品的阵容受保护，不占名额。**设计决定（not in M3）**。
         */
        val SECTIONS_POLICY = CacheEvictionPolicy(maxAgeMillis = TimeUnit.DAYS.toMillis(30), maxRows = SubjectSection.entries.size * 3000)

        /**
         * 搜索第一页：一行几百字节，新鲜期只有半小时，过期后只为下次先出旧结果；7 天没再取过即删，最多留 200 组条件。
         * **设计决定（not in M3）**。
         */
        val SEARCH_FIRST_PAGES_POLICY = CacheEvictionPolicy(maxAgeMillis = TimeUnit.DAYS.toMillis(7), maxRows = 200)

        /** 用户资料（看过的人）：新鲜期 10 分钟，30 天没再看过即删，最多留 1000 人。**设计决定（not in M3）**。 */
        val USERS_POLICY = CacheEvictionPolicy(maxAgeMillis = TimeUnit.DAYS.toMillis(30), maxRows = 1000)

        /** 旧账号 90 天没在本机用过（同步或首页刷新），清掉它的收藏、逐集状态与首页进度。**设计决定（not in M3）**。 */
        val ACCOUNT_MAX_IDLE_MILLIS = TimeUnit.DAYS.toMillis(90)
    }
}
