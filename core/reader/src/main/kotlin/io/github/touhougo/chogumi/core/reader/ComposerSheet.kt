package io.github.touhougo.chogumi.core.reader

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeTag
import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import io.github.touhougo.chogumi.core.common.bbcode.TextEdit
import io.github.touhougo.chogumi.core.common.bbcode.smileyTextOf
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.reader.reactionpoll.ReactionPollDraft
import io.github.touhougo.chogumi.core.reader.reactionpoll.ReactionPollDraftSaver
import io.github.touhougo.chogumi.core.reader.reactionpoll.ReactionPollEditor
import io.github.touhougo.chogumi.core.reader.reactionpoll.ReactionPollPlugin
import io.github.touhougo.chogumi.core.reader.reactionpoll.joinReactionPoll
import io.github.touhougo.chogumi.core.reader.reactionpoll.splitReactionPoll
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.ContentGrid
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.TipHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 编辑器面板（回复话题、改自己的楼、发动态、小组发帖共用）：标题行（标题 + 右边「发送」）→ 被回复楼的引用条 →（发帖时）帖子标题框 →
 * 正文框 → 工具栏 → 表情面板（点工具栏的「表情」开合）。纯文本框，写的就是 BBCode，与网页回复框同一套标记（[BBCodeTag]）。
 *
 * 工具栏（[ComposerToolbar]）一行放下：表情、四个分组（文字样式、字号、插入、段落）、预览、清空。点一个分组，这一行原地换成
 * 这一组的选项；选一项或再点组按钮就收回。图片、链接先在对话框里填地址（[InsertDialog]）。预览把正文框原地换成阅读器的
 * 渲染（[BBCodeText]），框的大小不变。
 *
 * 表情面板占的是键盘的位置（[SmileyArea]）：高度取这台设备键盘的实际高度，键盘与表情来回切换时面板总高不变。
 * 面板停稳后才弹出键盘、收起前先收键盘（[PageModalSheet] 的 inputFocus），面板的进退场不藏在键盘后面。
 *
 * 「发送」先弹人机验证（[TurnstileDialog]），拿到令牌才交给 [onSend]（[verify] 为 false 时直接交，令牌是空串）；
 * 正在发时按钮转圈、不能再点，标题、正文、投票与工具栏也都停住：发出去的是按下那一刻的内容，发成功后面板连同草稿一起收走，
 * 这期间再改的字没有地方留；发出后调用方把 [sent] 置 true，面板滑下去，滑完回调 [onDismiss]。失败时面板和正文都留着，
 * 原因（[failures]）画在面板里的提示条上。没有关闭按钮：下拉、点遮罩、返回键都能关（[PageModalSheet]）。
 * 正文随面板保存，转屏、进子页再回来都还在；面板关掉之后还要留的（草稿）由调用方经 [onChange] 存。
 *
 * @param initialTitle 打开时标题框里的内容（接着写的草稿），只在有标题框（[titleField]）时有用。
 * @param initialText 打开时框里的内容（接着写的草稿、要改的楼的正文）。
 * @param onChange 标题或正文每次变了都报（存草稿用），打开时的 [initialTitle]、[initialText] 不报。没有默认值：漏接时
 *   编译不过，面板关掉写的字就丢了；不存草稿的面板（改自己的楼）也交它的 `edit`，存不存由它的草稿库决定。
 * @param verify 发送前要不要人机验证：新建内容（回复、发动态）要，改自己的楼不要。
 * @param quote 被回复楼的引用条（[ReplyTarget.preview]）；null 时不画。
 * @param maxLength 正文字数上限（动态 380）：超出时计数变红、不能发送；null 表示不限，也不显示计数。
 * @param titleField 正文上面的单行标题框（[ComposerTitle]）：发帖必填，打开时先进它，标题和正文都写了才能发送；
 *   短信可选，打开时先进正文，不填标题也能发。
 * @param pollPalette 这里能贴的表情（[reactionsOf][io.github.touhougo.chogumi.core.model.reactionsOf]）；不空时「插入」里多一个「投票」，
 *   在正文框下面加一张投票卡（[ReactionPollEditor]），发出去的是正文后面接上投票块（[joinReactionPoll]）。打开时正文里
 *   认得出的投票块读回成投票卡（[splitReactionPoll]），草稿、改自己的楼都一样。空的地方（没有贴贴）没有投票。
 */
@Composable
fun ComposerSheet(
    title: String,
    sending: Boolean,
    sent: Boolean,
    failures: FailureNotices,
    onDismiss: () -> Unit,
    onSend: Gated<(draft: ComposerDraft, token: String) -> Unit>,
    initialTitle: String = "",
    initialText: String = "",
    onChange: (ComposerDraft) -> Unit,
    verify: Boolean = true,
    quote: QuoteHeader? = null,
    maxLength: Int? = null,
    titleField: ComposerTitle = ComposerTitle.NONE,
    pollPalette: List<Int> = emptyList(),
) {
    val opening = remember { splitReactionPoll(initialText, pollPalette) }
    val subject = rememberTextFieldState(initialTitle)
    val text = rememberTextFieldState(opening.body)
    var poll by rememberSaveable(stateSaver = ReactionPollDraftSaver) { mutableStateOf(opening.poll) }
    val reportChange by rememberUpdatedState(onChange)
    val composed = { joinReactionPoll(text.text.toString(), poll) }
    val withTitle = titleField != ComposerTitle.NONE
    val titleFirst = titleField == ComposerTitle.REQUIRED
    val typed = { ComposerDraft(title = if (withTitle) subject.text.toString() else "", text = composed()) }
    // 只报用户的改动：第一拍是打开时（或转屏恢复）的内容，跳过
    LaunchedEffect(text, subject) { snapshotFlow(typed).drop(1).collect { reportChange(it) } }
    val written = { typed().let { it.copy(title = it.title.trim()) } }
    var smileys by rememberSaveable { mutableStateOf(false) }
    var previewing by rememberSaveable { mutableStateOf(false) }
    var openGroup by rememberSaveable { mutableStateOf<ToolGroup?>(null) }
    var asking by rememberSaveable { mutableStateOf<InsertKind?>(null) }
    var askingText by rememberSaveable { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val bodyFocus = remember(focus, titleFirst) { if (titleFirst) FocusRequester() else focus }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    FailureNoticeEffect(failures, snackbar)

    // 回到打字：正文框拿焦点、弹出键盘（表情面板等键盘升起时让位，见 SmileyArea）
    val backToTyping = {
        bodyFocus.requestFocus()
        keyboard?.show()
    }
    val length = composed().length
    val tooLong = maxLength != null && length > maxLength
    val counter: (@Composable () -> Unit)? = maxLength?.let { limit ->
        @Composable {
            Text(stringResource(R.string.text_length_count, length, limit), textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
    }
    PageModalSheet(onDismiss = onDismiss, fullHeight = false, hideRequested = sent, growsWithContent = true, inputFocus = focus, snackbar = snackbar) {
        // 表情的位置在间距之外：它出现与否都不能多出一道 8dp 的间距，否则切到表情时整块面板先跳高一截
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetTitleRow(title) {
                    BusyButton(
                        onClick = { if (verify) verifying = true else onSend.action(written(), "") },
                        enabled = (text.text.isNotBlank() || poll != null) && poll?.isComplete != false &&
                            (!titleFirst || subject.text.isNotBlank()) && !tooLong,
                        busy = sending,
                        busyLabel = stringResource(R.string.loading),
                    ) {
                        Icon(painterResource(R.drawable.send_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.composer_send))
                    }
                }
                quote?.let { QuoteBar(it, onClick = null, modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding)) }
                if (withTitle) {
                    FormTextField(
                        state = subject,
                        placeholder = stringResource(if (titleFirst) R.string.composer_topic_title else R.string.composer_title_optional),
                        textStyle = MaterialTheme.typography.titleMedium,
                        enabled = !sending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ChogumiDimens.ScreenPadding)
                            .then(if (titleFirst) Modifier.focusRequester(focus) else Modifier),
                    )
                }
                // 预览盖在正文框（与投票卡）上、取它们的大小：框一直在量，预览开合时面板不变高也不变矮
                Box(Modifier.fillMaxWidth().padding(horizontal = ChogumiDimens.ScreenPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormTextField(
                            state = text,
                            placeholder = stringResource(if (titleFirst) R.string.composer_topic_body else R.string.composer_placeholder),
                            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = FIELD_MIN_LINES, maxHeightInLines = FIELD_MAX_LINES),
                            isError = tooLong,
                            supportingText = counter,
                            enabled = !sending,
                            modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus),
                        )
                        poll?.let { draft -> ReactionPollEditor(draft, pollPalette, onChange = { poll = it }, enabled = !sending) }
                    }
                    if (previewing) {
                        ComposerPreview(
                            source = composed(),
                            pollPalette = pollPalette,
                            onClick = {
                                previewing = false
                                backToTyping()
                            },
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                ComposerToolbar(
                    smileysOpen = smileys,
                    previewing = previewing,
                    sending = sending,
                    openGroup = openGroup,
                    onOpenGroup = { openGroup = it },
                    polls = pollPalette.isNotEmpty(),
                    onTool = { action ->
                        openGroup = null
                        when (action) {
                            is ToolAction.Wrap -> text.applyEdit { wrapWith(action.tag) }
                            is ToolAction.Ask -> {
                                askingText = text.current().selected
                                asking = action.kind
                            }
                            // 一楼只有一份贴贴，只能有一个投票：已经有了就不再加
                            ToolAction.Poll -> if (poll == null) poll = ReactionPollDraft.new(pollPalette)
                        }
                    },
                    onToggleSmileys = {
                        openGroup = null
                        if (smileys) {
                            smileys = false
                            backToTyping()
                        } else {
                            // 表情面板占键盘的位置：开面板时收起键盘，光标与选区留着，点表情照样插在光标处
                            smileys = true
                            keyboard?.hide()
                        }
                    },
                    onTogglePreview = {
                        openGroup = null
                        if (previewing) {
                            previewing = false
                            backToTyping()
                        } else {
                            previewing = true
                            smileys = false
                            focusManager.clearFocus()
                        }
                    },
                    onClear = { text.clearText() },
                )
            }
            SmileyArea(
                open = smileys,
                onKeyboardRising = { smileys = false },
            ) {
                SmileyPanel(onPick = { code -> if (!sending) text.applyEdit { insert(smileyTextOf(code)) } })
            }
        }
    }

    asking?.let { kind ->
        InsertDialog(
            kind = kind,
            initialText = askingText,
            onDismiss = { asking = null },
            onInsert = { url, label ->
                asking = null
                text.applyEdit { if (kind == InsertKind.LINK) link(url, label) else image(url) }
                backToTyping()
            },
        )
    }
    if (verifying) {
        TurnstileDialog(
            onDismiss = { verifying = false },
            onVerified = { token ->
                verifying = false
                onSend.action(written(), token)
            },
        )
    }
}

/** 编辑器正文上面的标题框：没有；必填（小组发帖，打开时先进它）；可选（短信：会话里填了就是开一个新主题）。 */
enum class ComposerTitle { NONE, REQUIRED, OPTIONAL }

/** 工具栏上的一个分组：组按钮的图标与读屏名，点开后这一行换成的选项。 */
private enum class ToolGroup(@DrawableRes val icon: Int, @StringRes val label: Int, val tools: List<Tool>) {
    STYLE(
        R.drawable.text_format_24,
        R.string.composer_style,
        listOf(
            Tool(ToolFace.Icon(R.drawable.format_bold_24), R.string.composer_bold, ToolAction.Wrap(BBCodeTag.BOLD)),
            Tool(ToolFace.Icon(R.drawable.format_italic_24), R.string.composer_italic, ToolAction.Wrap(BBCodeTag.ITALIC)),
            Tool(ToolFace.Icon(R.drawable.format_underlined_24), R.string.composer_underline, ToolAction.Wrap(BBCodeTag.UNDERLINE)),
            Tool(ToolFace.Icon(R.drawable.format_strikethrough_24), R.string.composer_strike, ToolAction.Wrap(BBCodeTag.STRIKE)),
        ),
    ),
    SIZE(
        R.drawable.format_size_24,
        R.string.composer_size,
        listOf(
            Tool(ToolFace.Letter(R.string.composer_size_large, LetterSize.LARGE), R.string.composer_size_large_label, ToolAction.Wrap(BBCodeTag.SIZE_LARGE)),
            Tool(ToolFace.Letter(R.string.composer_size_medium, LetterSize.MEDIUM), R.string.composer_size_medium_label, ToolAction.Wrap(BBCodeTag.SIZE_MEDIUM)),
            Tool(ToolFace.Letter(R.string.composer_size_small, LetterSize.SMALL), R.string.composer_size_small_label, ToolAction.Wrap(BBCodeTag.SIZE_SMALL)),
        ),
    ),
    INSERT(
        R.drawable.add_24,
        R.string.composer_insert,
        listOf(
            Tool(ToolFace.Icon(R.drawable.image_24), R.string.composer_image, ToolAction.Ask(InsertKind.IMAGE)),
            Tool(ToolFace.Icon(R.drawable.link_24), R.string.composer_link, ToolAction.Ask(InsertKind.LINK)),
            Tool(ToolFace.Icon(R.drawable.bar_chart_24), R.string.composer_poll, ToolAction.Poll),
        ),
    ),
    BLOCK(
        R.drawable.segment_24,
        R.string.composer_block,
        listOf(
            Tool(ToolFace.Icon(R.drawable.format_quote_24), R.string.composer_quote, ToolAction.Wrap(BBCodeTag.QUOTE)),
            Tool(ToolFace.Icon(R.drawable.visibility_off_24), R.string.composer_mask, ToolAction.Wrap(BBCodeTag.MASK)),
            Tool(ToolFace.Icon(R.drawable.code_24), R.string.composer_code, ToolAction.Wrap(BBCodeTag.CODE)),
        ),
    ),
}

/** 分组里的一项：长什么样、读屏名、点了做什么。 */
private class Tool(val face: ToolFace, @StringRes val label: Int, val action: ToolAction)

/** 选项的样子：图标，或字号那样的一个字（按它代表的大小画）。 */
private sealed interface ToolFace {
    class Icon(@DrawableRes val res: Int) : ToolFace

    class Letter(@StringRes val res: Int, val size: LetterSize) : ToolFace
}

/** 字号选项那个字画多大：大、中、小三档各取一个字体角色，一眼看出哪个大。 */
private enum class LetterSize { LARGE, MEDIUM, SMALL }

/** 选项做的事：包上标记，先问地址再插入，或在正文下面加投票卡（只在能贴贴的地方有，见 ComposerSheet 的 pollPalette）。 */
private sealed interface ToolAction {
    class Wrap(val tag: BBCodeTag) : ToolAction

    class Ask(val kind: InsertKind) : ToolAction

    data object Poll : ToolAction
}

/** 要先填地址的插入。 */
private enum class InsertKind { LINK, IMAGE }

/**
 * 工具栏：平时一行七个按钮——表情（最常用，放最前）、四个分组、预览、清空，铺满一行；点开一个分组，这一行换成
 * 「高亮的组按钮 + 这一组的选项」，选一项或再点组按钮收回。预览时只有预览按钮可点。宽度不够一行时横向滑动。
 */
@Composable
private fun ComposerToolbar(
    smileysOpen: Boolean,
    previewing: Boolean,
    sending: Boolean,
    openGroup: ToolGroup?,
    onOpenGroup: (ToolGroup?) -> Unit,
    polls: Boolean,
    onTool: (ToolAction) -> Unit,
    onToggleSmileys: () -> Unit,
    onTogglePreview: () -> Unit,
    onClear: () -> Unit,
) {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = openGroup,
        transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
        label = "composerToolbar",
    ) { group ->
        TipHost(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .widthIn(min = maxWidth)
                        .padding(horizontal = ToolbarPadding),
                    horizontalArrangement = if (group == null) Arrangement.SpaceBetween else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (group == null) {
                        val smileysLabel = stringResource(R.string.composer_smileys)
                        IconToggleButton(checked = smileysOpen, onCheckedChange = { onToggleSmileys() }, enabled = !previewing && !sending, modifier = Modifier.longPressTip(smileysLabel)) {
                            Icon(
                                painterResource(if (smileysOpen) R.drawable.mood_filled_24 else R.drawable.mood_24),
                                contentDescription = smileysLabel,
                            )
                        }
                        ToolGroup.entries.forEach { entry ->
                            val label = stringResource(entry.label)
                            IconButton(onClick = { onOpenGroup(entry) }, enabled = !previewing && !sending, modifier = Modifier.longPressTip(label)) {
                                Icon(painterResource(entry.icon), contentDescription = label)
                            }
                        }
                        val previewLabel = stringResource(R.string.composer_preview)
                        IconToggleButton(checked = previewing, onCheckedChange = { onTogglePreview() }, enabled = !sending, modifier = Modifier.longPressTip(previewLabel)) {
                            Icon(
                                painterResource(if (previewing) R.drawable.preview_filled_24 else R.drawable.preview_24),
                                contentDescription = previewLabel,
                            )
                        }
                        val clearLabel = stringResource(R.string.composer_clear)
                        IconButton(onClick = onClear, enabled = !previewing && !sending, modifier = Modifier.longPressTip(clearLabel)) {
                            Icon(painterResource(R.drawable.delete_24), contentDescription = clearLabel)
                        }
                    } else {
                        val groupLabel = stringResource(group.label)
                        FilledTonalIconToggleButton(checked = true, onCheckedChange = { onOpenGroup(null) }, enabled = !sending, modifier = Modifier.longPressTip(groupLabel)) {
                            Icon(painterResource(group.icon), contentDescription = groupLabel)
                        }
                        group.tools.filter { polls || it.action != ToolAction.Poll }.forEach { tool ->
                            IconButton(onClick = { onTool(tool.action) }, enabled = !sending, modifier = Modifier.longPressTip(stringResource(tool.label))) { ToolFaceOf(tool) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolFaceOf(tool: Tool) {
    val label = stringResource(tool.label)
    when (val face = tool.face) {
        is ToolFace.Icon -> Icon(painterResource(face.res), contentDescription = label)
        is ToolFace.Letter -> Text(
            text = stringResource(face.res),
            style = when (face.size) {
                LetterSize.LARGE -> MaterialTheme.typography.titleLarge
                LetterSize.MEDIUM -> MaterialTheme.typography.titleMedium
                LetterSize.SMALL -> MaterialTheme.typography.labelMedium
            },
            modifier = Modifier.clearAndSetSemantics { contentDescription = label },
        )
    }
}

/**
 * 预览：正文按阅读器的同一套渲染（[BBCodeText]，楼层正文的字体角色）画在与正文框同形同色的底上，内容多时在里面滚。
 * 点空白处回到编辑（遮罩、链接这些自己能点的地方照常响应）。投票块画成读者看到的投票卡（还没有票，不能投），[pollPalette] 为空时照常是列表。
 */
@Composable
private fun ComposerPreview(source: String, pollPalette: List<Int>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ast = remember(source) { BBCodeParser.parse(source) }
    val pollPlugin = remember(pollPalette) { pollPalette.takeIf { it.isNotEmpty() }?.let { ReactionPollPlugin(it, emptyList(), onVote = null) } }
    PressArea(onClick = onClick, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)) {
            CompositionLocalProvider(LocalBBCodeBlockPlugin provides pollPlugin) {
                BBCodeText(
                    ast = ast,
                    style = ReaderTypography.article,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(PreviewPadding),
                )
            }
        }
    }
}

/**
 * 插入图片或链接前先问地址（M3 基础对话框，同网页回复框点图片、链接时弹的询问）：链接填「地址」与「文字」（打开时预填
 * 选中的文字），图片只填「地址」。地址空着不能插入；键盘上的完成键同「插入」。
 */
@Composable
private fun InsertDialog(kind: InsertKind, initialText: String, onDismiss: () -> Unit, onInsert: (url: String, text: String) -> Unit) {
    val url = rememberTextFieldState()
    val label = rememberTextFieldState(initialText)
    val focus = remember { FocusRequester() }
    val canInsert = url.text.isNotBlank()
    val insert = { if (canInsert) onInsert(url.text.toString(), label.text.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (kind == InsertKind.LINK) R.string.composer_insert_link else R.string.composer_insert_image)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormTextField(
                    state = url,
                    label = stringResource(R.string.composer_url),
                    inDialog = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = if (kind == InsertKind.LINK) ImeAction.Next else ImeAction.Done,
                    ),
                    onKeyboardAction = if (kind == InsertKind.LINK) null else KeyboardActionHandler { insert() },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (kind == InsertKind.LINK) {
                    FormTextField(
                        state = label,
                        label = stringResource(R.string.composer_link_text),
                        inDialog = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        onKeyboardAction = KeyboardActionHandler { insert() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = insert, enabled = canInsert) { Text(stringResource(R.string.composer_insert)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    LaunchedEffect(focus) { focus.requestFocus() }
}

/**
 * 表情面板的位置：占键盘的那一块。面板按键盘高度（[WindowInsets.ime] 最近一次停稳时的高度，从屏幕底边算；还没量到时
 * 用 [SmileyPanelFallbackHeight]）量，露出的高度是「键盘高度 − 面板底边已经让出的（导航栏与键盘里大的那个）」：
 * 键盘落下、表情位置升起时两者此消彼长，面板顶沿不动；键盘有没有升降动画都一样（没有动画就是同一帧换过来）。
 *
 * 任何一帧只露出一方：键盘还没完全落下时，这一块只画面板底色，键盘落尽才淡入表情；键盘开始升起（点了正文框、或点表情
 * 按钮回到打字）的那一刻表情就关掉（[onKeyboardRising]），这一块空着跟着键盘逐帧让出。只有表情开着时升起的键盘才占用
 * 这一块；平常弹出键盘（打开面板、预览后回到编辑）与这里无关。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmileyArea(open: Boolean, onKeyboardRising: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val navigationBars = WindowInsets.navigationBars
    var keyboardHeight by rememberSaveable { mutableIntStateOf(0) }
    // 表情开着时键盘开始升起：这一块要空着跟着键盘让出，直到键盘停稳
    var handingOver by remember { mutableStateOf(false) }
    val rising by rememberUpdatedState(onKeyboardRising)
    val isOpen by rememberUpdatedState(open)
    LaunchedEffect(ime, imeTarget, density) {
        launch {
            snapshotFlow { ime.getBottom(density).takeIf { it > 0 && it == imeTarget.getBottom(density) } }
                .collect { settled -> if (settled != null) keyboardHeight = settled }
        }
        // 键盘往上去的那一刻（目标高度从 0 变正）开始交接，键盘停稳（或又往下去）交接结束；不带动画的键盘这两件事在同一帧
        var wasUp = false
        snapshotFlow { (imeTarget.getBottom(density) > 0) to (ime.getBottom(density) == imeTarget.getBottom(density)) }.collect { (up, settled) ->
            if (up && !wasUp) {
                if (isOpen) handingOver = true
                rising()
            }
            if (settled || !up) handingOver = false
            wasUp = up
        }
    }
    if (!open && !handingOver) return
    // 键盘落尽才画表情：落下途中露出来的只是面板底色
    val keyboardGone by remember(ime, density) { derivedStateOf { ime.getBottom(density) == 0 } }
    val shown = open && keyboardGone
    val alpha = remember { Animatable(0f) }
    val fadeIn = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(shown) { if (shown) alpha.animateTo(1f, fadeIn) else alpha.snapTo(0f) }
    val fallback = with(density) { SmileyPanelFallbackHeight.roundToPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val navigation = navigationBars.getBottom(this)
                val full = if (keyboardHeight > 0) keyboardHeight else fallback + navigation
                val panel = (full - navigation).coerceAtLeast(0)
                val visible = (full - maxOf(navigation, ime.getBottom(this))).coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minHeight = panel, maxHeight = panel))
                layout(placeable.width, constraints.constrainHeight(visible)) { placeable.place(0, 0) }
            },
    ) {
        if (open) Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
    }
}

/**
 * 表情面板：上面一行家族页签（[SmileyCatalog.Family] 七个家族，按 next.bgm.tv 表情面板的页签顺序；每个页签画这一家族的
 * 第一个表情，长按说出官方的分组标题与作者），下面是当前家族的表情网格，占满余下的高度、在里面滚。
 * 小表情按原大画在 40 高的格里，大表情家族（[SmileyCatalog.Scale.LARGE]）用大格。
 */
@Composable
private fun SmileyPanel(onPick: (String) -> Unit) {
    var family by rememberSaveable { mutableStateOf(SmileyCatalog.Family.entries.first()) }
    val codes = remember(family) { SmileyCatalog.codesOf(family) }
    val large = SmileyCatalog.scaleOf(family) == SmileyCatalog.Scale.LARGE
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TipHost(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ToolbarPadding)) {
                SmileyCatalog.Family.entries.forEach { entry ->
                    val label = stringResource(entry.labelRes())
                    FilledTonalIconToggleButton(
                        checked = entry == family,
                        onCheckedChange = { family = entry },
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f),
                        ),
                        modifier = Modifier.longPressTip(label).semantics { contentDescription = label },
                    ) {
                        SmileyImage(SmileyCatalog.codesOf(entry).first(), FamilyTabIconSize)
                    }
                }
            }
        }
        ContentGrid(
            cell = if (large) GridCell.LargeSmiley else GridCell.Smiley,
            modifier = Modifier.fillMaxWidth().weight(1f),
            rowSpacing = 4.dp,
        ) {
            items(codes, key = { it }) { code ->
                SmileyCell(code = code, height = if (large) LargeSmileyCellHeight else SmileyCellHeight, imageSize = if (large) LargeSmileySize else SmileySize) {
                    onPick(code)
                }
            }
        }
    }
}

@Composable
private fun SmileyCell(code: String, height: Dp, imageSize: Dp, onClick: () -> Unit) {
    PressArea(onClick = onClick, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) { SmileyImage(code, imageSize) }
    }
}

/** 家族 → 官方的分组标题与作者（页签的长按提示与读屏名，全仓唯一）。 */
private fun SmileyCatalog.Family.labelRes(): Int = when (this) {
    SmileyCatalog.Family.CLASSIC -> R.string.smiley_family_classic
    SmileyCatalog.Family.TV -> R.string.smiley_family_tv
    SmileyCatalog.Family.TV_VS -> R.string.smiley_family_vs
    SmileyCatalog.Family.TV_500 -> R.string.smiley_family_500
    SmileyCatalog.Family.MUSUME -> R.string.smiley_family_musume
    SmileyCatalog.Family.BLAKE -> R.string.smiley_family_blake
    SmileyCatalog.Family.KAOMOJI -> R.string.smiley_family_kaomoji
}

/** 输入框当前的正文与选区。 */
private fun TextFieldState.current(): TextEdit = TextEdit(text.toString(), selection.start, selection.end)

/** 把一次 [TextEdit] 变换落到输入框上：整段替换并放好光标（工具栏、表情、插入都走这里）。 */
private fun TextFieldState.applyEdit(change: TextEdit.() -> TextEdit) {
    val after = current().change()
    edit {
        replace(0, length, after.text)
        selection = TextRange(after.selectionStart, after.selectionEnd)
    }
}

// 面板几何——标题行 48 高（M3 面板顶部可交互区 48dp），其余是原型值，我的设计决定（not in M3）：
// 正文框 5–10 行；工具栏左右 8；预览内边距 16（同输入框的内容边距）；还没量到键盘时表情面板 280 高；
// 家族页签的表情画 24；小格 40 高画 21 的原大表情，大格 64 高画 56。
private const val FIELD_MIN_LINES = 5
private const val FIELD_MAX_LINES = 10
private val ToolbarPadding = 8.dp
private val PreviewPadding = 16.dp
private val SmileyPanelFallbackHeight = 280.dp
private val FamilyTabIconSize = 24.dp
private val SmileyCellHeight = 40.dp
private val SmileySize = 21.dp
private val LargeSmileyCellHeight = 64.dp
private val LargeSmileySize = 56.dp
