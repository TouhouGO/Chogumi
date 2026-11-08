package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/**
 * 条目类型（注意：没有 5）。
 * @Serializable：随 [SearchCriteria] 进路由。
 */
@Serializable
enum class SubjectType(val value: Int) {
    UNKNOWN(0),
    BOOK(1),
    ANIME(2),
    MUSIC(3),
    GAME(4),
    REAL(6),
    ;

    companion object {
        fun from(value: Int): SubjectType? = entries.find { it.value == value }
    }
}

/**
 * 五个内容分类（不含 [SubjectType.UNKNOWN]），按全 App 统一的顺序：动画、书籍、游戏、音乐、三次元。
 * 分类 tab、翻页的页序、按类型逐个同步、首页「继续」的分类 chip 都取这一份。
 */
val ContentDomains: List<SubjectType> = listOf(SubjectType.ANIME, SubjectType.BOOK, SubjectType.GAME, SubjectType.MUSIC, SubjectType.REAL)
