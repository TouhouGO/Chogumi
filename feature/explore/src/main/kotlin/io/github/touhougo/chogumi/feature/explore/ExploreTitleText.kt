package io.github.touhougo.chogumi.feature.explore

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.CombinationTitle
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileLabel
import io.github.touhougo.chogumi.core.res.R

/**
 * 探索页标题与方块文字的显示规格。
 *
 * 多数标题是译好的文案（[Res]）；预设与用户起的名字（[ExploreTitle.Named]）、图钉新建货架的默认名
 * （条件词连成的「A × B」）两种语言下一模一样，走 [Literal]（超过 3 个条件时外面套一层「等」，见 [combinationTitleSpec]）。
 */
sealed interface ExploreTitleTextSpec {
    data class Res(@StringRes val stringRes: Int, val formatArgs: List<Any> = emptyList()) : ExploreTitleTextSpec

    data class Literal(val text: String) : ExploreTitleTextSpec
}

/**
 * [ExploreTitle] → 显示文案的**唯一**映射函数（全仓唯一真源）。
 *
 * 绝对红线一/三：全仓只有一个 `when (ExploreTitle)` 穷尽全部叶子类型分支。
 * 业务判定与文案选择只在此处发生，任何 Composable 或 ViewModel 严禁再写第二个 `when (ExploreTitle)`。
 */
fun exploreTitleTextSpec(title: ExploreTitle): ExploreTitleTextSpec = when (title) {
    // —— 固定入口
    is ExploreTitle.DailyCalendar -> ExploreTitleTextSpec.Res(R.string.calendar)
    is ExploreTitle.Filters -> ExploreTitleTextSpec.Res(R.string.explore_title_filters)
    is ExploreTitle.Ranking -> ExploreTitleTextSpec.Res(R.string.ranking)

    // —— 带参的名字
    is ExploreTitle.NewSeason -> ExploreTitleTextSpec.Res(R.string.explore_title_new_season, listOf(title.month))
    is ExploreTitle.CatchUpSeason -> ExploreTitleTextSpec.Res(R.string.explore_title_catch_up_season, listOf(title.month))
    is ExploreTitle.ThisMonth -> when (title.type) {
        SubjectType.BOOK -> ExploreTitleTextSpec.Res(R.string.explore_title_this_month_book)
        SubjectType.MUSIC -> ExploreTitleTextSpec.Res(R.string.explore_title_this_month_music)
        SubjectType.GAME -> ExploreTitleTextSpec.Res(R.string.explore_title_this_month_game)
        SubjectType.REAL -> ExploreTitleTextSpec.Res(R.string.explore_title_this_month_real)
        else -> ExploreTitleTextSpec.Res(R.string.explore_title_this_month_default)
    }

    // —— 货架名
    is ExploreTitle.Trending -> ExploreTitleTextSpec.Res(R.string.trending)
    is ExploreTitle.TodayOnAir -> ExploreTitleTextSpec.Res(R.string.explore_title_today_on_air)
    is ExploreTitle.ThemeBrowse -> ExploreTitleTextSpec.Res(R.string.explore_title_theme_browse)
    is ExploreTitle.DecadeBrowse -> ExploreTitleTextSpec.Res(R.string.explore_title_decade_browse)
    is ExploreTitle.CompanyBrowse -> when (title.type) {
        SubjectType.ANIME -> ExploreTitleTextSpec.Res(R.string.explore_title_company_browse_anime)
        SubjectType.GAME -> ExploreTitleTextSpec.Res(R.string.explore_title_company_browse_game)
        else -> ExploreTitleTextSpec.Res(R.string.explore_title_company_browse_default)
    }
    is ExploreTitle.PlatformBrowse -> ExploreTitleTextSpec.Res(R.string.explore_title_platform_browse)
    is ExploreTitle.GenreBrowse -> ExploreTitleTextSpec.Res(R.string.explore_title_genre_browse)
    is ExploreTitle.FormatBrowse -> ExploreTitleTextSpec.Res(R.string.explore_title_format_browse)
    is ExploreTitle.RegionBrowse -> ExploreTitleTextSpec.Res(R.string.explore_title_region_browse)
    is ExploreTitle.Tokusatsu -> ExploreTitleTextSpec.Res(R.string.explore_title_tokusatsu)

    // —— 名字本身就是文字
    is ExploreTitle.Named -> ExploreTitleTextSpec.Literal(title.name)
}

/**
 * 一组条件的名字 → 显示规格，**唯一**：图钉新建货架的默认名。
 * 条件词两种语言一模一样，连好的部分直接给字面量；超过 3 个时套「%1$s 等」（这一个字随语言变）。
 */
fun combinationTitleSpec(title: CombinationTitle): ExploreTitleTextSpec =
    if (title.more) ExploreTitleTextSpec.Res(R.string.combination_title_more, listOf(title.joined))
    else ExploreTitleTextSpec.Literal(title.joined)

/**
 * [TileLabel] → 显示规格，**唯一**：词与预设名原样显示，年代与「全部…」按资源随语言变
 * （年代与搜索条件里的年代共用 `search_time_decade`）。
 */
fun exploreTileLabelSpec(label: TileLabel): ExploreTitleTextSpec = when (label) {
    is TileLabel.Word -> ExploreTitleTextSpec.Literal(label.word)
    is TileLabel.Decade -> ExploreTitleTextSpec.Res(R.string.search_time_decade, listOf(label.startYear))
    is TileLabel.AllOf -> ExploreTitleTextSpec.Res(R.string.explore_tile_all_of, listOf(label.word))
    is TileLabel.Preset -> ExploreTitleTextSpec.Literal(label.name)
}

/** Composable 环境下获取 [TileLabel] 的显示文本。 */
@Composable
fun exploreTileLabelText(label: TileLabel): String = exploreTileLabelSpec(label).text()

/** Composable 环境下获取 [ExploreTitle] 的显示文本。 */
@Composable
fun exploreTitleText(title: ExploreTitle): String = exploreTitleTextSpec(title).text()

/** 显示规格 → 文字（资源按当前语言取）。 */
@Composable
fun ExploreTitleTextSpec.text(): String = when (this) {
    is ExploreTitleTextSpec.Literal -> text
    is ExploreTitleTextSpec.Res ->
        if (formatArgs.isEmpty()) stringResource(stringRes)
        else stringResource(stringRes, *formatArgs.toTypedArray())
}

