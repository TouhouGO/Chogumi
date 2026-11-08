package io.github.touhougo.chogumi.core.data.util

/**
 * 有上限的内存缓存：按访问顺序排，超过 [maxSize] 项时丢掉最久没用的那一项。
 * 进程内按实体分份的缓存（看过的人、看过的角色）一律用它，否则看得越多占得越多、直到进程被杀。
 *
 * 读也会调整顺序，所以不是线程安全的：调用方在自己的锁里读写。
 */
internal class LruMap<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
}
