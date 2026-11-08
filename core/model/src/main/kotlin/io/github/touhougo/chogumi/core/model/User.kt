package io.github.touhougo.chogumi.core.model

/**
 * 用户资料领域模型。
 *
 * 字段来源分两类，不混用：
 * - **v0**（`/v0/me`、`/v0/users/{username}`）：基础资料（昵称、头像、`sign`）；
 * - **p1** `/p1/users/{username}`：`site`/`location`/`bio`/`joinedAt`/`stats`。
 *
 * p1 取不到时不影响 v0 已提供的资料展示。
 */
data class UserBo(
    val id: Int,
    val username: String,
    val nickname: String,
    val avatarLarge: String = "",
    val avatarMedium: String = "",
    val avatarSmall: String = "",
    val sign: String = "",
    /** 个人主页（bgm.tv 自身地址或用户填写的主页） */
    val site: String = "",
    val location: String = "",
    val bio: String = "",
    /** 注册时间，epoch 毫秒；0 表示未知（p1 不返回时保留 v0 的 reg_time） */
    val joinedAt: Long = 0L,
    /** 条目收藏统计：类型 → (状态 → 条数)，已由服务端补零填满 */
    val subjectStats: Map<SubjectType, Map<CollectionType, Int>> = emptyMap(),
    /** 角色 / 人物收藏数 */
    val characterCount: Int = 0,
    val personCount: Int = 0,
    val blogCount: Int = 0,
    /** 关注的人数：好友 tab 列表端点的 `total`（资料 stats 是服务端缓存，加、取消好友后要过一阵子才变）。 */
    val friendCount: Int = 0,
    val groupCount: Int = 0,
    /** 创建的 / 收藏的目录数：目录页两个列表端点的 `total`（不是资料 stats，那份与列表对不上），资料头「目录 N」与目录页页签同一来源。 */
    val indexCreateCount: Int = 0,
    val indexCollectCount: Int = 0,
) {
    /** 某条目类型下的收藏数（跨状态求和） */
    fun countOf(type: SubjectType): Int = subjectStats[type]?.values?.sum() ?: 0

    /** 某条目类型下某状态的收藏数 */
    fun countOf(type: SubjectType, type2: CollectionType): Int = subjectStats[type]?.get(type2) ?: 0

    /** 该用户的头像图址，规则见 [userAvatarUrl]。 */
    val avatarUrl: String? get() = userAvatarUrl(avatarLarge, avatarMedium, avatarSmall)
}

/**
 * 用户头像取哪一档的唯一规则：优先大图，为空才依次回退中图、小图；三档都空返回 null。
 *
 * 全 App 只取同一档，是为了让同一个人的头像只下载、只缓存一份：
 * 不同页面各取一档时，同一个头像会按两个 URL 各下载、各缓存一次。
 * 大图在最大的展示位（个人页头部）也清晰，小展示位由图片加载器按控件尺寸解码，
 * 多出的只是首次下载的体积。**设计决定（not in M3）**。
 */
fun userAvatarUrl(large: String, medium: String, small: String): String? =
    large.ifBlank { medium.ifBlank { small } }.ifBlank { null }
