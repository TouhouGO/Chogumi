package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.model.isSubjectVisible

/**
 * NSFW 过滤 —— **全仓唯一的过滤组件**。
 *
 * 各仓库调用本组件剔除 NSFW 条目，**禁止**在各仓库里另写 `filter { !it.nsfw }`，
 * 也**禁止**在 ViewModel / UI 层过滤（判定与过滤都只此一处，见 [isSubjectVisible]）。
 *
 * ## 判定来源
 *
 * 开关值取自 [NsfwPreferenceRepository]（bgm.tv 账号设置 `p1/privacy` 的缓存）：
 * 未登录或缓存未就绪恒为 false。本地不产生开关值，服务端才是真源。
 *
 * ## 生效时机
 *
 * 调用时读取开关当前值；已加载的页面在切换开关后**刷新 / 重进才生效**，不即时生效。
 * 若日后要做即时生效（combine 开关 Flow），只能在本组件这一处做，
 * **不得**让每个 ViewModel 各自订阅开关。
 *
 * ## 分页红线
 *
 * 本组件只负责「条目该不该出现」，**绝不参与游标计算**。
 * 翻页游标必须按服务端原始页（未过滤）的 `total` / `limit` 推进，
 * 过滤后整页为空也要能继续翻下一页（见 [nextCursor] 的说明）。
 * 因此调用方**一律在算完游标之后再过滤**。
 */
class NsfwFilter(
    private val nsfwPreferenceRepository: NsfwPreferenceRepository,
) {
    /** 读取开关当前值；返回 true 表示允许显示 NSFW 条目。 */
    suspend fun showNsfw(): Boolean = nsfwPreferenceRepository.showNsfw()

    /** 按当前开关过滤掉不可见的条目（NSFW 且开关关闭时被剔除）。 */
    suspend fun <T> apply(items: List<T>, nsfw: (T) -> Boolean): List<T> {
        val show = showNsfw()
        return items.filter { isSubjectVisible(nsfw(it), show) }
    }
}
