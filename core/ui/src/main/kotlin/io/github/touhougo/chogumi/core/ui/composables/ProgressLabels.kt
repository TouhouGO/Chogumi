package io.github.touhougo.chogumi.core.ui.composables

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R

/**
 * 进度文案——**全 App 唯一**：EP 按钮、待看卡片「已播到」、时间表海报标签、单集详情、桌面小组件都走这里。
 * 中文全中文（第 12 集 / 第 20 话 / 第 3 卷），英文全英文（EP 12 / Ch. 20 / Vol. 3），不混排。
 *
 * 每条文案的取法写在收 [Resources] 的那个函数里；组合里的同名函数只是从 [LocalResources] 取资源再交给它
 * （小组件不在 Compose UI 的组合里，没有那些 CompositionLocal，直接传 `context.resources`）。
 */
fun episodeProgressLabel(resources: Resources, sort: Double): String =
    resources.getString(R.string.progress_episode, formatEpisodeSort(sort))

@Composable
fun episodeProgressLabel(sort: Double): String = episodeProgressLabel(LocalResources.current, sort)

/** 书籍进度文案：单位只由 [BookUnit]（`bookUnit` 纯函数的结果）决定。 */
fun bookProgressLabel(resources: Resources, unit: BookUnit, value: Int): String = when (unit) {
    BookUnit.EP -> resources.getString(R.string.progress_book_chapter, value)
    BookUnit.VOL -> resources.getString(R.string.progress_book_volume, value)
}

@Composable
fun bookProgressLabel(unit: BookUnit, value: Int): String = bookProgressLabel(LocalResources.current, unit, value)

/**
 * 列表行的进度文字（首页、进度页与桌面小组件共用）：「看过 0 / 51 集」，总数未知时「看过 0 集」；
 * 书籍的单位（话/卷）由 [ProgressCount.Known.unit]（`bookUnit` 纯函数的结果）决定。
 *
 * **这一行只说条目内的条数，不出现系列绝对集号。** 分季条目不追加「第 202–252 集」：
 * 那是另一套坐标系（`sort`），和「看过 18 / 51」摆在一起要人心算；
 * 系列绝对集号只在条目详情的分集列表里出现，那里本来就是按 sort 排的。
 *
 * 逐集表尚未到位（[ProgressCount.Loading]）或无进度（[ProgressCount.None]）时返回 null，
 * 不显示任何 N / M，界面层走加载骨架占位或不占像素。
 */
fun collectionProgressLabel(resources: Resources, count: ProgressCount): String? {
    val known = when (count) {
        is ProgressCount.Known -> count
        ProgressCount.Loading, ProgressCount.None -> return null
    }
    val hasTotal = known.total > 0
    return when (known.unit) {
        null -> if (hasTotal) resources.getQuantityString(R.plurals.progress_row_episodes, known.total, known.current, known.total)
        else resources.getQuantityString(R.plurals.progress_row_episodes_unknown, known.current, known.current)
        BookUnit.EP -> if (hasTotal) resources.getString(R.string.progress_row_chapters, known.current, known.total)
        else resources.getString(R.string.progress_row_chapters_unknown, known.current)
        BookUnit.VOL -> if (hasTotal) resources.getString(R.string.progress_row_volumes, known.current, known.total)
        else resources.getString(R.string.progress_row_volumes_unknown, known.current)
    }
}

@Composable
fun collectionProgressLabel(count: ProgressCount): String? = collectionProgressLabel(LocalResources.current, count)

/**
 * 列表行放不下完整进度句时的纯数字写法。总数未知就只写当前数，不用占位符冒充总数。
 * 完整单位和类型仍由 [collectionProgressLabel] 唯一提供，并留给读屏。
 */
@Composable
internal fun collectionProgressNumbers(count: ProgressCount.Known): String =
    if (count.total > 0) stringResource(R.string.progress_row_numbers, count.current, count.total)
    else stringResource(R.string.progress_row_number_unknown, count.current)
