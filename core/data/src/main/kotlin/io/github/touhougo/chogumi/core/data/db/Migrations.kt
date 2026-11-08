package io.github.touhougo.chogumi.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.touhougo.chogumi.core.data.db.explore.toSearchCriteriaOrNull
import io.github.touhougo.chogumi.core.data.db.explore.toShelfRows
import io.github.touhougo.chogumi.core.data.db.subject.EPISODES_STAMP
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.concreteOrNull
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.isPinnable
import io.github.touhougo.chogumi.core.model.pinned

/**
 * 3 → 4：
 * - 加 `fetch_stamps`（章节与子模块的取回时刻），原来的取回时刻就是正文行的 `updatedAt`，据此回填，
 *   旧缓存的新鲜期不变；
 * - 删掉 `episode_watch_states` 上 `(accountId, episodeId)` 的索引：它和主键完全重复，只多一棵要维护的树。
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_episode_watch_states_accountId_episodeId`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fetch_stamps` (`subjectId` INTEGER NOT NULL, `section` TEXT NOT NULL, " +
                "`viewerId` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`subjectId`, `section`, `viewerId`))",
        )
        db.execSQL(
            "INSERT INTO `fetch_stamps` (`subjectId`, `section`, `viewerId`, `fetchedAt`) " +
                "SELECT `subjectId`, '$EPISODES_STAMP', 0, MAX(`updatedAt`) FROM `episodes` GROUP BY `subjectId`",
        )
        db.execSQL(
            "INSERT INTO `fetch_stamps` (`subjectId`, `section`, `viewerId`, `fetchedAt`) " +
                "SELECT `subjectId`, `section`, `viewerId`, `updatedAt` FROM `subject_sections`",
        )
    }
}

/**
 * 4 → 5：
 * - `topic_reply_drafts`、`muted_groups` 按账号分：主键前加 `account_id`（换主键只能重建表），旧行归游客（0）：v4 的这两张表没有账号列，旧行无主，登录账号下不再显示，只在游客名下读得到；
 * - 加 `post_drafts`（发动态、小组新帖的草稿）；
 * - `search_presets.criteriaJson` 里标签条件的类型标记从全限定类名改成稳定的 `public` / `user`。
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `topic_reply_drafts_new` (`account_id` INTEGER NOT NULL, `topic_type` TEXT NOT NULL, " +
                "`topic_id` INTEGER NOT NULL, `post_id` INTEGER NOT NULL, `topic_title` TEXT NOT NULL, `reply_to` INTEGER NOT NULL, " +
                "`floor` TEXT, `quote_author` TEXT, `quote_excerpt` TEXT, `quote` TEXT, `text` TEXT NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `topic_type`, `topic_id`, `post_id`))",
        )
        db.execSQL(
            "INSERT INTO `topic_reply_drafts_new` SELECT 0, `topic_type`, `topic_id`, `post_id`, `topic_title`, `reply_to`, " +
                "`floor`, `quote_author`, `quote_excerpt`, `quote`, `text`, `updated_at` FROM `topic_reply_drafts`",
        )
        db.execSQL("DROP TABLE `topic_reply_drafts`")
        db.execSQL("ALTER TABLE `topic_reply_drafts_new` RENAME TO `topic_reply_drafts`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `muted_groups_new` (`account_id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `icon` TEXT, `members` INTEGER NOT NULL, `muted_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`account_id`, `name`))",
        )
        db.execSQL(
            "INSERT INTO `muted_groups_new` SELECT 0, `name`, `title`, `icon`, `members`, `muted_at` FROM `muted_groups`",
        )
        db.execSQL("DROP TABLE `muted_groups`")
        db.execSQL("ALTER TABLE `muted_groups_new` RENAME TO `muted_groups`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `post_drafts` (`account_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `text` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `kind`, `key`))",
        )

        // 下面两个全限定类名是 v4 库里已经存下的历史值，必须保持原样：包名或类名再变也不改这两个字面量。
        db.execSQL(
            "UPDATE `search_presets` SET `criteriaJson` = REPLACE(REPLACE(`criteriaJson`, " +
                "'\"com.aniflow.app.core.model.TagFilter.Public\"', '\"public\"'), " +
                "'\"com.aniflow.app.core.model.TagFilter.User\"', '\"user\"')",
        )
    }
}

/**
 * 5 → 6：
 * - 加 `explore_shelves`（探索页布局，一个分类一份，一行一条货架）；
 * - 「我的组合」（`search_presets`）的每一行变成它那个分类里的一条自建作品货架：有组合的分类按默认布局整份写进去，
 *   组合按固定先后放到最上面、新固定的在前（与原来探索页上的摆法一样）；与默认布局里某条货架条件相同的不另加。
 *   「全部」分类下固定的、条件读不懂的、放不上探索页的（[isPinnable]）组合本来就不在任何一页探索页上，不迁；
 * - 删掉 `search_presets`。
 */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `explore_shelves` (`subject_type` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                "`entry` TEXT NOT NULL, PRIMARY KEY(`subject_type`, `position`))",
        )
        val pins = db.query("SELECT `subjectType`, `criteriaJson`, `title` FROM `search_presets` ORDER BY `createdAt`, `key`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val type = SubjectType.from(cursor.getInt(0))?.concreteOrNull() ?: continue
                    val criteria = cursor.getString(1).toSearchCriteriaOrNull()?.takeIf { it.subjectType == type && it.isPinnable() }
                        ?: continue
                    add(Triple(type, criteria, cursor.getString(2)))
                }
            }
        }
        pins.groupBy({ it.first }, { it.second to it.third }).forEach { (type, typePins) ->
            val layout = typePins.fold(defaultShelfLayout(type)) { layout, (criteria, name) -> layout.pinned(type, criteria, name) }
            layout.toShelfRows(type).forEach { row ->
                db.execSQL(
                    "INSERT INTO `explore_shelves` (`subject_type`, `position`, `entry`) VALUES (?, ?, ?)",
                    arrayOf<Any>(row.subjectType, row.position, row.entry),
                )
            }
        }
        db.execSQL("DROP TABLE `search_presets`")
    }
}

/** 所有手写迁移，库的构建与迁移测试都用这一份：升版本时只在这里加一项，链式测试自动迁到 [DB_VERSION]。 */
internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
