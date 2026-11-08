package io.github.touhougo.chogumi.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfEntity
import io.github.touhougo.chogumi.core.data.db.explore.toShelfEntryOrNull
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.WorkShelfEntry
import io.github.touhougo.chogumi.core.model.WorkShelfSource
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.pickWord
import io.github.touhougo.chogumi.core.model.shelfPresetOf
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 升级安装不能丢数据：从导出的旧版表结构（schemas/）起一个库、灌上数据，迁到当前版本，Room 校验表结构，
 * 再看数据与新表的回填。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChogumiDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ChogumiDatabase::class.java)

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> =
        query(sql).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Test
    fun `3到4用正文行的写入时刻回填取回时刻，数据原样保留`() {
        helper.createDatabase(TEST_DB, 3).apply {
            // 条目 8 的两集写入时刻不同：取回时刻取较晚的；条目 9 一集
            execSQL("INSERT INTO episodes (id, subjectId, type, name, nameCn, sort, airdate, comment, desc, updatedAt) VALUES (1, 8, 0, 'a', '甲', 1.0, '', 0, '', 1000)")
            execSQL("INSERT INTO episodes (id, subjectId, type, name, nameCn, sort, airdate, comment, desc, updatedAt) VALUES (2, 8, 0, 'b', '乙', 2.0, '', 0, '', 3000)")
            execSQL("INSERT INTO episodes (id, subjectId, type, name, nameCn, sort, airdate, comment, desc, updatedAt) VALUES (3, 9, 0, 'c', '丙', 1.0, '', 0, '', 2000)")
            execSQL("INSERT INTO subject_sections (subjectId, section, viewerId, json, updatedAt) VALUES (8, 'CHARACTERS', 7, '[]', 4000)")
            execSQL("INSERT INTO subject_sections (subjectId, section, viewerId, json, updatedAt) VALUES (8, 'PERSONS', 0, '[]', 5000)")
            execSQL("INSERT INTO episode_watch_states (episodeId, subjectId, accountId, status, updatedAt) VALUES (1, 8, 7, 2, 6000)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        assertEquals(3, db.query("SELECT COUNT(*) FROM episodes").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(2, db.query("SELECT COUNT(*) FROM subject_sections").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, db.query("SELECT COUNT(*) FROM episode_watch_states").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(
            listOf("8|CHARACTERS|7|4000", "8|EPISODES|0|3000", "8|PERSONS|0|5000", "9|EPISODES|0|2000"),
            db.strings("SELECT subjectId || '|' || section || '|' || viewerId || '|' || fetchedAt FROM fetch_stamps ORDER BY subjectId, section"),
        )
    }

    @Test
    fun `3到4删掉逐集观看状态上与主键重复的索引，其余索引还在`() {
        helper.createDatabase(TEST_DB, 3).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        val indices = db.strings("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'episode_watch_states'")
        assertTrue("index_episode_watch_states_accountId_episodeId" !in indices, "重复的索引还在：$indices")
        assertTrue("index_episode_watch_states_accountId_subjectId" in indices, "按条目取的索引不该被删：$indices")
    }

    @Test
    fun `4到5草稿与屏蔽的旧行归游客，新帖草稿表建好`() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL("INSERT INTO topic_reply_drafts (topic_type, topic_id, post_id, topic_title, reply_to, floor, quote_author, quote_excerpt, quote, text, updated_at) VALUES ('GROUP', 3, 0, '标题', 0, NULL, NULL, NULL, NULL, '正文', 42)")
            execSQL("INSERT INTO muted_groups (name, title, icon, members, muted_at) VALUES ('a', 'A', NULL, 5, 77)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        assertEquals(
            listOf("0|GROUP|3|0|正文|42"),
            db.strings("SELECT account_id || '|' || topic_type || '|' || topic_id || '|' || post_id || '|' || text || '|' || updated_at FROM topic_reply_drafts"),
        )
        assertEquals(listOf("0|a|A|77"), db.strings("SELECT account_id || '|' || name || '|' || title || '|' || muted_at FROM muted_groups"))
        assertEquals(0, db.query("SELECT COUNT(*) FROM post_drafts").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun `4到5组合条件里的标签类型标记换成稳定名字，迁完仍读得出`() {
        val legacy = """{"filters":{"tags":[{"type":"com.aniflow.app.core.model.TagFilter.Public","value":"科幻"},""" +
            """{"type":"com.aniflow.app.core.model.TagFilter.User","value":"妹控"}]}}"""
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL("INSERT INTO search_presets (`key`, subjectType, criteriaJson, title, createdAt) VALUES ('k', 2, '${legacy}', '组合', 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        val migrated = db.strings("SELECT criteriaJson FROM search_presets").single()
        assertTrue("com.aniflow" !in migrated, "旧类名还在：$migrated")
        val tags = json.decodeFromString(SearchCriteria.serializer(), migrated).filters.tags
        assertEquals(listOf("科幻", "妹控"), tags.map { it.value })
    }

    @Test
    fun `5到6组合变成自建作品货架_新固定的在前_删旧表`() {
        val older = SearchCriteria(subjectType = SubjectType.ANIME).pickWord("奇幻", WordPick.ADD).pickWord("百合", WordPick.ADD)
        val newer = SearchCriteria(subjectType = SubjectType.ANIME).pickWord("校园", WordPick.ADD).pickWord("百合", WordPick.ADD)
        // 与默认布局里「奇幻」预设条件相同：已经在探索页上，不另加
        val asPreset = checkNotNull(shelfPresetOf(SubjectType.ANIME, "奇幻")).criteria
        // 「全部」分类下固定的：没有探索页，不迁
        val allTypes = older.copy(subjectType = null)
        helper.createDatabase(TEST_DB, 5).apply {
            insertPreset("a", 2, older, "旧的", createdAt = 1)
            insertPreset("b", 2, newer, "新的", createdAt = 3)
            insertPreset("c", 2, asPreset, "同预设", createdAt = 2)
            insertPreset("d", 0, allTypes, "全部", createdAt = 4)
            execSQL("INSERT INTO search_presets (`key`, subjectType, criteriaJson, title, createdAt) VALUES ('e', 2, '读不懂', '坏行', 5)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        val tables = db.strings("SELECT name FROM sqlite_master WHERE type = 'table'")
        assertTrue("search_presets" !in tables, "旧表还在：$tables")
        val rows = db.query("SELECT subject_type, position, entry FROM explore_shelves ORDER BY subject_type, position").use { cursor ->
            buildList { while (cursor.moveToNext()) add(ExploreShelfEntity(cursor.getInt(0), cursor.getInt(1), cursor.getString(2))) }
        }
        assertTrue(rows.all { it.subjectType == SubjectType.ANIME.value }, "只有有组合的分类写了布局")
        val layout = rows.map { checkNotNull(it.toShelfEntryOrNull()) { "迁出的行读不懂：${it.entry}" } }
        assertEquals(
            listOf(
                WorkShelfEntry("custom:2", WorkShelfSource.Custom(newer, "新的"), WorkShelfStyle.POSTER_ROW),
                WorkShelfEntry("custom:1", WorkShelfSource.Custom(older, "旧的"), WorkShelfStyle.POSTER_ROW),
            ) + defaultShelfLayout(SubjectType.ANIME),
            layout,
        )
    }

    @Test
    fun `从第一版起逐版迁到当前版本`() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, DB_VERSION, true, *ALL_MIGRATIONS)

        val tables = db.strings("SELECT name FROM sqlite_master WHERE type = 'table'")
        assertTrue(listOf("topic_reply_drafts", "search_first_pages", "fetch_stamps", "post_drafts", "explore_shelves", "airing_times", "airing_times_meta").all { it in tables }, "逐版迁完少了表：$tables")
    }

    private fun SupportSQLiteDatabase.insertPreset(key: String, subjectType: Int, criteria: SearchCriteria, title: String, createdAt: Long) =
        execSQL(
            "INSERT INTO search_presets (`key`, subjectType, criteriaJson, title, createdAt) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any>(key, subjectType, json.encodeToString(SearchCriteria.serializer(), criteria), title, createdAt),
        )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val TEST_DB = "migration-test"
    }
}
