package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.ReportTarget
import io.github.touhougo.chogumi.core.model.UserModeration

/**
 * 页面 ViewModel 单测里的报告、加好友、解除好友、绝交端：测的不是这几件事的页面只需要把它交给构造参数，一旦被调用就是测试写错了。
 * 报告面板与写操作本身的行为见 core:ui 的 `ModerationControllerTest`。
 */
class StillUserModeration : UserModeration {
    override suspend fun report(target: ReportTarget, reason: Int, comment: String?): Outcome<String> = error("本测试不报告")

    override suspend fun addFriend(username: String): Outcome<Unit> = error("本测试不加好友")

    override suspend fun removeFriend(username: String): Outcome<Unit> = error("本测试不解除好友")

    override suspend fun addUserToBlocklist(username: String): Outcome<Unit> = error("本测试不绝交")
}
