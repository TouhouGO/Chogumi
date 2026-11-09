package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.ReadingTextSize
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ThemeColorMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.model.ThemePreset
import io.github.touhougo.chogumi.core.model.ToWatchLimit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferencesRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()

    /** 一份新的偏好 DataStore：作用域与临时目录都由 [resources] 收尾。 */
    private fun newDataStore(): DataStore<Preferences> {
        val dir = resources.directory("pref-test")
        return PreferenceDataStoreFactory.create(scope = resources.scope) { File(dir, "p.preferences_pb") }
    }

    private fun newRepo(): PreferencesRepository = PreferencesRepository(newDataStore())

    @Test
    fun `写别的偏好时_这个偏好的流不重发同值`() = runTest {
        val repo = newRepo()
        val seen = Channel<ThemeMode>(Channel.UNLIMITED)
        backgroundScope.launch { repo.themeMode.collect { seen.send(it) } }
        assertEquals(ThemeMode.FOLLOW_SYSTEM, seen.receive())

        repo.setCoverQuality(CoverQuality.entries.first { it != CoverQuality.DEFAULT })
        repo.coverQuality.first { it != CoverQuality.DEFAULT }
        repo.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, seen.receive(), "别的键写入不该再发一次 FOLLOW_SYSTEM")
    }

    @Test
    fun `手势引导_没放过是假_记下之后是真`() = runTest {
        val repo = newRepo()
        assertEquals(false, repo.gestureTourShown.first())
        repo.setGestureTourShown()
        assertEquals(true, repo.gestureTourShown.first())
    }

    @Test
    fun `主题色默认粉色且不随系统强制改变`() = runTest {
        val value = newRepo().themePreferences.first()
        assertEquals(ThemeColorMode.SEED, value.mode)
        assertEquals(0xFFF09199L, value.seedArgb)
        assertEquals(ThemePreset.INK, value.preset)
    }

    @Test
    fun `换配色来源仍保存自选颜色与配色方案`() = runTest {
        val repo = newRepo()
        val value = ThemePreferences(ThemeColorMode.PRESET, 0xFF526CFDL, ThemePreset.PEACOCK)
        repo.setThemePreferences(value)
        assertEquals(value, repo.themePreferences.first())
        repo.setThemePreferences(value.copy(mode = ThemeColorMode.SEED))
        assertEquals(value.copy(mode = ThemeColorMode.SEED), repo.themePreferences.first())
    }

    @Test
    fun `体验模式默认完整且写入后读取`() = runTest {
        val repo = newRepo()
        assertEquals(io.github.touhougo.chogumi.core.model.ExperienceMode.FULL, repo.experienceMode.first())
        repo.setExperienceMode(io.github.touhougo.chogumi.core.model.ExperienceMode.FOCUS)
        assertEquals(io.github.touhougo.chogumi.core.model.ExperienceMode.FOCUS, repo.experienceMode.first())
    }

    @Test
    fun `显示公共评分默认开启且写入后读取`() = runTest {
        val repo = newRepo()
        assertEquals(true, repo.showPublicRatings.first())
        repo.setShowPublicRatings(false)
        assertEquals(false, repo.showPublicRatings.first())
    }

    @Test
    fun `首页待看条数默认 8 且写入后读取`() = runTest {
        val repo = newRepo()
        assertEquals(ToWatchLimit.DEFAULT, repo.toWatchLimit.first())
        repo.setToWatchLimit(5)
        assertEquals(5, repo.toWatchLimit.first())
    }

    @Test
    fun `封面画质默认自动且写入后读取`() = runTest {
        val repo = newRepo()
        assertEquals(CoverQuality.AUTO, repo.coverQuality.first())
        repo.setCoverQuality(CoverQuality.ORIGINAL)
        assertEquals(CoverQuality.ORIGINAL, repo.coverQuality.first())
    }

    @Test
    fun `枚举偏好按名字认档，认不出的名字回落各自的默认`() = runTest {
        val dataStore = newDataStore()
        dataStore.edit {
            it[PreferenceKeys.READING_TEXT_SIZE] = "LARGE"
            it[PreferenceKeys.THEME_MODE] = "HUGE"
            it[PreferenceKeys.EXPERIENCE_MODE] = ""
            it[PreferenceKeys.COVER_QUALITY] = "HIGHEST"
        }
        val repo = PreferencesRepository(dataStore)
        assertEquals(ReadingTextSize.LARGE, repo.readingTextSize.first())
        assertEquals(ThemeMode.FOLLOW_SYSTEM, repo.themeMode.first())
        assertEquals(io.github.touhougo.chogumi.core.model.ExperienceMode.FULL, repo.experienceMode.first())
        assertEquals(ThemeColorMode.SEED, repo.themePreferences.first().mode, "缺省")
        assertEquals(CoverQuality.DEFAULT, repo.coverQuality.first())
    }

    @Test
    fun `首页待看条数磁盘值不在选项里时读成默认`() = runTest {
        // 直接往 DataStore 写一个不在 OPTIONS 里的值（不经 setter，模拟旧版本留下的脏数据）
        val dataStore = newDataStore()
        dataStore.edit { it[PreferenceKeys.HOME_TO_WATCH_LIMIT] = 7 }
        assertEquals(ToWatchLimit.DEFAULT, PreferencesRepository(dataStore).toWatchLimit.first())
    }

    @Test
    fun `进度页默认分类 缺省是全部 选回全部就删掉这个键`() = runTest {
        val dataStore = newDataStore()
        val repo = PreferencesRepository(dataStore)
        assertEquals(null, repo.progressDefaultType.first())
        repo.setProgressDefaultType(SubjectType.GAME)
        assertEquals(SubjectType.GAME, repo.progressDefaultType.first { it != null })
        assertEquals(SubjectType.GAME.name, dataStore.data.first()[PreferenceKeys.PROGRESS_DEFAULT_TYPE])
        repo.setProgressDefaultType(null)
        assertEquals(null, repo.progressDefaultType.first { it == null })
        assertEquals(false, dataStore.data.first().contains(PreferenceKeys.PROGRESS_DEFAULT_TYPE))
    }

    @Test
    fun `进度页默认分类 磁盘值不是五个内容分类之一时读成全部`() = runTest {
        val dataStore = newDataStore()
        dataStore.edit { it[PreferenceKeys.PROGRESS_DEFAULT_TYPE] = SubjectType.UNKNOWN.name }
        assertEquals(null, PreferencesRepository(dataStore).progressDefaultType.first())
        dataStore.edit { it[PreferenceKeys.PROGRESS_DEFAULT_TYPE] = "COMIC" }
        assertEquals(null, PreferencesRepository(dataStore).progressDefaultType.first())
    }

    @Test
    fun `appFeatures从四个偏好流唯一派生`() = runTest {
        val repo = newRepo()
        assertEquals(
            io.github.touhougo.chogumi.core.model.AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true),
            repo.appFeatures.first(),
        )
        repo.setShowPublicRatings(false)
        assertEquals(
            io.github.touhougo.chogumi.core.model.AppFeatures(community = true, publicRatings = false, coverScores = false, friendMarks = true),
            repo.appFeatures.first(),
        )
        repo.setExperienceMode(io.github.touhougo.chogumi.core.model.ExperienceMode.FOCUS)
        assertEquals(
            io.github.touhougo.chogumi.core.model.AppFeatures(community = false, publicRatings = false, coverScores = false, friendMarks = false),
            repo.appFeatures.first(),
        )
        repo.setShowPublicRatings(true)
        assertEquals(
            io.github.touhougo.chogumi.core.model.AppFeatures(community = false, publicRatings = true, coverScores = true, friendMarks = false),
            repo.appFeatures.first(),
        )
        repo.setShowCoverScores(false)
        assertEquals(
            io.github.touhougo.chogumi.core.model.AppFeatures(community = false, publicRatings = true, coverScores = false, friendMarks = false),
            repo.appFeatures.first(),
        )
        repo.setExperienceMode(io.github.touhougo.chogumi.core.model.ExperienceMode.FULL)
        repo.setShowFriendMarks(false)
        assertEquals(
            io.github.touhougo.chogumi.core.model.AppFeatures(community = true, publicRatings = true, coverScores = false, friendMarks = false),
            repo.appFeatures.first(),
        )
    }
}

