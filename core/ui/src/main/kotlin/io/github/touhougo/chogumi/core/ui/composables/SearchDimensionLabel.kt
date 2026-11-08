package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R

/**
 * 搜索维度 → 资源。**全 App 唯一映射点**：搜索页筛选面板与进度页「分类」组共用同一份，
 * 两处的维度命名因此不可能分叉。其余 UI 不得再写第二份 `when`。
 *
 * 叫法按条目类型给（统称「分类」「类型」的话，和常用标签分不清）：
 * GENRE 在动画叫「题材」、游戏叫「玩法」、音乐叫「形式」；CATEGORY 统一叫「形式」、
 * SERIES 叫「系列或单本」、PUBLICATION_STATUS 叫「连载状态」（原「进度」与进度页重名）。
 * 进度页传的是它生成这些分组时用的那个类型。
 *
 * 放在 core:ui 而不是某个 feature：两个 feature（explore 与 progress）都要用它，
 * 放进任一 feature 都会让另一个产生跨 feature 依赖（红线三：判定逻辑只允许一份，且不靠复制）。
 */
fun searchDimensionTitleRes(type: SubjectType?, dimension: SearchDimension): Int = when (dimension) {
    SearchDimension.CATEGORY -> R.string.search_dim_category
    SearchDimension.SERIES -> R.string.search_dim_series
    SearchDimension.PLATFORM -> R.string.search_dim_platform
    SearchDimension.SOURCE -> R.string.search_dim_source
    SearchDimension.GENRE -> when (type) {
        SubjectType.GAME -> R.string.search_dim_genre_game
        SubjectType.MUSIC -> R.string.search_dim_genre_music
        else -> R.string.search_dim_genre
    }
    SearchDimension.THEME -> R.string.search_dim_theme
    SearchDimension.REGION -> R.string.search_dim_region
    SearchDimension.AUDIENCE -> R.string.search_dim_audience
    SearchDimension.CONTENT_RATING -> R.string.search_dim_content_rating
    SearchDimension.PUBLICATION_STATUS -> R.string.search_dim_publication_status
}
