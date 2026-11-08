package io.github.touhougo.chogumi.core.model

/** 有原名与中文名的实体：作品、章节、角色、人物，以及它们在关联、目录、话题里的摘要。显示名由 [displayNameOf] 定。 */
interface NamedEntity {
    /** 原名（服务端 `name`）。 */
    val name: String

    /** 中文名（服务端 `name_cn` / `nameCN`）；没有译名时是空串。 */
    val nameCn: String
}

/**
 * 显示名：中文名优先，空白时回退原名。**全 App 唯一的这条规则**，标题、列表、排序、动态卡片都经它
 * （门禁 `tools/guards/check_display_name.py` 拦住别处手写的 `nameCn.ifBlank { name }`）。
 */
fun displayNameOf(name: String, nameCn: String): String = nameCn.ifBlank { name }

/** [NamedEntity] 的显示名，规则同上。 */
fun displayNameOf(entity: NamedEntity): String = displayNameOf(name = entity.name, nameCn = entity.nameCn)

/**
 * 用户的显示名：昵称优先，空白时回退用户名，两者都空时写 `#uid`（仍分得清是谁）。**全 App 唯一的这条规则**：主页、楼层、
 * 动态、通知、目录作者、收藏者都经它（门禁 `tools/guards/check_display_name.py` 拦住别处手写的 `nickname.ifBlank { … }`）。
 * 接口给的是三个字段的地方（楼层作者、通知发送者）直接传字段，有 [UserBo] 的传它本身。
 */
fun userDisplayNameOf(username: String, nickname: String, uid: Int): String =
    nickname.ifBlank { username }.ifBlank { "#$uid" }

/** 用户键（网页链接、提及、屏蔽名单用）：有用户名用用户名，没有就用数字 id。全仓唯一的推导。 */
fun userKeyOf(username: String?, uid: Int): String = username.orEmpty().ifBlank { uid.toString() }

/** [UserBo] 的显示名，规则同上。 */
fun displayNameOf(user: UserBo): String = userDisplayNameOf(user.username, user.nickname, user.id)

/** 只知道 uid、资料可能没随来时（动态作者）的显示名，规则同上。 */
fun displayNameOf(user: UserBo?, uid: Int): String =
    userDisplayNameOf(user?.username.orEmpty(), user?.nickname.orEmpty(), uid)
