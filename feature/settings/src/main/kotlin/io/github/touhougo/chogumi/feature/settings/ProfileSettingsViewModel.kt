package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.image.AvatarImages
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.ProfileChange
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.withProfileOf
import kotlinx.coroutines.launch

/** 资料里能改的文字项。 */
enum class ProfileTextField { NICKNAME, SIGN, BIO }

/** [field] 在资料 [user] 里现在的值。 */
internal fun ProfileTextField.valueIn(user: UserBo): String = when (this) {
    ProfileTextField.NICKNAME -> user.nickname
    ProfileTextField.SIGN -> user.sign
    ProfileTextField.BIO -> user.bio
}

/** [field] 填成 [text] 时要提交的那一项。 */
internal fun ProfileTextField.changeTo(text: String): ProfileChange = when (this) {
    ProfileTextField.NICKNAME -> ProfileChange.Nickname(text)
    ProfileTextField.SIGN -> ProfileChange.Sign(text)
    ProfileTextField.BIO -> ProfileChange.Bio(text)
}

/**
 * 改一项文字资料的面板（关着时页面状态里是 null）：哪一项、打开时的值、框里的字、在途、写成功（面板滑下去）。
 * 和打开时一样（按提交时的样子比，[ProfileChange.normalized]）不能保存；昵称不能为空。写失败时面板与框里的字都留着。
 */
data class ProfileTextEditor(
    val field: ProfileTextField,
    val original: String,
    val text: String = original,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val canSave: Boolean
        get() = !saving && !saved &&
            this.field.changeTo(text).normalized() != this.field.changeTo(original).normalized() &&
            (this.field != ProfileTextField.NICKNAME || text.isNotBlank())
}

/**
 * 换头像的面板（关着时为 null）：[jpeg] 是选好的图裁好、压好之后的样子，还在处理时为 null（画占位）；在途、写成功同上。
 * 处理失败时面板关掉、经提示说原因；写失败时面板与选好的图都留着。
 */
class AvatarEditor(
    val jpeg: ByteArray? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val canSave: Boolean get() = jpeg != null && !saving && !saved

    fun copy(jpeg: ByteArray? = this.jpeg, saving: Boolean = this.saving, saved: Boolean = this.saved) = AvatarEditor(jpeg, saving, saved)
}

/** 设置 › 个人资料的状态。 */
data class ProfileSettingsUiState(
    /** 自己的资料（四项现在的值）。 */
    val me: Load<UserBo> = Load.Pending,
    /**
     * 能不能在 App 里改（有没有网页会话，[UserRepository.canEditProfile]）；不能时页面只读、写明怎么做。
     * 还没看是 null：不让点，也不写只读说明（进页那一下不闪一句「要账号密码登录」）。
     */
    val canEdit: Boolean? = null,
    val textEditor: ProfileTextEditor? = null,
    val avatarEditor: AvatarEditor? = null,
)

/** 设置 › 个人资料的界面事件（失败提示走 ViewModel 的 `failures`）。 */
interface ProfileSettingsActions {
    fun retry()

    fun openText(field: ProfileTextField)

    fun changeText(text: String)

    fun saveText()

    fun closeText()

    /** 用户从系统选图器选了一张图（[uri]）：打开头像面板，在设备上裁好、压好再预览。再选一张也走这里。 */
    fun pickAvatar(uri: String)

    fun saveAvatar()

    fun closeAvatar()
}

/**
 * 设置 › 个人资料：读自己的资料，改头像、昵称、签名、自我介绍（[UserRepository.editProfile]，一次一项）。
 * 页面只画网上读到的自己的 p1 资料（[UserRepository.getEditableProfile]）：缓存与 p1 取不到时的退回资料里都没有自我介绍，
 * 面板的初值从那里来，用户一保存就把网页上的自我介绍盖成空的。写成功后页面直接用回读的资料，不另外重读。
 * 面板只在写成功之后才滑下去；结果落地前查面板还是不是发起时那一个（[editorGeneration]），关掉或重开过的不改。
 */
class ProfileSettingsViewModel(
    private val userRepository: UserRepository,
    private val avatarImages: AvatarImages,
) : StateViewModel<ProfileSettingsUiState>(ProfileSettingsUiState()), ProfileSettingsActions {

    /** 读资料（内容已显示时）、处理图片与写资料失败的提示。 */
    val failures = FailureNotices()

    /** 面板的代次：每次打开、关闭、重新选图都加一，旧的处理或写入结果不落到新面板上。 */
    private var editorGeneration = 0L

    init {
        load()
        checkEditable()
    }

    override fun retry() = load()

    /** 取不到就是加载失败、可重试。 */
    private fun load() {
        launchSection("me", failures, { loadOutcome { userRepository.getEditableProfile() } }, { it.me }) { copy(me = it) }
    }

    /** 看本地有没有网页会话：进页一次，写失败之后再一次（网页会话失效时会被丢掉）。 */
    private fun checkEditable() {
        viewModelScope.launch {
            val editable = outcomeOf { userRepository.canEditProfile() }.valueOrNull() ?: false
            updateState { it.copy(canEdit = editable) }
        }
    }

    override fun openText(field: ProfileTextField) {
        val user = (currentState.me as? Load.Ready)?.value ?: return
        if (currentState.canEdit != true) return
        editorGeneration++
        updateState { it.copy(textEditor = ProfileTextEditor(field, field.valueIn(user))) }
    }

    override fun changeText(text: String) = updateState { state ->
        state.copy(textEditor = state.textEditor?.takeUnless { it.saving || it.saved }?.copy(text = text) ?: state.textEditor)
    }

    override fun closeText() {
        editorGeneration++
        updateState { it.copy(textEditor = null) }
    }

    override fun saveText() {
        val editor = currentState.textEditor ?: return
        if (!editor.canSave) return
        val owner = editorGeneration
        updateState { it.copy(textEditor = it.textEditor?.copy(saving = true)) }
        viewModelScope.launch {
            val result = userRepository.editProfile(editor.field.changeTo(editor.text))
            if (owner == editorGeneration) {
                updateState { it.copy(textEditor = it.textEditor?.copy(saving = false, saved = result is Outcome.Success)) }
            }
            settle(result)
        }
    }

    override fun pickAvatar(uri: String) {
        if (currentState.canEdit != true) return
        val owner = ++editorGeneration
        updateState { it.copy(avatarEditor = AvatarEditor()) }
        viewModelScope.launch {
            when (val encoded = avatarImages.squareJpeg(uri)) {
                is Outcome.Success -> if (owner == editorGeneration) updateState { it.copy(avatarEditor = AvatarEditor(jpeg = encoded.value)) }
                is Outcome.Failed -> {
                    if (owner == editorGeneration) updateState { it.copy(avatarEditor = null) }
                    failures.post(encoded.failure)
                }
            }
        }
    }

    override fun saveAvatar() {
        val editor = currentState.avatarEditor ?: return
        val jpeg = editor.jpeg ?: return
        if (!editor.canSave) return
        val owner = editorGeneration
        updateState { it.copy(avatarEditor = it.avatarEditor?.copy(saving = true)) }
        viewModelScope.launch {
            val result = userRepository.editProfile(ProfileChange.Avatar(jpeg))
            if (owner == editorGeneration) {
                updateState { it.copy(avatarEditor = it.avatarEditor?.copy(saving = false, saved = result is Outcome.Success)) }
            }
            settle(result)
        }
    }

    override fun closeAvatar() {
        editorGeneration++
        updateState { it.copy(avatarEditor = null) }
    }

    /**
     * 写成功：页面的资料换成回读到的那几项（[withProfileOf]），什么也不说。
     * 写失败：说原因，再看一次能不能改（「要账号密码登录」时网页会话已被丢掉，页面随之只读）。
     */
    private fun settle(result: Outcome<UserBo>) {
        when (result) {
            is Outcome.Success -> updateState { state ->
                val shown = state.me as? Load.Ready ?: return@updateState state
                state.copy(me = Load.Ready(shown.value.withProfileOf(result.value)))
            }
            is Outcome.Failed -> {
                failures.post(result.failure)
                checkEditable()
            }
        }
    }
}
