package io.github.touhougo.chogumi.core.ui.collection

import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.formatEpisodeSort

/**
 * 点进度尺上方的大数字输入的一次校验结果：通过时是尺子上的第几格。文案由界面按结果取字符串资源，这里不出现文字；
 * 不通过时进度不变——界面只在 [Valid] 时写回。
 */
internal sealed interface ProgressInput {
    data class Valid(val index: Int) : ProgressInput

    /** 空或全是空白。 */
    data object Empty : ProgressInput

    /** 不是非负的数（字母、负数；书籍的话卷数还不能是小数）。 */
    data object NotANumber : ProgressInput

    /** 能到的范围里没有这一集（小数集号输错、分季条目输了本季里的第几集）。 */
    data object NoSuchEpisode : ProgressInput

    /** 超出能到的最大值：给出那个值（格上的字），而不是静默截断。 */
    data class OverLimit(val max: String) : ProgressInput
}

/** 书籍的话数、卷数：非负整数，[total] 大于 0（已知）时不得超过它，未知时不设上限。格子就是数本身。 */
internal fun parseCountInput(input: String, total: Int): ProgressInput {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ProgressInput.Empty
    val value = trimmed.toIntOrNull()?.takeIf { it >= 0 } ?: return ProgressInput.NotANumber
    return if (total in 1 until value) ProgressInput.OverLimit(total.toString()) else ProgressInput.Valid(value)
}

/**
 * 动画：输的是集号（格上写的 sort），找到前 [reach] 集正片（之后的还没播出）里的那一集，结果是它在尺子上的格。
 * `0` 有一集 `sort = 0` 的正片（序章）时是那一集，没有时是第 0 格：一集都没看。
 */
internal fun parseEpisodeInput(input: String, mains: List<EpisodeBo>, reach: Int): ProgressInput {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ProgressInput.Empty
    val sort = trimmed.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return ProgressInput.NotANumber
    val position = mains.take(reach).indexOfFirst { it.sort == sort }
    val max = mains.getOrNull(reach - 1)?.sort ?: 0.0
    return when {
        position >= 0 -> ProgressInput.Valid(position + 1)
        sort == 0.0 -> ProgressInput.Valid(0)
        sort > max -> ProgressInput.OverLimit(formatEpisodeSort(max))
        else -> ProgressInput.NoSuchEpisode
    }
}
