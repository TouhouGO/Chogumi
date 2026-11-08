package io.github.touhougo.chogumi.core.model

/** 章节观看状态 */
enum class EpisodeStatus(val value: Int) {
    NONE(0),
    WISH(1),
    DONE(2),
    DROPPED(3),
    ;

    companion object {
        fun from(value: Int): EpisodeStatus? = entries.find { it.value == value }
    }
}
