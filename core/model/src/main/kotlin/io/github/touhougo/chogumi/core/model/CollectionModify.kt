package io.github.touhougo.chogumi.core.model

/** 收藏部分更新。null 表示保持原值，空文本/空列表表示明确清空。 */
data class CollectionModify(
    val type: CollectionType? = null,
    val rate: Int? = null,
    val epStatus: Int? = null,
    val volStatus: Int? = null,
    val comment: String? = null,
    val `private`: Boolean? = null,
    val tags: List<String>? = null,
) {
    /**
     * 只留相对 [original] **真的改了**的字段，其余置 null（不发）。
     *
     * 收藏面板是整张表单，原样回发会把没动过的字段一起发出去，而服务端（v0 PATCH，`ctrl/update_subject_collection.go`）
     * 只要请求里带了 `type` 就发一条时间线，并把**请求里**的吐槽与评分写进去——于是只改了状态，
     * 没动的吐槽也跟着重新出现在时间线上。所以只发改动：改状态的时间线不带上旧吐槽，改吐槽、标签不发时间线。
     *
     * [original] 为 null（尚未收藏，服务端走新建）时整张表单照发，**只是进度为 0 的不发**：
     * 0 本就是新收藏的默认值，发了服务端会另记一条「进度更新」时间线（实测：书籍标「在读」后一秒，
     * 公开动态里多出一条「读到 0 话 0 卷」，好友也看得到）。
     */
    fun changedFrom(original: UserCollectionBo?): CollectionModify {
        if (original == null) return copy(epStatus = epStatus?.takeIf { it > 0 }, volStatus = volStatus?.takeIf { it > 0 })
        return CollectionModify(
            type = type?.takeIf { it != original.type },
            rate = rate?.takeIf { it != original.rate },
            epStatus = epStatus?.takeIf { it != original.epStatus },
            volStatus = volStatus?.takeIf { it != original.volStatus },
            comment = comment?.takeIf { it.trim() != original.comment.trim() },
            `private` = `private`?.takeIf { it != original.isPrivate },
            tags = tags?.takeIf { it != original.tags },
        )
    }
}
