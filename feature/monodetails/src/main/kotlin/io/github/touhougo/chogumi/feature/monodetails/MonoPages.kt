package io.github.touhougo.chogumi.feature.monodetails

import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.res.R

/** 作品区标题：角色是「出演作品」，人物是「参与作品」。 */
@StringRes
internal fun MonoType.worksTitleRes(): Int = when (this) {
    MonoType.CHARACTER -> R.string.mono_works_character
    MonoType.PERSON -> R.string.mono_works_person
}
