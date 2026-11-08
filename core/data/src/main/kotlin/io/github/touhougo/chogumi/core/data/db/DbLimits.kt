package io.github.touhougo.chogumi.core.data.db

/**
 * SQLite `IN (...)` 一次能绑定的参数上限。
 *
 * Android 自带的 SQLite 在 API < 32 有 **999** 个宿主参数上限（API 32+ 提到 32766）。
 * 本 App minSdk = 24，重度账号（实测单账号 1592 部）远超 999，
 * 不切块会 `SQLiteException: too many SQL variables`。取 900 留安全余量。
 *
 * **唯一真源**：所有按 id 的 `IN` 批量查询都复用本常量，不得各写一份数字。
 */
internal const val SQLITE_IN_CHUNK = 900
