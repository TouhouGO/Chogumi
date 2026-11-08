package io.github.touhougo.chogumi.core.ui.composables

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.github.touhougo.chogumi.core.model.formatAbbreviatedCount
import io.github.touhougo.chogumi.core.res.R

/**
 * 放送表的文案（每日放送页与放送小组件共用）。取法写在收 [Resources] 的函数里，组合里的同名函数只是转交
 * （小组件不在 Compose UI 的组合里，直接传 `context.resources`）。
 */
private val WEEKDAY_NARROW_RES = listOf(
    R.string.weekday_narrow_mon,
    R.string.weekday_narrow_tue,
    R.string.weekday_narrow_wed,
    R.string.weekday_narrow_thu,
    R.string.weekday_narrow_fri,
    R.string.weekday_narrow_sat,
    R.string.weekday_narrow_sun,
)

/** 星期 [weekday]（1=周一…7=周日）的一个字（中文「一」…「日」，英文 M T W …）；系统的窄写法在中文下给的是「星」，不能用。 */
fun weekdayNarrowLabel(resources: Resources, weekday: Int): String = resources.getString(WEEKDAY_NARROW_RES[weekday - 1])

@Composable
fun weekdayNarrowLabel(weekday: Int): String = weekdayNarrowLabel(LocalResources.current, weekday)

/** 在看人数「1.2W 在看」（缩写只经 [formatAbbreviatedCount]，按界面的语言）。 */
fun calendarWatchersLabel(resources: Resources, watchers: Int): String =
    resources.getString(R.string.calendar_watchers_count, formatAbbreviatedCount(watchers, resources.configuration.locales[0]))

@Composable
fun calendarWatchersLabel(watchers: Int): String = calendarWatchersLabel(LocalResources.current, watchers)
