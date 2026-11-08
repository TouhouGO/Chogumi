package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.res.R

/**
 * 设置的分类：一级菜单里的一行、点进去的一页。名字直接说出里面有什么，彼此不重叠。
 * 名字与图标只在这里，菜单行与那一页的标题都读它；分类里有哪些设置由设置页按分类排。
 */
enum class SettingsSection(@StringRes val title: Int, @DrawableRes val icon: Int) {
    /** 主题、配色、应用图标；界面大小、阅读字号、刷新率、封面画质。 */
    APPEARANCE(R.string.settings_appearance, R.drawable.palette_24),

    /** 首页待看数量、进度页默认分类。 */
    HOME_PROGRESS(R.string.settings_home_progress, R.drawable.home_24),

    /** 公共评分、封面评分；专注模式、好友标识。 */
    RATINGS_COMMUNITY(R.string.settings_ratings_community, R.drawable.star_24),

    /** NSFW、屏蔽的小组、屏蔽的用户。 */
    CONTENT_FILTER(R.string.settings_content_filter, R.drawable.shield_24),
}
