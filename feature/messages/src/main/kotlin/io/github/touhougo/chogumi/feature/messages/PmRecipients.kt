package io.github.touhougo.chogumi.feature.messages

import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.PmConversationSummary
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf

/**
 * 选收件人的一屏：照输入筛过的最近联系人与好友，与可以直接写给的用户名。
 *
 * @property typed 输入像一个用户名、又不是哪位好友的用户名时，就是它（「写给 @输入」那一行）；否则为 null。
 */
internal data class PmRecipients(
    val typed: String?,
    val recent: List<PmConversationSummary>,
    val friends: List<UserBo>,
)

/** bgm.tv 的用户名：字母、数字、下划线（没设用户名的人，主页地址里是数字 uid，同样能查）。 */
private val USERNAME = Regex("[A-Za-z0-9_]+")

/**
 * 选收件人的唯一规则：名字（[displayNameOf]）或用户名含输入（不分大小写，输入为空时全列）；绝交名单里的人不列；
 * 已经列在最近联系人里的好友不在好友里再列一次；输入像用户名、又不是哪位好友的用户名时多给一行直接写给它。
 * 最近联系人的行读不出用户名（会话列表只给名字），只按名字筛。
 */
internal fun pmRecipientsOf(
    query: String,
    recent: List<PmConversationSummary>,
    friends: List<UserBo>,
    blocked: BlockedUsers,
): PmRecipients {
    val text = query.trim()
    fun matches(user: UserBo) =
        displayNameOf(user).contains(text, ignoreCase = true) || user.username.contains(text, ignoreCase = true)
    // 会话列表里读不出 uid 的行，对方记成 0：不当作谁
    val shownRecent = recent.filter { !blocked.hides(it.peer.id.takeIf { id -> id > 0 }) && matches(it.peer) }
    val recentIds = shownRecent.mapTo(HashSet()) { it.peer.id }
    val shownFriends = friends.filter { it.id !in recentIds && !blocked.hides(it.id) && matches(it) }
    val typed = text.takeIf { USERNAME.matches(it) && friends.none { friend -> friend.username.equals(it, ignoreCase = true) } }
    return PmRecipients(typed, shownRecent, shownFriends)
}
