package io.github.touhougo.chogumi.core.model

/** EP 按钮状态。判定唯一入口见 [EpisodeTracking.epButtonState]。 */
sealed interface EpButtonState {
    /** 可点：标记到这一集。[position] 是它在本条目正片里的序号（从 1 数起，见 [EpisodeTracking.mainEpisodePosition]）。 */
    data class Next(val episode: EpisodeBo, val position: Int) : EpButtonState

    /** 可点：书籍标记到下一话/下一卷。 */
    data class BookNext(val current: Int, val unit: BookUnit) : EpButtonState

    /**
     * 等待播出：不可点。[daysUntil] 恒 `>= 1`（`1` = 明天，`N` = N 天后）——今天播出算已播出，见 [EpisodeTracking.airState]。
     * [intervalDays]：上一集正片的播出日到这一集隔几天；没有上一集、播出日未知或同日连播时为 null。
     */
    data class Waiting(val episode: EpisodeBo, val daysUntil: Long, val position: Int, val intervalDays: Long?) : EpButtonState {
        /**
         * 这次等待已经过去的比例——**倒计时环的进度，唯一出处**：上一集刚播出是 0，越临近这一集的播出日越满。
         * 没有间隔可参照时为 0（只画轨道）；等待比间隔还长（停播一周再续）时也是 0。
         */
        val elapsedFraction: Float
            get() = intervalDays?.let { ((it - daysUntil).toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    }

    /** 全部看过/读过：按钮显示「标为看过」。 */
    data object Finished : EpButtonState

    /**
     * 章节请求**还没返回**：槽位里画占位，不可点。
     * 判据是「请求有没有返回」，**不是**「章节列表是不是空」——存在 `eps = 0` 且确实零集的动画条目，
     * 拿空列表当加载中会挂一个永久占位。
     */
    data object Loading : EpButtonState

    /** 没有可用进度动作（章节已返回但无正片，或收藏状态不是「在看」）：槽位留空，不画按钮。 */
    data object Empty : EpButtonState
}

/** 书籍进度单位：话或卷（文案走字符串资源，见 core:ui 的进度文案函数）。 */
enum class BookUnit { EP, VOL }

/** 条目级进度计数（[EpisodeTracking.progressCount]）——全 App 进度计数唯一领域模型。 */
sealed interface ProgressCount {
    /** 游戏、音乐等不带进度的类型（一个像素都不占）。 */
    data object None : ProgressCount

    /** 章节请求尚未返回（动画/三次元逐集表未到位），走加载态。 */
    data object Loading : ProgressCount

    /**
     * 已知进度计数。
     *
     * @param unit 书籍单位（话/卷），非书籍类型为 null
     * @param current 看过数（书籍为 epStatus/volStatus；动画/三次元为看过正片条数，特典不计入）
     * @param total 总集数/总卷数（0 = 未知；动画/三次元为正片总条数）
     */
    data class Known(
        val unit: BookUnit?,
        val current: Int,
        val total: Int,
    ) : ProgressCount
}

/**
 * 书籍进度单位判定纯函数：
 * `volStatus > 0 且 epStatus == 0` 时用「卷」，否则用「话」。
 */
fun bookUnit(epStatus: Int, volStatus: Int): BookUnit =
    if (volStatus > 0 && epStatus == 0) BookUnit.VOL else BookUnit.EP
