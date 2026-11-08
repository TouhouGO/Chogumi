package io.github.touhougo.chogumi.core.data.di

import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

internal data class DataStores(
    val preferences: DataStore<Preferences>,
    val tokens: DataStore<Preferences>,
)

/** 创建应用偏好与令牌 DataStore；两者的生产配置也由本模块单测直接验证。 */
internal fun createDataStores(
    preferencesFile: File,
    tokensFile: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    onCorruption: (File, CorruptionException) -> Unit = { file, exception ->
        Log.w(
            DATA_STORE_CORRUPTION_LOG_TAG,
            "Corrupted ${file.name}; resetting it to empty preferences",
            exception,
        )
    },
): DataStores {
    fun create(file: File) = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            onCorruption(file, exception)
            emptyPreferences()
        },
        scope = scope,
    ) { file }

    return DataStores(preferences = create(preferencesFile), tokens = create(tokensFile))
}

private const val DATA_STORE_CORRUPTION_LOG_TAG = "ChogumiDataStore"
