package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 不创建线程或文件的偏好替身；历史隔离回归另外传入真实持久 DataStore。 */
class TestSearchHistoryPreferences : DataStore<Preferences> {
    private val values = MutableStateFlow(emptyPreferences())
    private val writes = Mutex()
    override val data: Flow<Preferences> = values

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = writes.withLock {
        transform(values.value).also { values.value = it }
    }
}
