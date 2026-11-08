package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 探索页第一次进来的聚焦引导走到哪一步（一台设备只出一次）：先指热门的第一张封面、演示长按环，再指第一条货架的标题、说长按能整理。
 */
enum class ExploreGuideStep { CoverMark, Arrange }

/** 引导第一步指着的那张封面（热门的第一项）与第二步指着的那个标题（近期热门之后的第一条货架）。 */
internal const val GuideCoverTarget = "explore_guide_cover"
internal const val GuideArrangeTarget = "explore_guide_arrange"

/**
 * 引导的进退：没走完过就从第一步开始，卡上的按钮往下走、结束；两步都走完（[finish]）才记下，中途离开下次还从头出。
 * 偏好读不出就不出引导（页面照常用）。状态是 [ExploreUiState.guide]，只由这里改。
 */
class ExploreGuide(
    private val scope: CoroutineScope,
    private val write: ((ExploreGuideStep?) -> ExploreGuideStep?) -> Unit,
    private val failures: FailureNotices,
    private val preferences: PreferencesRepository,
    /** 「去整理」：引导收起后进整理。 */
    private val arrange: () -> Unit,
) {
    init {
        scope.launch {
            val shown = storageOutcomeOf { preferences.exploreGuideShown.first() }
            if (shown is Outcome.Success && !shown.value) write { ExploreGuideStep.CoverMark }
        }
    }

    /** 「下一步」：从封面那一步走到标题那一步。 */
    fun next() = write { step -> if (step == ExploreGuideStep.CoverMark) ExploreGuideStep.Arrange else step }

    /** 「知道了」「去整理」：收起、记下走完了；[thenArrange] 时接着进整理。 */
    fun finish(thenArrange: Boolean) {
        write { null }
        scope.launchWrite(failures) { preferences.setExploreGuideShown() }
        if (thenArrange) arrange()
    }
}
