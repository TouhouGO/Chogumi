package io.github.touhougo.chogumi.core.data.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 既写又跟着变的偏好：写入进行中开始的收集也收到写完的那份，迟到的旧快照不会排在它后面；没有写入时第一份只到一次。 */
class ObservedPreferencesTest {
    /**
     * 按 `DataStoreImpl` 建模的存储：写入先推进版本号再落数据；收集一开始就读下快照与版本号（写入进行中读到的是旧数据、
     * 新版本号），之后只放行版本号更高的更新。第一份快照可以压到 [emitGate] 放行后才发出：读完快照的协程被让出，
     * 等它再跑时写入早已完成。
     */
    private class VersionedDataStore : DataStore<Preferences> {
        /** 当前数据与写下它的版本号（`DataStoreImpl` 内存里的那份状态）。 */
        private val state = MutableStateFlow(emptyPreferences() to 0)
        @Volatile private var version = 0

        /** 写入推进版本号之后、落数据之前等它放行。 */
        var writeGate: CompletableDeferred<Unit>? = null

        /** 第一份快照读下之后、发出之前等它放行。 */
        var emitGate: CompletableDeferred<Unit>? = null
        val versionBumped = CompletableDeferred<Unit>()

        /** 第一次收集已读下快照与版本号（还没发出）。 */
        val snapshotTaken = CompletableDeferred<Unit>()
        val firstEmitted = CompletableDeferred<Unit>()

        /** 开始过几次收集（每次收集读一份快照）。 */
        val reads = AtomicInteger(0)

        override val data: Flow<Preferences> = flow {
            reads.incrementAndGet()
            val (snapshot, _) = state.value
            val seen = version
            snapshotTaken.complete(Unit)
            emitGate?.await()
            emit(snapshot)
            firstEmitted.complete(Unit)
            emitAll(state.filter { (_, written) -> written > seen }.map { (prefs, _) -> prefs })
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val newVersion = ++version
            versionBumped.complete(Unit)
            writeGate?.await()
            val next = transform(state.value.first)
            state.value = next to newVersion
            return next
        }
    }

    private val key = stringPreferencesKey("blocklist")

    @Test
    fun `写入进行中开始的收集先到旧快照再到写完的那份_旧快照不排在后面`() = runBlocking {
        val store = VersionedDataStore()
        val prefs = ObservedPreferences(store)
        val writeGate = CompletableDeferred<Unit>().also { store.writeGate = it }
        val emitGate = CompletableDeferred<Unit>().also { store.emitGate = it }
        val write = launch(Dispatchers.Default) { prefs.edit { it[key] = "99" } }
        withTimeout(WAIT_BUDGET_MS) { store.versionBumped.await() }
        val seen = MutableStateFlow(emptyList<String?>())
        val collector = launch(Dispatchers.Default) { prefs.data.collect { current -> seen.update { it + current[key] } } }
        withTimeout(WAIT_BUDGET_MS) { store.snapshotTaken.await() }
        writeGate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { write.join() }
        emitGate.complete(Unit)
        awaitUntil("写完的那份到达收集方") { "99" in seen.value }
        prefs.edit { it[key] = "100" }
        awaitUntil("后一次写入到达收集方") { seen.value.lastOrNull() == "100" }
        assertEquals(listOf(null, "99", "100"), seen.value, "旧快照先到、写完的那份补上，之后按写入顺序")
        assertEquals(2, store.reads.get(), "第一份可能是脏读：写完之后补读一次")
        collector.cancelAndJoin()
    }

    @Test
    fun `没有写入进行中时第一份只到一次`() = runBlocking {
        val store = VersionedDataStore()
        val prefs = ObservedPreferences(store)
        val seen = MutableStateFlow(emptyList<String?>())
        val collector = launch(Dispatchers.Default) { prefs.data.collect { current -> seen.update { it + current[key] } } }
        withTimeout(WAIT_BUDGET_MS) { store.firstEmitted.await() }
        prefs.edit { it[key] = "99" }
        awaitUntil("写入到达收集方") { seen.value.lastOrNull() == "99" }
        assertEquals(listOf(null, "99"), seen.value, "第一份没有重复，写入只到一拍")
        assertEquals(1, store.reads.get(), "没有写入进行中就不补读")
        collector.cancelAndJoin()
    }
}
