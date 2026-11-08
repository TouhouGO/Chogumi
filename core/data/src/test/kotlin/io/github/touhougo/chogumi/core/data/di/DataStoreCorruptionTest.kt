package io.github.touhougo.chogumi.core.data.di

import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DataStoreCorruptionTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val temporaryDirectories = mutableListOf<File>()
    private val loggedCorruptions = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        temporaryDirectories.forEach(File::deleteRecursively)
    }

    @Test
    fun `损坏的偏好文件重置后仓库读出默认值`() = runTest {
        val directory = temporaryDirectory()
        val preferencesFile = File(directory, "chogumi_preferences.preferences_pb")
        preferencesFile.writeBytes(GARBAGE_BYTES)

        val dataStores = createCorruptionTestDataStores(directory, preferencesFile)
        val preferences = PreferencesRepository(dataStores.preferences)

        assertEquals(ThemeMode.FOLLOW_SYSTEM, preferences.themeMode.first())
        assertEquals(ThemePreferences(), preferences.themePreferences.first())
        assertEquals(ExperienceMode.FULL, preferences.experienceMode.first())
        assertEquals(true, preferences.showPublicRatings.first())
        assertEquals(AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true), preferences.appFeatures.first())
        assertEquals(listOf(preferencesFile.name), loggedCorruptions)
    }

    @Test
    fun `损坏的令牌文件重置后处于未登录状态`() = runTest {
        val directory = temporaryDirectory()
        val tokensFile = File(directory, "chogumi_tokens.preferences_pb")
        tokensFile.writeBytes(GARBAGE_BYTES)
        val dataStores = createCorruptionTestDataStores(directory, tokensFile = tokensFile)

        val tokens = TokenStore(dataStores.tokens)

        assertEquals(
            AccountSession(generation = 0, userId = null, username = null, hasToken = false),
            tokens.session(),
        )
        assertFalse(tokens.session().authenticated)
        assertNull(tokens.accessToken())
        assertNull(tokens.refreshGrant()?.refreshToken)
        assertEquals(listOf(tokensFile.name), loggedCorruptions)
    }

    private fun createCorruptionTestDataStores(
        directory: File,
        preferencesFile: File? = null,
        tokensFile: File? = null,
    ) =
        createDataStores(
            preferencesFile = preferencesFile ?: File(directory, "chogumi_preferences.preferences_pb"),
            tokensFile = tokensFile ?: File(directory, "chogumi_tokens.preferences_pb"),
            scope = scope,
            onCorruption = { file, _ -> loggedCorruptions += file.name },
        )

    private fun temporaryDirectory(): File = Files.createTempDirectory("chogumi-corruption-")
        .toFile()
        .also(temporaryDirectories::add)

    private companion object {
        val GARBAGE_BYTES = byteArrayOf(0x00, 0x01, 0x7F, 0xFF.toByte())
    }
}
