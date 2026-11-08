package io.github.touhougo.chogumi.core.ui.moderation

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.ReportTarget
import io.github.touhougo.chogumi.core.model.UserModeration
import kotlinx.coroutines.CompletableDeferred

/** 记下发出的写操作；[reportReply] 与 [writeResult] 决定服务端怎么答。 */
internal class FakeModeration : UserModeration {
    val reports = mutableListOf<Triple<ReportTarget, Int, String?>>()
    val friends = mutableListOf<String>()
    val unfriended = mutableListOf<String>()
    val blocked = mutableListOf<String>()
    var reportReply: CompletableDeferred<Outcome<String>> = CompletableDeferred(Outcome.Success("感谢"))
    var writeResult: Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun report(target: ReportTarget, reason: Int, comment: String?): Outcome<String> {
        reports += Triple(target, reason, comment)
        return reportReply.await()
    }

    override suspend fun addFriend(username: String): Outcome<Unit> = writeResult.also { friends += username }

    override suspend fun removeFriend(username: String): Outcome<Unit> = writeResult.also { unfriended += username }

    override suspend fun addUserToBlocklist(username: String): Outcome<Unit> = writeResult.also { blocked += username }
}
