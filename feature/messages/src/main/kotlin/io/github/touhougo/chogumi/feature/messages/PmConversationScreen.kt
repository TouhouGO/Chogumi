package io.github.touhougo.chogumi.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.reader.ComposerSheet
import io.github.touhougo.chogumi.core.reader.ComposerTitle
import io.github.touhougo.chogumi.core.reader.ReplyFab
import io.github.touhougo.chogumi.core.reader.rememberBodyCopier
import io.github.touhougo.chogumi.core.reader.userTitleLink
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.PmMessage
import io.github.touhougo.chogumi.core.ui.common.LoginGate
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.AppDropdownMenu
import io.github.touhougo.chogumi.core.ui.composables.AppMenuItem
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SkeletonTextSample
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 短信会话页（[key]：一个会话，或写给一个人）：顶栏是对方（头像 + 名字，点了进对方主页）；下面整段会话从旧到新，
 * 打开时停在最新一封（列表倒排，新信、图片加载都不把底部顶走）。每条线开头一个主题标签，点标签回复那条线；右下「回复」FAB 回复最新那条线。
 * 回复面板是同一个编辑器（[ComposerSheet]）：主题可填可不填，填了就是在这个会话里开新主题；正文 ≤ 1000 字（网页的上限），
 * 不用人机验证。长按一封：复制、删除（先确认；只从自己的短信里删）。删的是最后一封，会话不在了，回到上一页。
 * 写给一个人而没找到会话时，页面是一句「没有找到之前的短信」，编辑器写的是第一封；发出去后页面就是网页跳去的那段会话。
 * 回到这一页时重读一次（对方回了信），没有下拉刷新：倒排的列表顶上是最早的信，下拉要先翻到最早。
 */
@Composable
fun PmConversationScreen(
    key: PmChatKey,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
) {
    val viewModel: PmConversationViewModel = koinViewModel { parametersOf(key) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    PmConversationPage(
        state = state,
        failures = viewModel.failures,
        composerFailures = viewModel.composer.failures,
        gate = rememberLoginGate(isLoggedIn, onLogin),
        onBack = onBack,
        onUserClick = onUserClick,
        onRetry = viewModel::retry,
        onReply = viewModel::reply,
        onErase = { message ->
            viewModel.erase(message) {
                // 删光了：会话不在了，回到上一页；人已经离开这一页（进了别的页）时不替人返回
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onBack()
            }
        },
        onSend = { draft, onSent -> viewModel.composer.send(draft, token = "") { onSent() } },
        onCloseComposer = viewModel.composer::close,
        onComposerChange = viewModel.composer::edit,
    )
}

/**
 * 会话页的画面（[PmConversationScreen] 接上 ViewModel）：写操作（回复、删除）都过 [gate]。
 *
 * @param onReply 打开回复面板，回复那条线（null = 最新那条线）。
 * @param onErase 确认后删掉这一封。
 * @param onSend 发出回复面板里写好的内容；发出去、会话重读完调 `onSent`。
 * @param onComposerChange 回复面板里的字变了（存草稿，关掉面板再打开还在）。
 */
@Composable
internal fun PmConversationPage(
    state: PmConversationUiState,
    failures: FailureNotices,
    composerFailures: FailureNotices,
    gate: LoginGate,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    onRetry: () -> Unit,
    onReply: (threadId: Int?) -> Unit,
    onErase: (PmMessage) -> Unit,
    onSend: (draft: ComposerDraft, onSent: () -> Unit) -> Unit,
    onCloseComposer: () -> Unit,
    onComposerChange: (ComposerDraft) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val copy = rememberBodyCopier()
    // 开着菜单的、要确认删除的那一封（按信 id）：放在列表之上，气泡滚出屏幕再回来也还在
    var menuFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var erasing by rememberSaveable { mutableStateOf<Int?>(null) }
    val chat = state.chat.valueOrNull()
    val conversation = chat?.conversation
    val peer = chat?.peer
    FailureNoticeEffect(failures, snackbar)

    PageScaffold(
        title = peer?.let(::displayNameOf).orEmpty(),
        onBack = onBack,
        titleLink = peer?.let { userTitleLink(it.avatarSmall) { onUserClick(userKeyOf(it.username, it.id)) } },
        titleLoading = state.chat is Load.Pending,
        floatingActionButton = {
            if (chat != null && (conversation == null || conversation.messages.isNotEmpty())) ReplyFab { gate.run { onReply(null) } }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (sectionStateOf(state.chat) { it?.conversation == null || it.conversation.messages.isEmpty() }) {
            SectionState.LOADING -> PmConversationSkeleton(modifier)
            SectionState.ERROR -> Box(modifier, contentAlignment = Alignment.Center) {
                state.chat.failureOrNull()?.let { RetryError(it, onRetry = onRetry, Modifier.fillMaxWidth(), compact = true) }
            }
            SectionState.EMPTY -> Box(modifier) {
                // 会话不在了（信已经删光），或写给一个人而没找到会话
                EmptyLine(stringResource(if (chat != null && conversation == null) R.string.pm_conversation_not_found else R.string.pm_conversation_empty))
            }
            SectionState.CONTENT -> if (conversation != null) {
                val rows = remember(conversation) { pmRowsOf(conversation).asReversed() }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = 8.dp, bottom = FabClearance),
                    modifier = modifier,
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is PmRow.ThreadLabel -> PmThreadLabel(row.thread, onReply = { gate.run { onReply(row.thread.id) } })
                            is PmRow.Bubble -> PmBubbleRow(
                                row = row,
                                peer = conversation.peer,
                                onPeerClick = { onUserClick(userKeyOf(conversation.peer.username, conversation.peer.id)) },
                                onUserClick = onUserClick,
                                onLongPress = { menuFor = row.message.id },
                            ) {
                                AppDropdownMenu(
                                    expanded = menuFor == row.message.id,
                                    onDismissRequest = { menuFor = null },
                                    groups = listOf(
                                        {
                                            AppMenuItem(stringResource(R.string.copy), onDismiss = { menuFor = null }, icon = R.drawable.content_copy_24) {
                                                copy(row.message.body)
                                            }
                                        },
                                        {
                                            AppMenuItem(stringResource(R.string.delete), onDismiss = { menuFor = null }, icon = R.drawable.delete_24) {
                                                erasing = row.message.id
                                            }
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    conversation?.messages?.firstOrNull { it.id == erasing }?.let { message ->
        ConfirmDialog(
            target = message,
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { target -> gate.run { onErase(target) } },
            onDismiss = { erasing = null },
            title = stringResource(R.string.pm_erase_confirm_title),
            destructive = true,
            icon = R.drawable.delete_24,
        )
    }

    val target = state.composer.target
    if (peer != null && target != null) {
        ComposerSheet(
            title = stringResource(if (target.threadId == null) R.string.pm_write_to else R.string.composer_reply_floor, displayNameOf(peer)),
            sending = state.composer.sending,
            sent = state.composer.sent,
            failures = composerFailures,
            onDismiss = onCloseComposer,
            onSend = gate.guard { draft: ComposerDraft, _: String ->
                // 发出后会话已经重读，新信在最下面：落到最新一封
                onSend(draft) { scope.launch { listState.scrollToItem(0) } }
            },
            initialTitle = state.composer.title,
            initialText = state.composer.text,
            onChange = onComposerChange,
            verify = false,
            quote = target.quote,
            maxLength = PM_MAX_LENGTH,
            titleField = ComposerTitle.OPTIONAL,
        )
    }
}

/** 整页骨架：几封交替的气泡，自己的、对方的各几行。 */
@Composable
private fun PmConversationSkeleton(modifier: Modifier) {
    // 与倒排的列表一样贴着底部排：内容到了，最新的几封落在骨架原来的位置
    Column(modifier.clipToBounds().padding(bottom = FabClearance), verticalArrangement = Arrangement.Bottom) {
        SKELETON_BUBBLES.forEach { (mine, sample) -> PmBubbleSkeleton(mine, sample) }
    }
}

/** 骨架里的几封：谁发的、样例有几个字（样例字只用来排出真气泡的高度，不显示）。 */
private val SKELETON_BUBBLES = listOf(false to 20, true to 8, false to 44, true to 24, false to 12)
    .map { (mine, length) -> mine to SkeletonTextSample.repeat(length) }

/** 一封短信正文的字数上限（网页写信框的上限）。 */
private const val PM_MAX_LENGTH = 1000
