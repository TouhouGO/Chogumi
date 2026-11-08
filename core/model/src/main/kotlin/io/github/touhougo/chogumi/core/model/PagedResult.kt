package io.github.touhougo.chogumi.core.model

/**
 * 一页数据 + 服务端给出的总数 + 下一页的游标。
 *
 * 为什么不是裸 `List`：分页端点**一页取不完是常态**。契约把 `limit` 上限钉死在 100
 * （见 `tools/openapi/generated/p1.bundled.json` 的 `maximum: 100`），
 * 而实测单个用户可以有 2600+ 关注者。没有 [total] 就无法判断"还有没有下一页"，
 * 界面会静默截断成第一页——没有错误、没有提示，用户以为就这么多。
 *
 * 为什么由数据层给游标：界面拿到的列表会去重、会与已加载内容合并，
 * **长度不等于已向服务端要过的条数**，用它反推 offset 会重复请求或漏取。
 *
 * [total] 直接来自服务端响应的 `total` 字段，不做本地推算：
 * 服务端先切结果集、再跳过查不到的条目（发布者已注销），
 * 所以 `total` 是**过滤前**的行数，本地列表通常比它短，两者不等是正常的。
 *
 * @param items 本页数据
 * @param total 服务端侧的总条数；小于等于 0 表示服务端未给出
 * @param nextOffset 下一页的 `offset`；null 表示已到末页
 */
data class PagedResult<T>(
    val items: List<T>,
    val total: Int,
    val nextOffset: Int?,
) {
    /** 是否还有下一页。空页但有后续游标时仍为真（中间整段被服务端跳过）。 */
    val hasMore: Boolean get() = nextOffset != null
}
