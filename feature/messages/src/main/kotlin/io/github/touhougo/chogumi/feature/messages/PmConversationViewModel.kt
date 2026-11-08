package io.github.touhougo.chogumi.feature.messages

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.bbcode.excerptOf
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.DraftRepository
import io.github.touhougo.chogumi.core.data.repository.PmRepository
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.repository.postDrafts
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.PmConversation
import io.github.touhougo.chogumi.core.model.PmDraft
import io.github.touhougo.chogumi.core.model.PmMessage
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.reader.ComposerActions
import io.github.touhougo.chogumi.core.reader.ComposerState
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 会话页开的是哪一段：一个已知的会话，或写给一个人。 */
sealed interface PmChatKey {
    /** 会话 [id]；[compose] 为 true 时读到就弹出回复面板（写信页从最近联系人选进来）。 */
    data class Conversation(val id: Int, val compose: Boolean = false) : PmChatKey

    /** 写给 [username]：找得到和该用户的会话就是那段会话，找不到是第一封；读到就弹出编辑器。 */
    data class Peer(val username: String) : PmChatKey
}

/**
 * 会话页显示的：对方，与和该用户的会话（null = 没找到会话，要写的是第一封）。
 */
data class PmChat(val peer: UserBo, val conversation: PmConversation?)

/**
 * 写给谁：收件人（对方 uid）、回复哪条线（[threadId]，null = 一封新信：第一封，或会话里还没有线），
 * 编辑器顶上的引用条（主题名 + 这条线最近一封的开头）。
 */
data class PmReplyTarget(val receiverId: Int, val threadId: Int?, val quote: QuoteHeader?) {
    /** 写好的内容 → 要发的信：填了主题、又有线可回时是在这个会话里开一个新主题，否则回复这条线。 */
    fun draftOf(draft: ComposerDraft) = PmDraft(
        receiverId = receiverId,
        body = draft.text,
        title = draft.title,
        replyTo = threadId,
        newTopic = threadId != null && draft.title.isNotBlank(),
    )

    /** 草稿的键：收件人与线（新信是 0），同一个人的同一条线一份草稿。 */
    val draftKey: String get() = "$receiverId/${threadId ?: 0}"
}

/**
 * 会话页的状态：对方与会话（null = 会话不在了：信已经删光）与编辑器面板。
 */
data class PmConversationUiState(
    val chat: Load<PmChat?> = Load.Pending,
    val composer: ComposerState<PmReplyTarget> = ComposerState(),
)

/**
 * 一个短信会话，或写给一个人（[PmChatKey]）。整段会话一页读全（网页打开即算读过）；写给一个人时先按用户名查到对方，
 * 再找和该用户的会话（[PmRepository.findConversation]）。回复与写第一封走同一个编辑器面板，草稿按收件人与线存
 * （[PmReplyTarget.draftKey]）；发出后重读会话（第一封发出后就是网页跳去的那段会话），新信已经在里面才收起面板；
 * 删信后用删完重读的那份会话替换。
 */
class PmConversationViewModel(
    private val key: PmChatKey,
    private val repository: PmRepository,
    private val users: UserRepository,
    drafts: DraftRepository,
    clock: AppClock,
) : StateViewModel<PmConversationUiState>(PmConversationUiState()) {

    /** 会话 id：一开始就知道（[PmChatKey.Conversation]），或按人找到会话后才知道（第一封发出后重读即找到）。 */
    private var conversationId: Int? = (key as? PmChatKey.Conversation)?.id

    /** 本页的失败提示：删信没做成、回到页面时重读失败（内容还在屏幕上）。 */
    val failures = FailureNotices()

    /** 同一会话的读与删除逐笔执行；第二笔会等待，不会与第一笔的确认重读交错。 */
    private val conversationQueue = Mutex()

    /** 会话已经删空后只通知页面离开一次。 */
    private var goneNotified = false

    /** 编辑器面板：回复一条线、开新主题或写第一封（标题可选），不要人机验证（网页发信没有）；发出去或不知道发没发出去都重读会话。 */
    val composer = ComposerActions<PmReplyTarget, Int>(
        scope = viewModelScope,
        state = { currentState.composer },
        update = { transform -> updateState { it.copy(composer = transform(it.composer)) } },
        submit = { target, draft, _ -> repository.send(target.draftOf(draft), conversationId) },
        // 第一封发出后，网页跳去的会话已经记在本机（[PmRepository.send]），重读时按人就找得到
        afterSend = { reloadQueuedAndWait() },
        afterUnsure = { reloadQueuedAndWait() },
        drafts = drafts.postDrafts(PostDraftKind.PM, clock, PmReplyTarget::draftKey),
    )

    init {
        load(failures = null)
        if (key is PmChatKey.Peer || (key is PmChatKey.Conversation && key.compose)) {
            viewModelScope.launch {
                joinSection(SECTION)
                reply()
            }
        }
    }

    /** 整页报错后的重试。 */
    fun retry() = load(failures = null)

    /** 回到这一页时重读（对方回了信、在网页上删了信）：第一次进页（还没读到）时什么都不做；已显示的留着，失败只发提示。 */
    fun refresh() {
        if (currentState.chat is Load.Ready) {
            viewModelScope.launch {
                reloadQueuedAndWait()
            }
        }
    }

    /**
     * 打开编辑器面板：有会话时回复 [threadId] 那条线（null 回复网页默认的那条，即最新那条线），没有会话时写第一封。
     * 还没读到、会话不在了时什么都不做。
     */
    fun reply(threadId: Int? = null) {
        val chat = currentState.chat.valueOrNull() ?: return
        val conversation = chat.conversation
        composer.open(
            if (conversation == null) PmReplyTarget(chat.peer.id, threadId = null, quote = null)
            else replyTargetOf(conversation, threadId ?: conversation.replyThreadId),
        )
    }

    /**
     * 从自己的短信里删掉 [message]（对方那边看不看得到，网页没有说法）。做成后会话换成删完重读的那份，
     * 已在跑的重读结束后才删，后来的重读也等删除确认完；删的是最后一封时会话不在了，只调一次 [onGone]。
     */
    fun erase(message: PmMessage, onGone: () -> Unit) {
        val erase = repository.prepareErase(requireNotNull(conversationId), message.id)
        viewModelScope.launch {
            conversationQueue.withLock {
                when (val result = erase()) {
                    is Outcome.Success -> {
                        cancelSection(SECTION)
                        val peer = currentState.chat.valueOrNull()?.peer
                        updateState { it.copy(chat = Load.Ready(result.value?.let { conversation -> PmChat(peer ?: conversation.peer, conversation) })) }
                        if (result.value == null && !goneNotified) {
                            goneNotified = true
                            onGone()
                        }
                    }
                    is Outcome.Failed -> failures.post(result.failure, retry = { erase(message, onGone) })
                }
            }
        }
    }

    private fun load(failures: FailureNotices?) =
        launchSection(SECTION, failures, { loadOnce { read() } }, { it.chat }) { copy(chat = it) }

    /**
     * 读这一页：知道会话 id 就读那段会话（不在了为 null）；写给一个人时先查到对方，再找和该用户的会话。
     * 对方取查到的那份资料（带用户名，点了进主页），会话里只有 uid。
     */
    private suspend fun read(): PmChat? {
        val known = currentState.chat.valueOrNull()?.peer ?: (key as? PmChatKey.Peer)?.let { users.getUser(it.username).settled().orThrow() }
        val id = conversationId
        val conversation = if (id != null) repository.conversation(id).orThrow() else known?.let { repository.findConversation(it).orThrow() }
        conversation?.let { conversationId = it.id }
        val peer = known ?: conversation?.peer ?: return null
        return PmChat(peer, conversation)
    }

    /** 所有已显示会话的重读只从这里排队；初始加载与整页失败重试还没有可与删除交错的内容。 */
    private suspend fun reloadQueuedAndWait() = conversationQueue.withLock {
        load(failures)
        joinSection(SECTION)
    }

    private companion object {
        const val SECTION = "conversation"
    }
}

private fun <T> Outcome<T>.orThrow(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failed -> throw FailureException(failure)
}

/** 回复 [threadId] 那条线的对象：引用条是主题名与这条线最近一封的开头；没有线时没有引用条。 */
internal fun replyTargetOf(conversation: PmConversation, threadId: Int?): PmReplyTarget {
    val thread = conversation.threads.firstOrNull { it.id == threadId }
    val latest = conversation.messages.lastOrNull { it.threadId == threadId }
    val quote = thread?.let { QuoteHeader(author = it.title, excerpt = latest?.let { message -> excerptOf(message.body) }.orEmpty()) }
    return PmReplyTarget(conversation.peer.id, threadId, quote)
}
