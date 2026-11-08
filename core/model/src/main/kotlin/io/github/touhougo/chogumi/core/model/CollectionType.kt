package io.github.touhougo.chogumi.core.model

/** 收藏状态 */
enum class CollectionType(val value: Int) {
    UNKNOWN(0),
    WISH(1),
    DONE(2),
    DOING(3),
    ON_HOLD(4),
    DROPPED(5),
    ;

    companion object {
        /** 能选的五种状态（[UNKNOWN] 是服务端给了不认识的值时的占位，不能选）：收藏面板与进度页的状态筛选共用。 */
        val choices: List<CollectionType> = entries - UNKNOWN

        /** 没收藏的作品记了进度（点了一集、前进一步）时替用户建的收藏状态：开始看了就是在看。 */
        val startedByProgress: CollectionType = DOING

        fun from(value: Int): CollectionType? = entries.find { it.value == value }
    }
}
