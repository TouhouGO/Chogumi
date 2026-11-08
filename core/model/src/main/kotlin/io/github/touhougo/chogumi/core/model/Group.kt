package io.github.touhougo.chogumi.core.model

/**
 * 一个小组：列表里的一项、帖子挂在哪个小组、帖子页眉题里的小组都是它。
 *
 * @property name 小组的英文键（网页路径段 `/group/{name}`、接口参数、本机置顶名单都用它）。
 * @property icon 小组图标；没有时为 null。
 */
data class GroupSummaryBo(
    val name: String,
    val title: String,
    val icon: String?,
    val members: Int,
)

/**
 * 小组页头部。
 *
 * @property topics 话题数。
 * @property createdAt 建组时间，epoch 毫秒；0 表示未知。
 * @property joined 当前账号已加入（未登录恒 false）。
 */
data class GroupBo(
    val summary: GroupSummaryBo,
    val description: String,
    val topics: Int,
    val createdAt: Long,
    val joined: Boolean,
)

/**
 * 社区「小组」页顶部那一行的顺序，只此一处：置顶的在前（[pinned] 的顺序，最近置顶的在最前），其余我加入的按服务端顺序在后。
 * 置顶名单里已经不在 [joined] 里的小组（在别处退出了）不画，名单本身不动。
 */
fun pinnedFirst(joined: List<GroupSummaryBo>, pinned: List<String>): List<GroupSummaryBo> {
    val byName = joined.associateBy { it.name }
    val top = pinned.mapNotNull { byName[it] }
    val topNames = top.mapTo(HashSet()) { it.name }
    return top + joined.filterNot { it.name in topNames }
}
