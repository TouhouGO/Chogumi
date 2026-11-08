package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.parseSubjectYearMonth
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.composables.keyedListSaver
import io.github.touhougo.chogumi.core.ui.composables.starScoreText
import io.github.touhougo.chogumi.core.ui.composables.subjectRatingText

/**
 * 一条货架里「停在哪一项」（卡堆顶上那张、抽出的那本书、刻度尺停的那一格），连同货架内容的 [key] 存下（[keyedListSaver]）：
 * 进详情再回来还停在原处，从封面长出去的页缩回同一张；内容换了（刷新出新的一批、方块改了）从 [initial] 重新开始，不恢复旧的位置。
 */
@Composable
internal fun rememberShelfIndex(key: String, initial: Int = 0): MutableIntState = rememberSaveable(
    key,
    saver = keyedListSaver(key, save = { listOf(it.intValue) }, restore = { saved -> mutableIntStateOf(saved[0] as Int) }),
) { mutableIntStateOf(initial) }

/** 横排的滚动位置连同货架内容的 [key] 存下（同 [rememberShelfIndex]）；没有存档时从第 [initialIndex] 项起。 */
@Composable
internal fun rememberShelfListState(key: String, initialIndex: Int): LazyListState = rememberSaveable(
    key,
    saver = keyedListSaver(
        key,
        save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
        restore = { saved -> LazyListState(saved[0] as Int, saved[1] as Int) },
    ),
) { LazyListState(initialIndex) }

/** 一条作品货架内容的 key：按作品的顺序与 id。 */
internal fun worksKeyOf(subjects: List<SubjectBo>): String = subjects.joinToString(",") { it.id.toString() }

/**
 * 压在封面上、或跟在标题下的一行短元信息（拼盘的大图、封面流正中那张的说明）：年份与分数，各自没有就不写。
 * 分数照公共评分开关（[subjectRatingText]）。
 */
@Composable
internal fun yearAndScoreParts(subject: SubjectBo): List<String> = listOfNotNull(
    subject.date?.let(::parseSubjectYearMonth)?.let { stringResource(R.string.detail_meta_year, it.year) },
    subjectRatingText(subject.ratingScore, LocalAppFeatures.current, ::starScoreText),
)
