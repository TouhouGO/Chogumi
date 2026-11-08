package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * [SyncPreferenceStore] 的生产实现：把「上次成功同步时刻」写进偏好 DataStore。
 *
 * **为什么放偏好 DataStore（`chogumi_preferences`）而不是单开一个**：
 * 它是纯本地的省流量优化（30 分钟新鲜期），丢了只是多同步一次，不值得为它
 * 新增存储文件；按账号隔离用键后缀（键名真源见 [PreferenceKeys.collectionSyncedAtKey]）。
 */
class DataStoreSyncPreferenceStore(
    private val dataStore: DataStore<Preferences>,
) : SyncPreferenceStore {

    override suspend fun lastSyncedAt(accountId: Int): Long? =
        dataStore.data.first()[PreferenceKeys.collectionSyncedAtKey(accountId)]

    override suspend fun markSynced(accountId: Int, at: Long) {
        dataStore.edit { it[PreferenceKeys.collectionSyncedAtKey(accountId)] = at }
    }
}
