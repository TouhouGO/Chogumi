package io.github.touhougo.chogumi.core.data.db.cache

/**
 * 一类本地缓存行的清理阈值。
 *
 * @param maxAgeMillis 多久没被写入（用到）就过期。
 * @param maxRows 不受保护的行最多留多少；超出时从最久没用到的开始删。
 */
data class CacheEvictionPolicy(val maxAgeMillis: Long, val maxRows: Int)

/** 参与清理判断的一行：主键 + 最近一次被写入的时刻（epoch 毫秒，0 表示未知）。 */
data class CachedRowAge<K>(val key: K, val touchedAt: Long)

/**
 * 本地缓存清理的唯一规则：给出应删除的行。
 *
 * 1. 受保护的行（[isProtected]，如用户自己收藏里的条目）**永远不删**，也不占 [CacheEvictionPolicy.maxRows] 名额；
 * 2. 其余行超过 [CacheEvictionPolicy.maxAgeMillis] 没被写入的删除；时刻未知（0）视为已过期；
 * 3. 剩下的不受保护行若仍多于 [CacheEvictionPolicy.maxRows]，从最久没被写入的开始删到上限。
 *
 * 被删的行都能从服务端重新拉取：清理只影响「下次是否秒开」，不丢用户数据。
 */
fun <K> cacheEvictionVictims(
    rows: List<CachedRowAge<K>>,
    isProtected: (K) -> Boolean,
    nowMillis: Long,
    policy: CacheEvictionPolicy,
): Set<K> {
    val (expired, fresh) = rows
        .filterNot { isProtected(it.key) }
        .partition { it.touchedAt <= 0L || nowMillis - it.touchedAt >= policy.maxAgeMillis }
    val overCap = fresh.sortedByDescending { it.touchedAt }.drop(policy.maxRows)
    return (expired + overCap).mapTo(mutableSetOf()) { it.key }
}

/**
 * 本地留着数据的账号里该清掉哪些（唯一判定）：[lastActiveAt] 是每个账号最近一次在本机用过的时刻
 * （同步或首页刷新，0 表示不知道），超过 [maxIdleMillis] 没用过的清掉。
 *
 * - 当前登录的账号（[currentAccountId]）永远不清；
 * - 时刻不知道的不清：这是用户自己的数据，宁可多留，不像缓存行那样按过期算。
 *
 * 清掉的都能在切回来时从服务端重新同步。
 */
fun inactiveAccounts(lastActiveAt: Map<Int, Long>, currentAccountId: Int?, nowMillis: Long, maxIdleMillis: Long): Set<Int> =
    lastActiveAt.filter { (accountId, at) -> accountId != currentAccountId && at > 0L && nowMillis - at >= maxIdleMillis }.keys
