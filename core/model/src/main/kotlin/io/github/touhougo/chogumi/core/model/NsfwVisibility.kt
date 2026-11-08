package io.github.touhougo.chogumi.core.model

/**
 * NSFW 条目可见性判定 —— **全仓唯一的纯判定函数**。
 *
 * 真源是 bgm.tv 账号设置（`p1/privacy` 的 `allowNsfw`），本地只存按账号隔离的缓存
 * （`NsfwPreferenceRepository`）；未登录或缓存未就绪恒为 false。
 * `showNsfw` 由调用方（唯一过滤组件 NsfwFilter）从缓存取得后传入，本函数只做纯判定。
 * 关闭时软件里**所有「列条目」的地方**都不得出现 NSFW 条目。
 *
 * 判定只此一处：数据层的过滤组件 [io.github.touhougo.chogumi.core.data.repository.NsfwFilter]
 * 与任何需要该判定的调用方都必须引用本函数，**禁止**在各仓库/ViewModel/UI 里
 * 另写 `!it.nsfw` 之类的等价逻辑（单一真源与解耦是本项目绝对红线）。
 *
 * 注意：本函数只描述「单条条目该不该出现」，**不涉及分页游标**——
 * 游标必须按服务端原始页推进，见 `nextCursor` 与 `PagedCursor.kt` 的说明。
 */
fun isSubjectVisible(nsfw: Boolean, showNsfw: Boolean): Boolean = showNsfw || !nsfw
