package io.github.touhougo.chogumi.core.reader

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDrafts
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.reactionsOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LoginGate
import kotlinx.coroutines.CoroutineScope

/**
 * 回复楼层的编辑器（话题与对象的评论区共用，只此一份构造）：写给话题本身（[ReplyTarget.Topic]）或某一层楼，
 * 草稿按回复的那层楼存。发出后经 [reload] 重新拉楼层，等它落定才收起面板（新楼已在列表里，好落过去）；
 * 超时、断连不知道发没发出去时也重拉一次。
 *
 * @param send 提交：正文（含引用块或 @）、挂在哪层主楼下（0 = 新的一条）、人机验证令牌 → 新楼的 id。
 */
fun replyComposer(
    scope: CoroutineScope,
    state: () -> ComposerState<ReplyTarget>,
    update: ((ComposerState<ReplyTarget>) -> ComposerState<ReplyTarget>) -> Unit,
    send: suspend (content: String, replyTo: Int, token: String) -> Outcome<Int>,
    reload: suspend () -> Unit,
    drafts: ComposerDrafts<ReplyTarget>,
) = ComposerActions<ReplyTarget, Int>(
    scope = scope,
    state = state,
    update = update,
    submit = { target, draft, token -> send(target.contentOf(draft.text), target.replyTo, token) },
    afterSend = { reload() },
    afterUnsure = { reload() },
    drafts = drafts,
)

/**
 * [replyComposer] 的编辑器面板：标题是写给谁（[title]），顶上是被回复楼的引用条，发送前人机验证；能贴贴的评论区可以加投票；
 * 发出后 [land] 落到新楼上（参数是新楼的 id）。面板没开（[ComposerState.target] 为 null）时什么都不画。
 */
@Composable
fun ReplyComposerSheet(
    state: ComposerState<ReplyTarget>,
    composer: ComposerActions<ReplyTarget, Int>,
    surface: CommentSurface,
    gate: LoginGate,
    land: (postId: Int) -> Unit,
) {
    val target = state.target ?: return
    ComposerSheet(
        title = target.title(surface),
        sending = state.sending,
        sent = state.sent,
        failures = composer.failures,
        onDismiss = composer::close,
        onSend = gate.guard { draft: ComposerDraft, token: String -> composer.send(draft, token, land) },
        initialText = state.text,
        onChange = composer::edit,
        quote = target.preview,
        pollPalette = reactionsOf(surface),
    )
}

/**
 * 「回复」FAB（话题、对象的评论区所在的页面共用）：写新的一条，打开 [replyComposer] 的面板。
 * 只有图标的 M3 FAB（fabs.md 最小一档 56dp），尺寸固定，不随滚动变形。只放进 Scaffold 的 floatingActionButton 槽位。
 */
@Composable
fun ReplyFab(onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick) {
        Icon(painterResource(R.drawable.reply_24), contentDescription = stringResource(R.string.reply))
    }
}
