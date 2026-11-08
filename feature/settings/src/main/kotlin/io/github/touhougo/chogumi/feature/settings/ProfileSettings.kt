package io.github.touhougo.chogumi.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.text
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import org.koin.compose.viewmodel.koinViewModel

/** 设置 › 个人资料：头像、昵称、签名、自我介绍，各点开一个面板改。 */
@Composable
fun ProfileSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: ProfileSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileSettingsContent(state = uiState, actions = viewModel, failures = viewModel.failures, onBack = onBack, modifier = modifier)
}

/**
 * 个人资料页的无状态内容层。能改时头像可点（系统选图器，只选图片），三行文字点开各自的面板；
 * 不能改（没有网页会话）时只读，头像下面一句话说怎么才能改。
 */
@Composable
fun ProfileSettingsContent(
    state: ProfileSettingsUiState,
    actions: ProfileSettingsActions,
    failures: FailureNotices,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) actions.pickAvatar(uri.toString())
    }
    val pickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val snackbar = remember { SnackbarHostState() }
    SettingsListPage(
        title = stringResource(R.string.settings_profile),
        failures = failures,
        onBack = onBack,
        modifier = modifier,
        snackbar = snackbar,
        snackbarShown = state.textEditor == null && state.avatarEditor == null,
    ) {
        when (sectionStateOf(state.me) { false }) {
            SectionState.LOADING -> {
                item(key = "avatar") { AvatarHeaderSkeleton() }
                items(ProfileTextField.entries.size, key = { "row-$it" }) { index ->
                    ProfileFieldRow(index, ProfileTextField.entries.size, ProfileTextField.entries[index], value = null, onClick = null)
                }
            }
            SectionState.ERROR -> item(key = "error") {
                RetryError(failure = (state.me as Load.Failed).failure, onRetry = actions::retry)
            }
            SectionState.EMPTY, SectionState.CONTENT -> {
                val user = (state.me as Load.Ready).value
                val editable = state.canEdit == true
                item(key = "avatar") { AvatarHeader(user, editable = editable, onClick = pickImage) }
                if (state.canEdit == false) {
                    item(key = "read-only") {
                        Text(
                            stringResource(R.string.profile_needs_password_login),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 16.dp),
                        )
                    }
                }
                items(ProfileTextField.entries.size, key = { "row-$it" }) { index ->
                    val field = ProfileTextField.entries[index]
                    ProfileFieldRow(
                        index = index,
                        count = ProfileTextField.entries.size,
                        field = field,
                        value = field.valueIn(user),
                        onClick = if (editable) ({ actions.openText(field) }) else null,
                    )
                }
            }
        }
    }

    state.textEditor?.let { editor ->
        ProfileTextSheet(editor, snackbar, onChange = actions::changeText, onSave = actions::saveText, onDismiss = actions::closeText)
    }
    state.avatarEditor?.let { editor ->
        AvatarSheet(editor, snackbar, onPickAnother = pickImage, onSave = actions::saveAvatar, onDismiss = actions::closeAvatar)
    }
}

@Composable
private fun ProfileTextField.label(): String = stringResource(
    when (this) {
        ProfileTextField.NICKNAME -> R.string.profile_nickname
        ProfileTextField.SIGN -> R.string.profile_sign
        ProfileTextField.BIO -> R.string.profile_bio
    },
)

/** 头像居中，能改时右下角一枚相机标记，整个圆可点。尺寸是设计决定（not in M3）。 */
@Composable
private fun AvatarHeader(user: UserBo, editable: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        Box {
            CircleAvatar(
                url = user.avatarUrl,
                description = stringResource(if (editable) R.string.profile_change_avatar else R.string.profile_avatar),
                size = ProfileAvatarSize,
                onClick = if (editable) onClick else null,
            )
            if (editable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(AvatarBadgeSize)
                        .border(AvatarBadgeRing, MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(AvatarBadgeRing)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.photo_camera_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** [AvatarHeader] 的占位：同一位置同一尺寸的圆。 */
@Composable
private fun AvatarHeaderSkeleton() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        SkeletonBox(Modifier.size(ProfileAvatarSize), shape = CircleShape)
    }
}

/**
 * 一项文字资料：名字在上，现在的值在下（自我介绍最多三行）；空着的项只有名字。能改时整行可点。
 * 资料还没到（[value] 为 null）时名字照画，值是一行占位。
 */
@Composable
private fun ProfileFieldRow(index: Int, count: Int, field: ProfileTextField, value: String?, onClick: (() -> Unit)?) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = when {
            value == null -> {
                { SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.5f) }
            }
            value.isBlank() -> null
            else -> {
                {
                    Text(
                        value,
                        maxLines = if (field == ProfileTextField.BIO) BIO_PREVIEW_LINES else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { Text(field.label()) }
}

/**
 * 改一项文字资料的面板：标题行右侧「保存」，下面一个输入框（自我介绍多行）。打开就把焦点交给输入框；
 * 写成功才滑下去，失败时框里的字留着，提示条画在面板上。
 */
@Composable
private fun ProfileTextSheet(
    editor: ProfileTextEditor,
    snackbar: SnackbarHostState,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    PageModalSheet(
        onDismiss = onDismiss,
        fullHeight = false,
        hideRequested = editor.saved,
        growsWithContent = true,
        inputFocus = focus,
        snackbar = snackbar,
    ) {
        SheetTitleRow(editor.field.label()) {
            BusyButton(onClick = onSave, enabled = editor.canSave, busy = editor.saving, busyLabel = stringResource(R.string.loading)) {
                Text(stringResource(R.string.save))
            }
        }
        FormTextField(
            value = editor.text,
            onValueChange = onChange,
            enabled = !editor.saving && !editor.saved,
            minLines = if (editor.field == ProfileTextField.BIO) BIO_MIN_LINES else 1,
            modifier = Modifier
                .padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, bottom = 16.dp)
                .fillMaxWidth()
                .focusRequester(focus),
        )
    }
}

/**
 * 换头像的面板：标题行右侧「保存」，下面是裁好的圆形预览（处理中是同尺寸的占位）与「换一张」。
 * 写成功才滑下去，失败时选好的图留着，提示条画在面板上。
 */
@Composable
private fun AvatarSheet(editor: AvatarEditor, snackbar: SnackbarHostState, onPickAnother: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    PageModalSheet(onDismiss = onDismiss, fullHeight = false, hideRequested = editor.saved, growsWithContent = true, snackbar = snackbar) {
        SheetTitleRow(stringResource(R.string.profile_avatar)) {
            BusyButton(onClick = onSave, enabled = editor.canSave, busy = editor.saving, busyLabel = stringResource(R.string.loading)) {
                Text(stringResource(R.string.save))
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val jpeg = editor.jpeg
            if (jpeg == null) {
                SkeletonBox(Modifier.size(AvatarPreviewSize), shape = CircleShape)
            } else {
                AsyncImage(
                    model = jpeg,
                    contentDescription = stringResource(R.string.profile_avatar),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(AvatarPreviewSize).clip(CircleShape),
                )
            }
            TextButton(onClick = onPickAnother, enabled = !editor.saving && !editor.saved) {
                Icon(painterResource(R.drawable.image_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.profile_pick_another), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

/** 设置一级菜单最上面一行：自己的头像与显示名，点了去个人资料页。资料还没到时是同尺寸的占位，读不到时是人形图标加「个人资料」。 */
@Composable
internal fun ProfileEntryRow(index: Int, count: Int, me: Load<UserBo>, onClick: () -> Unit) {
    val pending = me is Load.Pending
    val user = (me as? Load.Ready)?.value
    SegmentedItem(
        index = index,
        count = count,
        onClick = if (pending) null else onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            if (pending) {
                SkeletonBox(Modifier.size(EntryAvatarSize), shape = CircleShape)
            } else {
                CircleAvatar(url = user?.avatarUrl, description = null, size = EntryAvatarSize)
            }
        },
        trailingContent = { Icon(painterResource(R.drawable.chevron_right_24), contentDescription = null) },
    ) {
        if (pending) {
            SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.4f)
        } else {
            Text(user?.let { displayNameOf(it) } ?: stringResource(R.string.settings_profile), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 个人资料页的头像、换头像面板的预览、一级菜单那一行的头像边长；相机标记的大小与描边（设计决定，not in M3）。 */
private val ProfileAvatarSize = 112.dp
private val AvatarPreviewSize = 200.dp
private val EntryAvatarSize = 40.dp
private val AvatarBadgeSize = 36.dp
private val AvatarBadgeRing = 3.dp

/** 自我介绍在行里最多露几行、输入框至少几行高（设计决定，not in M3）。 */
private const val BIO_PREVIEW_LINES = 3
private const val BIO_MIN_LINES = 5
