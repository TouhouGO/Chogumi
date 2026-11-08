package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * chogumi_preferences DataStore 里各偏好键的**单一真源**。
 *
 * 集中声明避免键名在多处漂移。读写方：[PreferencesRepository]、按账号的同步时刻（`DataStoreSyncPreferenceStore`）、
 * 置顶小组（`GroupRepository`）、NSFW 偏好缓存（`NsfwPreferenceRepository`）、绝交名单缓存（`BlocklistRepository`）、好友名单缓存（`FriendsRepository`）。
 */
object PreferenceKeys {
    val THEME_MODE = stringPreferencesKey("pref_theme_mode")
    val COLOR_MODE = stringPreferencesKey("pref_color_mode")
    val COLOR_SEED = longPreferencesKey("pref_color_seed")

    /** 选中的配色方案：见 `ThemePreset`，只在配色来源是方案时起作用。 */
    val COLOR_PRESET = stringPreferencesKey("pref_color_preset")

    /** 体验模式（完整 / 专注）：见 `ExperienceMode`，缺省即完整模式。 */
    val EXPERIENCE_MODE = stringPreferencesKey("pref_experience_mode")

    /** 是否显示公共评分（[PreferencesRepository.showPublicRatings]）：默认 true。与 [EXPERIENCE_MODE] 正交。 */
    val SHOW_PUBLIC_RATINGS = booleanPreferencesKey("pref_show_public_ratings")

    /** 封面上是否画分数角标（[PreferencesRepository.showCoverScores]）：默认 true；只在 [SHOW_PUBLIC_RATINGS] 开着时起作用。 */
    val SHOW_COVER_SCORES = booleanPreferencesKey("pref_show_cover_scores")

    /** 名字旁画不画「好友」小牌（[PreferencesRepository.showFriendMarks]）：默认 true；专注模式下不起作用。 */
    val SHOW_FRIEND_MARKS = booleanPreferencesKey("pref_show_friend_marks")

    /**
     * 首页「待看」显示条数。可选值与默认值的真源是 `ToWatchLimit`，
     * [PreferencesRepository.toWatchLimit] 读盘时会 coerce（不在 [ToWatchLimit.OPTIONS] 里 → 默认）。
     */
    val HOME_TO_WATCH_LIMIT = intPreferencesKey("pref_home_to_watch_limit")

    /** 阅读字号档位（`ReadingTextSize` 的名字）；读盘经 `PreferencesRepository` 的 `enumOr` 收敛。 */
    val READING_TEXT_SIZE = stringPreferencesKey("pref_reading_text_size")

    /** 进度页启动时打开的分类（`SubjectType` 的名字；没有这个键 = 全部）。读盘经 [PreferencesRepository.progressDefaultType] 收敛。 */
    val PROGRESS_DEFAULT_TYPE = stringPreferencesKey("pref_progress_default_type")

    /** 封面画质档位（`CoverQuality` 的名字）；读盘经 `PreferencesRepository` 的 `enumOr` 收敛。 */
    val COVER_QUALITY = stringPreferencesKey("pref_cover_quality")

    /** 刷新率（整数 Hz，`RefreshRate.SYSTEM` = 跟随系统）；存原值，这块屏有没有这一档由 `RefreshRate.effective` 判。 */
    val REFRESH_RATE = intPreferencesKey("pref_refresh_rate")

    /** 探索页的聚焦引导走完了（[PreferencesRepository.exploreGuideShown]）：没有这个键 = 还没走完。 */
    val EXPLORE_GUIDE_SHOWN = booleanPreferencesKey("pref_explore_guide_shown")

    /** 最近搜索整表的会话归属；SearchRepository 删除旧行成功后才允许新归属读取。 */
    val RECENT_SEARCH_OWNER = stringPreferencesKey("recent_search_owner")

    /** 收藏全量同步「上次成功时刻」（epoch 毫秒）的键前缀；实际键为 `pref_collection_synced_at_<accountId>`。 */
    private const val COLLECTION_SYNCED_AT_PREFIX = "pref_collection_synced_at_"

    fun collectionSyncedAtKey(accountId: Int) = longPreferencesKey("$COLLECTION_SYNCED_AT_PREFIX$accountId")

    /** 置顶的小组（`GroupRepository.pinned`）的键前缀；实际键为 `pref_pinned_groups_<accountId>`，按账号分开。 */
    private const val PINNED_GROUPS_PREFIX = "pref_pinned_groups_"

    fun pinnedGroupsKey(accountId: Int) = stringPreferencesKey("$PINNED_GROUPS_PREFIX$accountId")

    /**
     * 服务端 NSFW 偏好的按账号缓存（`NsfwPreferenceRepository`）：服务端算出的 `allowNsfw`、账号的 `show_nsfw_subject`
     * 开关、现在能不能改它。实际键为 `nsfw_allow_<accountId>` 等。
     */
    fun nsfwAllowKey(accountId: Int) = booleanPreferencesKey("nsfw_allow_$accountId")
    fun nsfwShowKey(accountId: Int) = booleanPreferencesKey("nsfw_show_$accountId")
    fun nsfwCanSetKey(accountId: Int) = booleanPreferencesKey("nsfw_canset_$accountId")

    /** 绝交名单的按账号缓存（`BlocklistRepository`）：用户 id 以逗号连起来，与读到它的时刻（epoch 毫秒）。 */
    fun blocklistKey(accountId: Int) = stringPreferencesKey("blocklist_$accountId")
    fun blocklistFetchedAtKey(accountId: Int) = longPreferencesKey("blocklist_at_$accountId")

    /** 自己关注的人的按账号缓存（`FriendsRepository`）：用户 id 以逗号连起来，与读到它的时刻（epoch 毫秒）。 */
    fun friendsKey(accountId: Int) = stringPreferencesKey("friends_$accountId")
    fun friendsFetchedAtKey(accountId: Int) = longPreferencesKey("friends_at_$accountId")
}
