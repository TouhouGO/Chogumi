package io.github.touhougo.chogumi.core.data.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一份偏好 DataStore 的读、写与变更流，给既写又跟着它变的仓库（绝交名单、好友名单、置顶小组、设置、令牌、最近搜索的归属）。
 *
 * DataStore 自己的 `data` 流在一次写入进行中开始收集时拿到的是脏读的快照：写入先推进版本号再落文件
 * （`DataStoreImpl.writeData`），没拿到锁的读取读到旧文件、记下新版本号（`readDataOrHandleCorruption`），
 * 流随后以这个版本号为基线丢掉版本号不高于它的更新（`DataStoreImpl.data` 的 `dropWhile`）：正在进行的那次写入
 * 就此丢失，收集方停在旧值上，直到下一次写入。只有第一份可能这样脏，之后的每一拍都是写完之后的状态。
 *
 * 所以 [data] 发出第一份之后看一眼：开始收集时本类有写入进行中，或读第一份期间有写入开始，就等那次写入结束
 * （拿一下写锁）再读一次、补发那一份；这之后的写入版本号都高于基线，DataStore 自己的流一拍不丢。流只有 DataStore
 * 这一个来源、同一个协程顺序发出，拍的顺序就是版本的顺序。再并一条「写完出一拍」的流排不了序：收集方先收到写完的
 * 一拍、后收到迟到的旧快照，就被盖回旧值。读取不拿写锁，写入不等读取。同一个 DataStore 由别的类写入时不经这里，
 * 那种写入正在进行时开始的收集仍可能丢掉它那一拍。
 */
class ObservedPreferences(private val dataStore: DataStore<Preferences>) {
    private val writes = Mutex()
    private val started = AtomicInteger(0)
    private val finished = AtomicInteger(0)

    /** 当前那份偏好，之后每次写入再出一拍。 */
    val data: Flow<Preferences> = flow {
        val finishedBefore = finished.get()
        val startedBefore = started.get()
        var first = true
        dataStore.data.collect { prefs ->
            emit(prefs)
            if (first) {
                first = false
                if (startedBefore > finishedBefore || started.get() > startedBefore) {
                    writes.withLock { }
                    emit(dataStore.data.first())
                }
            }
        }
    }

    /** 写入并返回写后的偏好。 */
    suspend fun edit(transform: suspend (MutablePreferences) -> Unit): Preferences {
        started.incrementAndGet()
        return writes.withLock {
            try {
                dataStore.edit(transform)
            } finally {
                finished.incrementAndGet()
            }
        }
    }
}
