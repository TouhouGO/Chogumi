package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.model.statistics.subjectStatsVisible
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectSecondTab
import io.github.touhougo.chogumi.core.model.subjectTypeSpec
import io.github.touhougo.chogumi.core.res.R

/**
 * 作品详情的选项卡：概览 / 第 2 个 tab / 人物 / 社区 / 统计。
 *
 * 第 2 个 tab 按类型改名（章节 / 单行本 / 曲目，游戏没有）：三个候选各自声明对应的 [secondTab]，
 * 当前条目显示哪一个只看规格表的 [io.github.touhougo.chogumi.core.model.SubjectTypeSpec.secondTab]，不写 `when (type)`。
 */
enum class SubjectDetailsSection(
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    /** 非 null 表示这是「第 2 个 tab」的一个候选，只在规格表的 secondTab 与它相同时出现。 */
    val secondTab: SubjectSecondTab? = null,
) {
    OVERVIEW(R.string.detail_tab_overview, R.drawable.info_24),
    EPISODES(R.string.episodes, R.drawable.play_circle_24, SubjectSecondTab.EPISODES),
    VOLUMES(R.string.detail_tab_volumes, R.drawable.menu_book_24, SubjectSecondTab.VOLUMES),
    TRACKS(R.string.detail_tab_tracks, R.drawable.music_note_24, SubjectSecondTab.TRACKS),
    PEOPLE(R.string.detail_tab_people, R.drawable.group_24),
    COMMUNITY(R.string.detail_tab_community, R.drawable.forum_24),
    STATS(R.string.detail_stats, R.drawable.bar_chart_24),
}

/**
 * 依据条目、功能可见性与人物数据决定详情页可见的选项卡列表（单一判定点）。[subject] 为 null 表示条目还没到（骨架阶段）。
 * - 第 2 个 tab：候选里只留 [SubjectDetailsSection.secondTab] 等于规格表 secondTab 的那一个（游戏一个都不留）；
 * - 人物 [SubjectDetailsSection.PEOPLE]：[hasPeople] 为 false（角色与制作人员都已确认为空）时不出现；
 * - 社区 [SubjectDetailsSection.COMMUNITY]：[features] 的 `community` 为 false（专注模式）时不出现；
 * - 统计 [SubjectDetailsSection.STATS]：由 [subjectStatsVisible] 判定（公共评分关掉或评分不足 10 人不出现）；
 *   只有列表摘要的条目评分人数不可信（搜索短行恒为 0），按「未知」处理，等详情到了再定。
 *
 * 几个维度在这里一次判定完：界面只渲染结果，不得再叠一层筛除。
 */
fun visibleSectionsFor(
    subject: SubjectBo?,
    features: AppFeatures,
    hasPeople: Boolean = true,
): List<SubjectDetailsSection> {
    val secondTab = subjectTypeSpec(subject?.type).secondTab
    val ratingTotal = subject?.takeIf { it.hasFullDetail }?.ratingTotal
    return SubjectDetailsSection.entries.filter { section ->
        when {
            section.secondTab != null -> section.secondTab == secondTab
            section == SubjectDetailsSection.PEOPLE -> hasPeople
            section == SubjectDetailsSection.COMMUNITY -> features.community
            section == SubjectDetailsSection.STATS -> subjectStatsVisible(ratingTotal, features)
            else -> true
        }
    }
}

/**
 * 选中态纠偏（唯一一处）：请求的选项卡在当前条目与可见性下不可见时，回落到首个可见选项卡（概览恒可见）。
 * 详情页的选中态是它的派生值（[SubjectDetailsUiState.selectedSection]），界面只渲染结果。
 */
fun resolveSection(
    requested: SubjectDetailsSection,
    subject: SubjectBo?,
    features: AppFeatures,
    hasPeople: Boolean = true,
): SubjectDetailsSection {
    val visible = visibleSectionsFor(subject, features, hasPeople)
    return if (requested in visible) requested else visible.first()
}
