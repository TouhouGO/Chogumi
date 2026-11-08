package io.github.touhougo.chogumi.core.data.repository

/**
 * 把一段跨表写库包进一个数据库事务。
 *
 * **为什么要这层抽象**：Room 的 `@Transaction` 注解只能包住**单个 DAO** 内的方法，
 * 而收藏全量同步一次要同时写 `subjects` 与 `user_collections` 两张表——跨越两个 DAO。
 * 生产环境由 `ChogumiDatabase.withTransaction` 提供真正的跨表事务（DataModule）；
 * 仓库只依赖这个接口，不必把 `ChogumiDatabase` 拽进构造函数——JVM 单测没有 Room 运行时，
 * 注入直通或统计调用次数的实现（断言「每类一次事务」）。
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
