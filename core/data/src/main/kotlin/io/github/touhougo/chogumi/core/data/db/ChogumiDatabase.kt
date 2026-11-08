package io.github.touhougo.chogumi.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeDao
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeEntity
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeMetaEntity
import io.github.touhougo.chogumi.core.data.db.cache.CacheEvictionDao
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarDao
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionDao
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupDao
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupEntity
import io.github.touhougo.chogumi.core.data.db.community.PostDraftDao
import io.github.touhougo.chogumi.core.data.db.community.PostDraftEntity
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftDao
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfDao
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfEntity
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressDao
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressEntity
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressMetaEntity
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchDao
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchEntity
import io.github.touhougo.chogumi.core.data.db.search.SearchFirstPageDao
import io.github.touhougo.chogumi.core.data.db.search.SearchFirstPageEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.FetchStampEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionEntity
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogDao
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogEntity
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogMetaEntity
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity

/** 库的版本，唯一写处；迁移测试迁到它，漏写迁移时链式用例会红。 */
internal const val DB_VERSION = 7

@Database(
    entities = [
        UserEntity::class,
        RecentSearchEntity::class,
        SubjectEntity::class,
        EpisodeEntity::class,
        UserCollectionEntity::class,
        CalendarEntity::class,
        EpisodeWatchStateEntity::class,
        HomeProgressEntity::class,
        HomeProgressMetaEntity::class,
        TagCatalogEntity::class,
        TagCatalogMetaEntity::class,
        ExploreShelfEntity::class,
        SubjectSectionEntity::class,
        TopicReplyDraftEntity::class,
        MutedGroupEntity::class,
        PostDraftEntity::class,
        SearchFirstPageEntity::class,
        FetchStampEntity::class,
        AiringTimeEntity::class,
        AiringTimeMetaEntity::class,
    ],
    // 升版本时同一次改动写迁移，schemas/ 里导出的上一版结构是迁移的基线：只加表、加列时用自动迁移（[autoMigrations]），
    // 改名、删列这类要写规格或手写迁移（加进 [ALL_MIGRATIONS]）。
    // 手写迁移各有一条带数据的用例（ChogumiDatabaseMigrationTest：从上一版的导出结构起库、灌数据、迁移、让 Room 校验）；
    // 自动迁移由从第一版起逐版迁到 [DB_VERSION] 的那条用例覆盖。
    // 2：加话题回复草稿（topic_reply_drafts）与屏蔽的小组（muted_groups）两张表。
    // 3：加搜索第一页（search_first_pages）一张表。
    // 4：加取回时刻（fetch_stamps）一张表并从正文表回填；删掉逐集观看状态上与主键重复的索引（手写迁移 [MIGRATION_3_4]）。
    // 5：话题回复草稿、屏蔽的小组按账号分（主键加 account_id，旧行归游客）；加新帖草稿（post_drafts）；
    //    「我的组合」里标签条件的判别名改成固定的 public / user（手写迁移 [MIGRATION_4_5]）。
    // 6：加探索页布局（explore_shelves），「我的组合」（search_presets）并进去成自建作品货架后删表（手写迁移 [MIGRATION_5_6]）。
    // 7：加放送时刻表（airing_times）与它的刷新记录（airing_times_meta）两张表。
    version = DB_VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 6, to = 7)],
)
abstract class ChogumiDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun subjectDao(): SubjectDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun userCollectionDao(): UserCollectionDao
    abstract fun calendarDao(): CalendarDao
    abstract fun episodeWatchStateDao(): EpisodeWatchStateDao
    abstract fun homeProgressDao(): HomeProgressDao
    abstract fun tagCatalogDao(): TagCatalogDao
    abstract fun exploreShelfDao(): ExploreShelfDao
    abstract fun subjectSectionDao(): SubjectSectionDao
    abstract fun cacheEvictionDao(): CacheEvictionDao
    abstract fun topicReplyDraftDao(): TopicReplyDraftDao
    abstract fun mutedGroupDao(): MutedGroupDao
    abstract fun postDraftDao(): PostDraftDao
    abstract fun searchFirstPageDao(): SearchFirstPageDao
    abstract fun airingTimeDao(): AiringTimeDao
}
