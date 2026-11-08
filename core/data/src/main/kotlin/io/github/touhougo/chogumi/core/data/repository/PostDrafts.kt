package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDrafts
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.model.TopicReplyDraft
import io.github.touhougo.chogumi.core.model.TopicType

/** 新帖面板（发动态、小组发帖）的草稿：存在 [kind] 下 [key] 那一行，标题与正文一起。 */
fun DraftRepository.postDrafts(kind: PostDraftKind, key: String, clock: AppClock): ComposerDrafts<Unit> =
    postDrafts(kind, clock) { _: Unit -> key }

/** 同一种草稿的对象由 [keyFor] 换成种类内的键；读取、待存与删除都由仓库的唯一账号绑定处理。 */
fun <T> DraftRepository.postDrafts(kind: PostDraftKind, clock: AppClock, keyFor: (T) -> String): ComposerDrafts<T> =
    composerDrafts(
        kind = "post/${kind.name}",
        keyFor = keyFor,
        load = { owner, target -> getPost(owner, kind, keyFor(target))?.let { ComposerDraft(it.title, it.text) } },
        save = { owner, target, draft -> savePost(owner, kind, keyFor(target), draft.title, draft.text, now = clock.nowMillis()) },
        discard = { owner, target -> deletePost(owner, kind, keyFor(target)) },
    )

/** 话题回复沿用同一个草稿绑定与待存实现；引用等显示信息由调用方提供，账号不在页面推导。 */
fun <T> DraftRepository.replyDrafts(
    type: TopicType,
    topicId: Int,
    clock: AppClock,
    postIdOf: (T) -> Int,
    draftOf: (T, String) -> TopicReplyDraft,
): ComposerDrafts<T> = composerDrafts(
    kind = "topic/${type.name}/$topicId",
    keyFor = postIdOf,
    load = { owner, target -> get(owner, type, topicId, postIdOf(target))?.let { ComposerDraft("", it.text) } },
    save = { owner, target, draft -> save(owner, draftOf(target, draft.text), now = clock.nowMillis()) },
    discard = { owner, target -> delete(owner, type, topicId, postIdOf(target)) },
)
