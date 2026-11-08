package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.res.R

/** 角色在作品里的定位 → 文案（全仓唯一）：作品详情的角色列表与角色、人物页共用。 */
@StringRes
fun CastRole.labelRes(): Int = when (this) {
    CastRole.MAIN -> R.string.mono_role_main
    CastRole.SUPPORTING -> R.string.mono_role_supporting
    CastRole.GUEST -> R.string.mono_role_guest
    CastRole.EXTRA -> R.string.mono_role_extra
    CastRole.NARRATOR -> R.string.mono_role_narrator
}
