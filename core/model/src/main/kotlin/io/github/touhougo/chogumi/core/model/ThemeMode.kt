package io.github.touhougo.chogumi.core.model

/**
 * 主题模式。[fixedDark] 是它定死的明暗：浅色 false、深色 true，跟随系统时为 null（这一刻由系统定）。
 * 模式 → 明暗只从这里推（App 的主题、小组件与它的选作品页）。
 */
enum class ThemeMode(val fixedDark: Boolean?) {
    FOLLOW_SYSTEM(null),
    LIGHT(false),
    DARK(true),
}
