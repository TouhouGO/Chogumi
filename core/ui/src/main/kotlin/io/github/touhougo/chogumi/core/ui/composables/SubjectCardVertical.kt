package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.ui.collection.PosterMark
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 条目海报卡（探索货架、搜索结果）：[PosterCard] 的 [PosterCardStyle.SHELF] 排法。宽度由调用方的 [modifier] 给：
 * 货架横排给 [ChogumiDimens.CoverWidth]，网格里卡片铺满一格（格子的富余宽度要匀到每一格里，左右留白才相等）；
 * 右下角按公共评分开关压分数徽章（长按标记的待提交胶囊在封面底部时让位），[coverOverlay] 再叠别的角标（名次大字）。
 * 长按标记随页面（[rememberPosterMark]）。
 */
@Composable
fun SubjectCardVertical(
    subject: SubjectBo,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    coverOverlay: @Composable BoxScope.() -> Unit = {},
) {
    val mark = rememberPosterMark(subject.id, subject.type)
    PosterCard(
        imageUrl = subject.coverUrl,
        title = displayNameOf(subject),
        style = PosterCardStyle.SHELF,
        onClick = { onClick(subject.id) },
        mark = mark,
        modifier = modifier,
        coverOverlay = {
            coverOverlay()
            CoverScoreBadge(subject.ratingScore, mark)
        },
    )
}

/**
 * 封面右下角的分数角标（海报卡与探索拼盘的小图共用）：显不显示只问 [subjectCardRatingSubtitle]（封面评分开关、有没有分）；
 * 长按标记的待提交胶囊压在封面底部时让位。
 */
@Composable
fun BoxScope.CoverScoreBadge(score: Double, mark: PosterMark?) {
    val text = subjectCardRatingSubtitle(score, LocalAppFeatures.current) ?: return
    AnimatedVisibility(
        visible = mark?.pending == null,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(CoverBadgeInset),
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        CoverBadge(text, Modifier.testTag("subject_score_badge"))
    }
}

/** [SubjectCardVertical] 的骨架：同一种排法，宽度同样由调用方给，同宽就同高。 */
@Composable
fun SubjectCardVerticalSkeleton(modifier: Modifier = Modifier) {
    PosterCardSkeleton(PosterCardStyle.SHELF, modifier)
}
